package db;

import java.util.List;

public record Statement(StatementType type, String table, Row row, List<Integer> projection,
                        Filter filter, Schema schema, String sql, List<Assignment> assignments) {

    public record Filter(int column, Object value) {}
    
    public record Assignment(int column, Object value) {}

    public static Statement insert(String table, Row row) {
        return new Statement(StatementType.INSERT, table, row, null, null, null, null, null);
    }

    public static Statement select(String table, List<Integer> projection, Filter filter) {
        return new Statement(StatementType.SELECT, table, null, List.copyOf(projection), filter, null, null, null);
    }

    public static Statement createTable(Schema schema, String sql) {
        return new Statement(StatementType.CREATE_TABLE, schema.tableName(), null, null, null, schema, sql, null);
    }

    public static Statement delete(String table, Filter filter) {                                   // 7a
        return new Statement(StatementType.DELETE, table, null, null, filter, null, null, null);
    }

    public static Statement update(String table, List<Assignment> assignments, Filter filter) {     // 7a
        return new Statement(StatementType.UPDATE, table, null, null, filter, null, null, List.copyOf(assignments));
    }
}
