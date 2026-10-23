package db;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/** Execute stage: act on the table. Never looks at raw text. */
public final class Executor {

    private Executor() {}

    static String formatRow(Row row, List<UsersColumn> projection) {
        StringJoiner out = new StringJoiner(", ", "( ", " )");
        for(UsersColumn col : projection){
            out.add(String.valueOf(col.valueIn(row)));
        }
        return out.toString();
    }

    public static void execute(Statement statement, Table table) throws ExecuteException {
        switch (statement.type()) {
            case INSERT:
                table.insert(statement.row());
                break;
            case SELECT:
                try {
                    select(statement, table);
                } catch (IOException e) {
                    throw new ExecuteException("Select failed: " + e.getMessage());
                }
                break;
            default:
                break;
        }
    }

    private static void select(Statement s, Table table) throws IOException {
        Statement.Filter f = s.filter();

        if (f != null && f.column() == UsersColumn.ID) {
            int key = (Integer) f.value();
            Cursor c = table.find(key);
            if(!c.isEndOfTable()){
                Row row = c.read();
                if(row.id() == key){
                    System.out.println(formatRow(row, s.projection()));
                }
            }
            return;
        }

        for(Cursor c = Cursor.atStart(table); !c.isEndOfTable(); c.advance()){
            Row row = c.read();
            if(f == null || Objects.equals(f.column().valueIn(row), f.value())){
                System.out.println(formatRow(row, s.projection()));
            }
        }
    }
}
