package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


public class Database implements AutoCloseable {

    static final int MAGIC = 0x4A444235; 
    static final int HEADER_MAGIC_OFFSET = 0;
    static final int HEADER_CATALOG_ROOT_OFFSET = 4;
    static final int CATALOG_ROOT = 1;

    static final String CATALOG_NAME = "jdb_catalog";
    static final int CATALOG_ID = 0, CATALOG_NAME_COL = 1, CATALOG_ROOT_COL = 2, CATALOG_SQL = 3;
    static final int MAX_TABLE_NAME_BYTES = 64;
    static final int MAX_SQL_BYTES = 1000;
    static final Schema CATALOG_SCHEMA = new Schema(CATALOG_NAME, List.of(
            Column.integer("id"),
            Column.text("name", MAX_TABLE_NAME_BYTES),
            Column.integer("root_page"),
            Column.text("sql", MAX_SQL_BYTES)));

    private final Pager pager;
    private final BTree catalog;

    private final Map<String, BTree> tables = new TreeMap<>();
    private final Map<String, String> sqlByTable = new TreeMap<>();
    private int nextCatalogId = 1;

    private boolean inTransaction = false;

    @FunctionalInterface
    interface Work {
        void run() throws IOException, ExecuteException;
    }

    public Database(Path path) throws IOException {
        this.pager = new Pager(path);
        try {
            if (pager.numPages() == 0) {
                BTree.initializeEmptyRoot(pager, CATALOG_ROOT);   
                ByteBuffer header = pager.getPage(0);
                header.putInt(HEADER_MAGIC_OFFSET, MAGIC);
                header.putInt(HEADER_CATALOG_ROOT_OFFSET, CATALOG_ROOT);
                pager.markDirty(0);
                pager.commit();
            } else {
                if(pager.numPages() <= CATALOG_ROOT){
                    throw new IOException("Corrupt database: file has no catalog page");
                }
                ByteBuffer header = pager.getPage(0);
                int magic = header.getInt(HEADER_MAGIC_OFFSET);
                if(magic != MAGIC){
                    throw new IOException(String.format(
                        "Not a database file (bad magic 0x%08X, expected 0x%08X)", magic, MAGIC));
                }
                int catalogRoot = header.getInt(HEADER_CATALOG_ROOT_OFFSET);
                if(catalogRoot != CATALOG_ROOT){
                    throw new IOException("Corrupt header: catalog root is page " + catalogRoot
                        + ", expected " + CATALOG_ROOT);
                }
            }
            this.catalog = new BTree(pager, CATALOG_NAME, CATALOG_ROOT, CATALOG_SCHEMA);
            loadCatalog();
        } catch (IOException | RuntimeException e) {
            pager.close();
            throw e;
        }
    }

    private void loadCatalog() throws IOException {
        tables.clear();
        sqlByTable.clear();
        int maxId = 0;
        for(Cursor c = Cursor.atStart(catalog); !c.isEndOfTable(); c.advance()){
            Row row = c.read();
            int id      = (Integer) row.get(CATALOG_ID);
            String name = (String)  row.get(CATALOG_NAME_COL);
            int root    = (Integer) row.get(CATALOG_ROOT_COL);
            String sql  = (String)  row.get(CATALOG_SQL);

            Schema schema = schemaFromSql(sql);

            if(root <= CATALOG_ROOT || root >= pager.numPages()){
                throw new IOException("Corrupt catalog: table '" + name + "' has root page " + root + " (file has " + pager.numPages() + " pages)");
            }
            if(!LeafNode.isRoot(pager.getPage(root))){
                throw new IOException("Corrupt catalog: table '" + name + "' root page " + root + " is not marked as a root");
            }

            String key = name.toLowerCase();
            if(tables.containsKey(key)){
                throw new IOException("Corrupt catalog: duplicate table '" + name + "'");
            }
            tables.put(key, new BTree(pager, name, root, schema));
            sqlByTable.put(key, sql);
            maxId = Math.max(maxId, id);
        }
        nextCatalogId = maxId + 1;
    }

