package db;

import java.util.Map;
import java.util.TreeMap;

final class Catalog {

    final Map<String, BTree> tables = new TreeMap<>();
    final Map<String, String> sqlByTable = new TreeMap<>();
    final Map<String, Index> indexes = new TreeMap<>();
    final Map<String, String> sqlByIndex = new TreeMap<>();
    int nextCatalogId = 1;

    Catalog copy() {
        Catalog c = new Catalog();
        c.tables.putAll(tables);
        c.sqlByTable.putAll(sqlByTable);
        c.indexes.putAll(indexes);
        c.sqlByIndex.putAll(sqlByIndex);
        c.nextCatalogId = nextCatalogId;
        return c;
    }
}
