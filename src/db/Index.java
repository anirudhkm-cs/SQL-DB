package db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class Index {

    final String name;
    final String tableName;
    final int column;
    final BTree tree;

    Index(String name, String tableName, int column, BTree tree) {
        this.name = name;
        this.tableName = tableName;
        this.column = column;
        this.tree = tree;
    }

    static Schema schemaFor(String indexName, Schema table, int column) {
        return new Schema(indexName, List.of(Column.integer("id"), table.column(column)));
    }

    static int prefixOf(Object value) {
        if(value instanceof Integer){
            return (Integer) value;
        }
        byte[] b = ((String) value).getBytes(StandardCharsets.UTF_8);
        int p = 0;
        for(int k=0; k<4; k++){
            p <<= 8;
            if(k<b.length){
                p |= b[k] & 0xFF;
            }
        }
        return p ^Integer.MIN_VALUE;
    }

    static long keyFor(Object value, int id) {
        return ((long) prefixOf(value) << 32) | (id & 0xFFFFFFFFL);
    }

    static int prefixOfKey(long key) {
        return (int) (key >> 32);
    }

    void add(Row row) throws IOException {
       long key = keyFor(row.get(column), row.key());
       tree.insertEntry(key, RecordCodec.encode(new Row(List.of(row.key(), row.get(column)))));
    }

    void remove(Row row) throws IOException {
        if(!tree.delete(keyFor(row.get(column), row.key()))){
            throw new IllegalStateException("index " + name + " has no entry for row " + row.key());
        }
    }

    List<Integer> lookup(Object value) throws IOException {
        List<Integer> ids = new ArrayList<>();
        int prefix = prefixOf(value);
        for(Cursor c = Cursor.atOrAfter(tree, keyFor(value, 0)); !c.isEndOfTable() && prefixOfKey(c.key()) == prefix; c.advance()){
            Row entry = c.read();
            if(Objects.equals(entry.get(1), value)){
                ids.add(entry.key());
            }
        }
        return ids;
    }

    String verify(BTree table) throws IOException {
        List<Long> expected = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Cursor c = Cursor.atStart(table); !c.isEndOfTable(); c.advance()) {
            Row row = c.read();
            expected.add(keyFor(row.get(column), row.key()));
            values.add(row.get(column));
        }
        List<Long> actual = new ArrayList<>();
        Object prev = null;
        for (Cursor c = Cursor.atStart(tree); !c.isEndOfTable(); c.advance()) {
            Row entry = c.read();
            long key = c.key();
            if (key != keyFor(entry.get(1), entry.key())) {
                return "entry for row " + entry.key() + " has key " + key + ", expected " + keyFor(entry.get(1), entry.key());
            }
            if (prev != null && comparePrefix(prev, entry.get(1)) > 0) {
                return "out of value order: '" + prev + "' comes before '" + entry.get(1) + "'";
            }
            prev = entry.get(1);
            actual.add(key);
        }
        List<Long> sortedExpected = new ArrayList<>(expected);
        sortedExpected.sort(null);
        if (!sortedExpected.equals(actual)) {
            List<Long> missing = new ArrayList<>(sortedExpected);
            missing.removeAll(actual);
            List<Long> extra = new ArrayList<>(actual);
            extra.removeAll(sortedExpected);
            return actual.size() + " entries for " + expected.size() + " rows; missing row ids "
                    + idsOf(missing) + ", extra entries for row ids " + idsOf(extra);
        }
        for (int i = 0; i < expected.size(); i++) {
            Cursor c = tree.find(expected.get(i));
            Row entry = c.read();
            if (!Objects.equals(entry.get(1), values.get(i))) {
                return "entry for row " + entry.key() + " holds " + entry.get(1) + ", table has " + values.get(i);
            }
        }
        return null;
    }

    private static int comparePrefix(Object a, Object b) {
        if (a instanceof Integer x) {
            return Integer.compare(x, (Integer) b);
        }
        byte[] p = ((String) a).getBytes(StandardCharsets.UTF_8);
        byte[] q = ((String) b).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 4; i++) {
            int x = i < p.length ? p[i] & 0xFF : 0;
            int y = i < q.length ? q[i] & 0xFF : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static List<Integer> idsOf(List<Long> keys) {
        List<Integer> out = new ArrayList<>();
        for (long k : keys) {
            out.add((int) k);
            if (out.size() == 5) break;
        }
        return out;
    }
}
