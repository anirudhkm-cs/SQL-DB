package db;

import java.io.IOException;
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

    @SuppressWarnings("unused")
    private static boolean matches(Row row, Statement.Filter f) {
        return f == null || Objects.equals(row.get(f.column()), f.value());
    }
}
