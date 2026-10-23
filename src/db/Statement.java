package db;

import java.util.List;


public record Statement(StatementType type, Row row, List<UsersColumn> projection, Filter filter) {
    public record Filter(UsersColumn column, Object value) {}
}
