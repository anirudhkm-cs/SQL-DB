package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;


public class Database implements AutoCloseable {

    static final int MAGIC = 0x4A444236;    
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

    private Catalog live = new Catalog();

    final ReentrantLock writeLock = new ReentrantLock(true);

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
                pager.commit(new Catalog());
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
            pager.publishCatalog(live.copy());
        } catch (IOException | RuntimeException e) {
            pager.close();
            throw e;
        }
    }

    private void loadCatalog() throws IOException {
        live = new Catalog();
        Map<String, BTree> tables = live.tables;
        Map<String, String> sqlByTable = live.sqlByTable;
        Map<String, Index> indexes = live.indexes;
        int maxId = 0;
        for(Cursor c = Cursor.atStart(catalog); !c.isEndOfTable(); c.advance()){
            Row row = c.read();
            int id      = (Integer) row.get(CATALOG_ID);
            String name = (String)  row.get(CATALOG_NAME_COL);
            int root    = (Integer) row.get(CATALOG_ROOT_COL);
            String sql  = (String)  row.get(CATALOG_SQL);

            if(root <= CATALOG_ROOT || root >= pager.numPages()){
                throw new IOException("Corrupt catalog: '" + name + "' has root page " + root + " (file has " + pager.numPages() + " pages)");
            }
            if(!LeafNode.isRoot(pager.getPage(root))){
                throw new IOException("Corrupt catalog: '" + name + "' root page " + root + " is not marked as a root");
            }
            String key = name.toLowerCase();
            if(tables.containsKey(key) || indexes.containsKey(key)){
                throw new IOException("Corrupt catalog: duplicate name '" + name + "'");
            }

            Ast.Stmt stmt = parseCatalogSql(sql);
            if (stmt instanceof Ast.CreateTable create) {
                tables.put(key, new BTree(pager, name, root, schemaFrom(create, sql)));
                sqlByTable.put(key, sql);
            } else if (stmt instanceof Ast.CreateIndex ci) {
                BTree table = tables.get(ci.table().text().toLowerCase());
                if(table == null){
                    throw new IOException("Corrupt catalog: index '" + name + "' is on unknown table '" + ci.table().text() + "'");
                }
                int col = table.schema().indexOf(ci.column().text());
                if(col < 0){
                    throw new IOException("Corrupt catalog: index '" + name + "' is on unknown column '" + ci.column().text() + "'");
                }
                if(col == 0){
                    throw new IOException("Corrupt catalog: index '" + name + "' is on the primary key column");
                }
                registerIndex(name, table, col, root, sql);
            } else {
                throw new IOException("Corrupt catalog: not a CREATE statement: " + sql);
            }
            maxId = Math.max(maxId, id);
        }
        live.nextCatalogId = maxId + 1;
    }

    private void registerIndex(String name, BTree table, int col, int root, String sql) {
        BTree tree = new BTree(pager, name, root, Index.schemaFor(name, table.schema(), col));
        String key = name.toLowerCase();
        live.indexes.put(key, new Index(name, table.name(), col, tree));
        live.sqlByIndex.put(key, sql);
    }

    static Ast.Stmt parseCatalogSql(String sql) throws IOException {
        try {
            return SqlParser.parse(Lexer.tokenize(sql));
        } catch (PrepareException e) {
            throw new IOException("Corrupt catalog: " + e.getMessage() + " in: " + sql);
        }
    }

    static Schema schemaFrom(Ast.CreateTable create, String sql) throws IOException {
        try {
            return Binder.schemaFrom(create);
        } catch (PrepareException e) {
            throw new IOException("Corrupt catalog: " + e.getMessage() + " in: " + sql);
        }
    }

    BTree catalog() {
        return catalog;
    }

    private Catalog cat() {
        Pager.Snapshot s = pager.snapshot();
        return s!= null ? s.catalog() : live;
    }

    void beginRead() throws IOException {
        pager.beginRead();
    }

    void endRead() {
        pager.endRead();
    }

    String versionStats() {
        return pager.versionStats();
    }

    public BTree table(String name) {
        return cat().tables.get(name.toLowerCase());
    }

    public List<String> tableNames() {
        List<String> names = new ArrayList<>();
        for (BTree t : cat().tables.values()) names.add(t.name());
        return names;
    }

    public String sqlFor(String name) {
        String sql = cat().sqlByTable.get(name.toLowerCase());
        if (sql == null) {
            return cat().sqlByIndex.get(name.toLowerCase());
        }
        StringBuilder out = new StringBuilder(sql);
        for (Index idx : indexesOn(name)) {
            out.append("\n").append(cat().sqlByIndex.get(idx.name.toLowerCase()));
        }
        return out.toString();
    }

    boolean nameInUse(String name) {
        return cat().tables.containsKey(name.toLowerCase()) || cat().indexes.containsKey(name.toLowerCase());
    }

    Index index(String name) {
        return cat().indexes.get(name.toLowerCase());
    }

    BTree tree(String name) {
        BTree t = table(name);
        if (t != null) return t;
        Index idx = index(name);
        return idx == null ? null : idx.tree;
    }

    List<Index> indexesOn(String table) {
        List<Index> out = new ArrayList<>();
        for (Index idx : cat().indexes.values()) {
            if (idx.tableName.equalsIgnoreCase(table)) out.add(idx);
        }
        return out;
    }

    Index indexOn(String table, int column) {
        for (Index idx : indexesOn(table)) {
            if (idx.column == column) return idx;
        }
        return null;
    }

    List<Index> allIndexes() {
        return new ArrayList<>(cat().indexes.values());
    }

    public void createIndex(Statement.IndexSpec spec, String tableName, String sql) throws IOException, ExecuteException {
        if(nameInUse(spec.name())){
            throw new ExecuteException("Name '" + spec.name() + "' is already in use");
        }
        int rootPage = pager.allocatePage();
        BTree.initializeEmptyRoot(pager, rootPage);
        int id = live.nextCatalogId;
        catalog.insert(new Row(List.of(id, spec.name(), rootPage, sql)));
        live.nextCatalogId = id + 1;

        BTree table = table(tableName);
        registerIndex(spec.name(), table, spec.column(), rootPage, sql);

        List<Row> rows = new ArrayList<>();
        for(Cursor c = Cursor.atStart(table); !c.isEndOfTable(); c.advance()){
            rows.add(c.read());
        }
        Index index = index(spec.name());
        for(Row row: rows){
            index.add(row);
        }
    }

    void insertRow(String tableName, Row row) throws IOException, ExecuteException {
        table(tableName).insert(row);
        for(Index idx: indexesOn(tableName)){
            idx.add(row);
        }
    }

    boolean deleteRow(String tableName, Row old) throws IOException, ExecuteException {
        if (!table(tableName).delete(old.key())) {
            return false;
        }
        for(Index idx: indexesOn(tableName)){
            idx.remove(old);
        }
        return true;
    }

    boolean updateRow(String tableName, Row old, Row updated) throws IOException, ExecuteException {
        if (!table(tableName).update(updated)) {
            return false;
        }
        for(Index idx: indexesOn(tableName)){
            if(!Objects.equals(old.get(idx.column), updated.get(idx.column))){
                idx.remove(old);
                idx.add(updated);
            }
        }
        return true;
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
        trees.addAll(cat().tables.values());
        for (Index idx : cat().indexes.values()) trees.add(idx.tree);          

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
        if (cat().tables.containsKey(key)) {
            throw new ExecuteException("Table '" + schema.tableName() + "' already exists");
        }
        int rootPage = pager.allocatePage();
        BTree.initializeEmptyRoot(pager, rootPage);
        int id = live.nextCatalogId;
        catalog.insert(new Row(List.of(id, schema.tableName(), rootPage, sql)));
        live.tables.put(key, new BTree(pager, schema.tableName(), rootPage, schema));
        live.sqlByTable.put(key, sql);
        live.nextCatalogId = id + 1;
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
        if (pager.snapshot() != null) {
            try {
                work.run();
            } catch (ExecuteException e) {
                throw e;
            } catch(IOException | RuntimeException e){
                throw new ExecuteException("Statement failed: " + e.getMessage());
            }
            return;
        }
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
            pager.commit(live.copy());
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