    static Schema schemaFromSql(String sql) throws IOException {
        try {
            Ast.Stmt stmt = SqlParser.parse(Lexer.tokenize(sql));
            if (!(stmt instanceof Ast.CreateTable create)) {
                throw new IOException("Corrupt catalog: not a CREATE TABLE: " + sql);
            }
            return Binder.schemaFrom(create);
        } catch (PrepareException e) {
            throw new IOException("Corrupt catalog: " + e.getMessage() + " in: " + sql);
        }
    }

    BTree catalog() {
        return catalog;
    }

    public BTree table(String name) {
        return tables.get(name.toLowerCase());
    }

    public List<String> tableNames() {
        List<String> names = new ArrayList<>();
        for (BTree t : tables.values()) names.add(t.name());
        return names;
    }

    public String sqlFor(String name) {
        return sqlByTable.get(name.toLowerCase());
    }


    String pageStats() throws IOException {
        return pager.numPages() + " pages in the file, " + pager.freeList().size() + " on the free list";
    }

    String checkPages() throws IOException {
        int numPages = pager.numPages();
        String[] owner = new String[numPages];
        owner[0] = "file header";

        List<BTree> trees = new ArrayList<>();
        trees.add(catalog);
        trees.addAll(tables.values());

        for (BTree tree : trees) {
            List<Integer> pages = new ArrayList<>();
            tree.collectPages(pages);
            for (int p : pages) {
                if (p <= 0 || p >= numPages) {
                    return tree.name() + " references page " + p + " (file has " + numPages + " pages)";
                }
                if (owner[p] != null) {
                    return "page " + p + " is used by both " + owner[p] + " and " + tree.name();
                }
                owner[p] = tree.name();
            }
        }

       
        List<Integer> free;
        try {
            free = pager.freeList();
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        for (int p : free) {
            if (owner[p] != null) {
                return "page " + p + " is on the free list but used by " + owner[p];
            }
            if (pager.getPage(p).get(0) != Pager.TYPE_FREE) {
                return "free list: page " + p + " is not marked free";
            }
            owner[p] = "free list";
        }

        List<Integer> orphans = new ArrayList<>();
        for (int p = 1; p < numPages; p++) {
            if (owner[p] == null) orphans.add(p);
        }
        if (!orphans.isEmpty()) {
            return orphans.size() + " unreferenced page(s): " + orphans;
        }
        return null;
    }

    public void createTable(Schema schema, String sql) throws IOException, ExecuteException {
        String key = schema.tableName().toLowerCase();
        if (tables.containsKey(key)) {
            throw new ExecuteException("Table '" + schema.tableName() + "' already exists");
        }
        int rootPage = pager.allocatePage();
        BTree.initializeEmptyRoot(pager, rootPage);
        int id = nextCatalogId;
        catalog.insert(new Row(List.of(id, schema.tableName(), rootPage, sql)));
        tables.put(key, new BTree(pager, schema.tableName(), rootPage, schema));
        sqlByTable.put(key, sql);
        nextCatalogId = id + 1;
    }

    public boolean inTransaction() {
        return inTransaction;
    }

    public void begin() throws ExecuteException {
       if(inTransaction){
        throw new ExecuteException("Already in a transaction");
       }
       inTransaction = true;
    }

    public void commit() throws ExecuteException {
        if(!inTransaction){
            throw new ExecuteException("no active transaction");
        }
        inTransaction = false;
        commitOrDiscard();
    }

    public void rollback() throws ExecuteException {
        if(!inTransaction){
            throw new ExecuteException("no active transaction");
        }
        inTransaction = false;
        discard();
    }

    void runStatement(Work work) throws ExecuteException {
        try {
            work.run();
        } catch (ExecuteException e) {
           throw e;
        } catch(IOException | RuntimeException e){
            boolean wasExplicit = inTransaction;
            inTransaction = false;
            discard();
            throw new ExecuteException("Statement failed: " + e.getMessage()+ (wasExplicit ? " - transaction rolled back" : ""));
        }
        if(!inTransaction){
            commitOrDiscard();
        }
    }
    
    private void commitOrDiscard() throws ExecuteException {
        try {
            pager.commit();
        } catch (IOException e) {
            discard();
            throw new ExecuteException("COMMIT failed, transaction rolled back: " + e.getMessage());
        }
    }

    private void discard() throws ExecuteException {
        pager.abort();
        try {
            loadCatalog();
        } catch (IOException e) {
            throw new ExecuteException("Rollback could not reload the catalog: " + e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        pager.close();
    }
}
