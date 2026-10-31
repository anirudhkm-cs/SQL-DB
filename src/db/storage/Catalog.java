package db.storage;

import java.util.Map;
import java.util.TreeMap;

public final class Catalog {

    public final Map<String, BTree> tables = new TreeMap<>();
    public final Map<String, String> sqlByTable = new TreeMap<>();
    public final Map<String, Index> indexes = new TreeMap<>();
    public final Map<String, String> sqlByIndex = new TreeMap<>();
    public int nextCatalogId = 1;

    public Catalog copy() {
        Catalog c = new Catalog();
        c.tables.putAll(tables);
        c.sqlByTable.putAll(sqlByTable);
        c.indexes.putAll(indexes);
        c.sqlByIndex.putAll(sqlByIndex);
        c.nextCatalogId = nextCatalogId;
        return c;
    }
}
