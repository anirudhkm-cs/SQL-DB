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

    public Database(Path path) throws IOException {
        this.pager = new Pager(path);
        try {
            if (pager.numPages() == 0) {
                BTree.initializeEmptyRoot(pager, CATALOG_ROOT);
                pager.flushPage(CATALOG_ROOT);
                pager.sync();

                ByteBuffer header = pager.getPage(0);
                header.putInt(HEADER_MAGIC_OFFSET, MAGIC);
                header.putInt(HEADER_CATALOG_ROOT_OFFSET, CATALOG_ROOT);
                pager.flushPage(0);
                pager.sync();
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

        List<Integer> orphans = new ArrayList<>();
        for (int p = 1; p < numPages; p++) {
            if (owner[p] == null) orphans.add(p);
        }
        if (!orphans.isEmpty()) {
            return orphans.size() + " unreferenced page(s): " + orphans;
        }
        return null;
    }

    public void createTable(Schema schema, String sql) throws ExecuteException {
        String key = schema.tableName().toLowerCase();
        if (tables.containsKey(key)) {
            throw new ExecuteException("Table '" + schema.tableName() + "' already exists");
        }
        try {
            int rootPage = pager.allocatePage();
            BTree.initializeEmptyRoot(pager, rootPage);
            pager.flushPage(rootPage);
            pager.sync();

            int id = nextCatalogId;
            catalog.insert(new Row(List.of(id, schema.tableName(), rootPage, sql)));

            tables.put(key, new BTree(pager, schema.tableName(), rootPage, schema));
            sqlByTable.put(key, sql);
            nextCatalogId = id+1;
        } catch (IOException e) {
            throw new ExecuteException("CREATE TABLE failed: " + e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        pager.close();
    }
}
