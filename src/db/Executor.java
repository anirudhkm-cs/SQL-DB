package db;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;  
import java.util.List;
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
        Statement.SelectSpec q = s.query();
        List<Row> rows = q.join() == null ? matchingRows(s, db) : Planner.joinRows(s, db);
        List<Integer> projection = s.projection();

        if(q.order()!=null){
            int col = q.order().column();
            Comparator<Row> cmp = (a, b) -> Predicate.compareValues(a.get(col), b.get(col));
            if(q.order().descending()){
                cmp = cmp.reversed();
            }
            rows = new ArrayList<>(rows);
            rows.sort(cmp);
        }

        if(q.count()){
            rows = List.of(new Row(List.of(rows.size())));
            projection = List.of(0);
        }

        if(q.limit() >= 0 && q.limit() < rows.size()){
            rows = rows.subList(0, q.limit());
        }

        for(Row row: rows){
            System.out.println(formatRow(row, projection));
        }
    }

    /** 10a: the planner decides how to find the rows (and records .plan). Collect first, then change. */
    private static List<Row> matchingRows(Statement s, Database db) throws IOException {
        return Planner.matchingRows(s, db);
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


}
