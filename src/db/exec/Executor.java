package db.exec;

import db.Database;
import db.ExecuteException;
import db.Output;
import db.record.Row;
import db.sql.Predicate;
import db.sql.Statement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;   
import java.util.List;

public final class Executor {

    private Executor() {}

    static List<Object> project(Row row, List<Integer> projection) {
        List<Object> out = new ArrayList<>(projection.size());
        for (int col : projection) {
            out.add(row.get(col));
        }
        return out;
    }

    public static void execute(Statement statement, Database db, Output out) throws ExecuteException {
        switch (statement.type()) {
            case BEGIN -> db.begin();
            case COMMIT -> db.commit();
            case ROLLBACK -> db.rollback();
            default -> db.runStatement(() -> executeData(statement, db, out));
        }
    }

    private static final ThreadLocal<String> LAST_PLAN = ThreadLocal.withInitial(() -> "(none yet)");

    public static String plan() {
        return LAST_PLAN.get();
    }

    static void setPlan(String plan) {
        LAST_PLAN.set(plan);
    }

    private static void executeData(Statement statement, Database db, Output out) throws IOException, ExecuteException {
        switch (statement.type()) {
            case CREATE_TABLE -> db.createTable(statement.schema(), statement.sql());
            case CREATE_INDEX -> db.createIndex(statement.index(), statement.table(), statement.sql());
            case INSERT -> db.insertRow(statement.table(), statement.row());
            case SELECT -> select(statement, db, out);
            case DELETE -> delete(statement, db, out);
            case UPDATE -> update(statement, db, out);
            default -> throw new IllegalStateException("not a data statement: " + statement.type());
        }
    }

    private static void select(Statement s, Database db, Output out) throws IOException {
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
            out.row(project(row, projection));
        }
    }

    private static List<Row> matchingRows(Statement s, Database db) throws IOException {
        return Planner.matchingRows(s, db);
    }

    private static void delete(Statement s, Database db, Output out) throws IOException, ExecuteException {
        List<Row> rows = matchingRows(s, db);
        int count = 0;
        for(Row row: rows){
            if(db.deleteRow(s.table(), row)){
                count++;
            }
        }
        out.message(count + (count == 1 ? " row deleted" : " rows deleted"));
    }

    private static void update(Statement s, Database db, Output out) throws IOException, ExecuteException {
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
        out.message(count + (count == 1 ? " row updated" : " rows updated"));
    }
}
