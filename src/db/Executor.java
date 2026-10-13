package db;

import java.io.IOException;

public final class Executor {

    private Executor() {}

    static String formatRow(Row row) {
        return "( " + row.id() + ", " + row.username() + ", " + row.email() + " )";
    }

    public static void execute(Statement statement, Table table) throws ExecuteException {
        switch (statement.type()) {
            case INSERT:
                table.insert(statement.row()); 
                break;
            case SELECT:
                try {
                    for(Cursor c = Cursor.atStart(table); !c.isEndOfTable(); c.advance()){
                        System.out.println(formatRow(c.read()));
                    }
                } catch (IOException e) {
                    throw new ExecuteException("Select failed: " + e.getMessage());
                } 
                break;
            default:
                break;
        }
    }
}