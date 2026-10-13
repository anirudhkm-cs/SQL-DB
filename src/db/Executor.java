package db;

/** Execute stage: act on the table. Never looks at raw text. */
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
                for(Row row: table.selectAll()){
                    System.out.println(formatRow(row));
                }
                break;
            default:
                break;
        }
    }
}