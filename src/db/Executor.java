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
            case CREATE_TABLE:
                db.createTable(statement.schema(), statement.sql());
                break;
            case INSERT:
                BTree tree = db.table(statement.table());
                if (tree == null) {
                    throw new ExecuteException("Table '" + statement.table() + "' does not exist");
                }
                tree.insert(statement.row());
                break;
            case SELECT:
                try {
                    select(statement, db.table(statement.table()));
                } catch (IOException e) {
                    throw new ExecuteException("Select failed: " + e.getMessage());
                }
                break;
            case DELETE:                                                    // 7a
                delete(statement, db.table(statement.table()));
                break;
            case UPDATE:                                                    // 7a
                update(statement, db.table(statement.table()));
                break;
            default:
                break;
        }
    }

    private static void select(Statement s, BTree tree) throws IOException {
        Statement.Filter f = s.filter();

        if(f != null && f.column() == 0){
            int key = (Integer) f.value();
            Cursor c = tree.find(key);
            if(!c.isEndOfTable()){
                Row row = c.read();
                if(row.key() == key){
                    System.out.println(formatRow(row, s.projection()));
                }
            }
            return;
        }

        for(Cursor c = Cursor.atStart(tree); !c.isEndOfTable(); c.advance()){
            Row row = c.read();
            if(matches(row, f)){
                System.out.println(formatRow(row, s.projection()));
            }
        }
    }

    private static List<Row> matchingRows(Statement.Filter f, BTree tree) throws IOException {
        List<Row> out = new ArrayList<>();
        
        if(f != null && f.column()==0){
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
        for(Cursor c = Cursor.atStart(tree); !c.isEndOfTable(); c.advance()){
            Row row = c.read();
            if(matches(row, f)){
                out.add(row);
            }
        }
        return out;
    }

    private static void delete(Statement s, BTree tree) throws ExecuteException {
        List<Row> rows;
        try {
            rows = matchingRows(s.filter(), tree);
        } catch (IOException e) {
            throw new ExecuteException("Delete failed: " + e.getMessage());
        }
        
        int count = 0;
        for(Row row: rows){
            if(tree.delete(row.key())){
                count++;
            }
        }
        System.out.println(count + (count == 1 ? " row deleted" : " rows deleted"));
    }

    private static void update(Statement s, BTree tree) throws ExecuteException {
        for (Statement.Assignment a : s.assignments()) {
            if (a.column() == 0) {
                throw new ExecuteException("Cannot update the key column");
            }
        }

        List<Row> rows;
        try {
            rows = matchingRows(s.filter(), tree);
        } catch (IOException e) {
            throw new ExecuteException("Update failed: " + e.getMessage());
        }

        int count = 0;
        for (Row row : rows) {
            List<Object> values = new ArrayList<>(row.values());
            for (Statement.Assignment a : s.assignments()) {
                values.set(a.column(), a.value());
            }
            if (tree.update(new Row(values))) {
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
