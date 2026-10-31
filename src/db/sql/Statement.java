package db.sql;

import db.record.Row;
import db.record.Schema;

import java.util.List;

public record Statement(StatementType type, String table, Row row, List<Integer> projection, Predicate where, Schema schema, String sql, List<Assignment> assignments, IndexSpec index, SelectSpec query) {

    
    public record Assignment(int column, Object value) {}

    public record SelectSpec(JoinSpec join, OrderSpec order, int limit, boolean count) {}
    
    public record JoinSpec(String table, int leftColumn, int rightColumn) {}

    public record OrderSpec(int column, boolean descending) {}

    public record IndexSpec(String name, int column) {}

    public static Statement insert(String table, Row row) {
        return new Statement(StatementType.INSERT, table, row, null, null, null, null, null, null, null);
    }

    public static Statement select(String table, List<Integer> projection, Predicate where, SelectSpec query) {
        return new Statement(StatementType.SELECT, table, null, List.copyOf(projection), where, null, null, null, null, query);
    }

    public static Statement createTable(Schema schema, String sql) {
        return new Statement(StatementType.CREATE_TABLE, schema.tableName(), null, null, null, schema, sql, null, null, null);
    }

    public static Statement delete(String table, Predicate where) {                                   
        return new Statement(StatementType.DELETE, table, null, null, where, null, null, null, null, null);
    }

    public static Statement update(String table, List<Assignment> assignments, Predicate where) {     
        return new Statement(StatementType.UPDATE, table, null, null, where, null, null, List.copyOf(assignments), null, null);
    }

    public static Statement createIndex(String indexName, String table, int column, String sql) {   // 9
        return new Statement(StatementType.CREATE_INDEX, table, null, null, null, null, sql, null,
                new IndexSpec(indexName, column), null);
    }

    public static Statement transaction(StatementType type) {
        return new Statement(type, null, null, null, null, null, null, null, null, null);
    }
}
