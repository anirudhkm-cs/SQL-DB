package db;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public final class Executor {

    private Executor() {}

    static String formatRow(Row row, List<Integer> projection) {
        StringJoiner out = new StringJoiner(", ", "( ", " )");
        for (int col : projection) {
            out.add(String.valueOf(row.get(col)));
        }
        return out.toString();
    }

    public static void execute(Statement statement, Database db) throws ExecuteException {
        switch (statement.type()) {
            case BEGIN -> db.begin();
            case COMMIT -> db.commit();
            case ROLLBACK -> db.rollback();
            default -> db.runStatement(() -> executeData(statement, db));
        }
    }

    static String lastPlan = "(none yet)";

    private static void executeData(Statement statement, Database db) throws IOException, ExecuteException {
        switch (statement.type()) {
            case CREATE_TABLE -> db.createTable(statement.schema(), statement.sql());
            case CREATE_INDEX -> db.createIndex(statement.index(), statement.table(), statement.sql());
            case INSERT -> db.insertRow(statement.table(), statement.row());
            case SELECT -> select(statement, db);
            case DELETE -> delete(statement, db);
            case UPDATE -> update(statement, db);
            default -> throw new IllegalStateException("not a data statement: " + statement.type());
        }
    }

    private static void select(Statement s, Database db) throws IOException {
        for (Row row : matchingRows(s, db)) {
            System.out.println(formatRow(row, s.projection()));
        }
    }

    private static List<Row> matchingRows(Statement s, Database db) throws IOException {
        BTree tree = db.table(s.table());
        Statement.Filter f = s.filter();
        List<Row> out = new ArrayList<>();

        if(f != null && f.column()==0){
            lastPlan = "key lookup on " + tree.name();
            int key = (Integer) f.value();
            Cursor c = tree.find(key);
            if(!c.isEndOfTable()){
                Row row = c.read();
                if(row.key() == key){
                    out.add(row);
                }
            }
            return out;
        }
        Index idx = f == null ? null : db.indexOn(tree.name(), f.column());
        if (idx != null) {
            lastPlan = "index " + idx.name + " on " + tree.name() + "(" + tree.schema().column(f.column()).name() + ")";
            for(int id: idx.lookup(f.value())){
                Cursor c = tree.find(id);
                Row row = c.isEndOfTable() ? null : c.read();
                if(row == null || row.key() != id || !matches(row, f)){
                    throw new IllegalStateException("index " + idx.name + " points at row " + id + " but the table " + (row == null || row.key() != id ? "has no such row" : "row doesn't match"));
                }
                out.add(row);
            }
            return out;
        }
        lastPlan = "full scan of " + tree.name();
        for(Cursor c = Cursor.atStart(tree); !c.isEndOfTable(); c.advance()){
            Row row = c.read();
            if(matches(row, f)){
                out.add(row);
            }
        }
        return out;
    }

    private static void delete(Statement s, Database db) throws IOException, ExecuteException {
        List<Row> rows = matchingRows(s, db);
        int count = 0;
        for(Row row: rows){
            if(db.deleteRow(s.table(), row)){
                count++;
            }
        }
        System.out.println(count + (count == 1 ? " row deleted" : " rows deleted"));
    }

    private static void update(Statement s, Database db) throws IOException, ExecuteException {
        for (Statement.Assignment a : s.assignments()) {
            if (a.column() == 0) {
                throw new ExecuteException("Cannot update the key column");
            }
        }
        List<Row> rows = matchingRows(s, db);
        int count = 0;
        for (Row row : rows) {
            List<Object> values = new ArrayList<>(row.values());
            for (Statement.Assignment a : s.assignments()) {
                values.set(a.column(), a.value());
            }
            if (db.updateRow(s.table(), row, new Row(values))) {
                count++;
            }
        }
        System.out.println(count + (count == 1 ? " row updated" : " rows updated"));
    }

    @SuppressWarnings("unused")
    private static boolean matches(Row row, Statement.Filter f) {
        return f == null || Objects.equals(row.get(f.column()), f.value());
    }
}
