package db;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Binder {

    private Binder() {}

    public static Statement bind(Ast.Stmt stmt, Database db, String sql) throws PrepareException {
        if (stmt instanceof Ast.Insert insert) return bindInsert(insert, db);
        if (stmt instanceof Ast.Select select) return bindSelect(select, db);
        if (stmt instanceof Ast.CreateTable create) return bindCreate(create, db, sql);
        throw new IllegalStateException("unknown statement " + stmt);
    }

    
    private static Statement bindCreate(Ast.CreateTable create, Database db, String sql)
            throws PrepareException {
        Schema schema = schemaFrom(create);
        
        String name = create.table().text();
        if(db.table(name) != null){
            throw error(create.table().column(), "table '" + name + "' already exists");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > Database.MAX_TABLE_NAME_BYTES){
            throw error(create.table().column(), "table name too long (max " + Database.MAX_TABLE_NAME_BYTES + " bytes)");
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > Database.MAX_SQL_BYTES){
            throw error(1, "statement too long (max " + Database.MAX_SQL_BYTES + " bytes)");
        }
        return Statement.createTable(schema, sql);
    }

    static Schema schemaFrom(Ast.CreateTable create) throws PrepareException {
        List<Column> columns = new ArrayList<>();
        for (Ast.ColumnDef def : create.columns()) {
            String name = def.name().text();
            for (Column existing : columns) {
                if (existing.name().equalsIgnoreCase(name)) {
                    throw error(def.name().column(), "duplicate column '" + name + "'");
                }
            }

            String type = def.type().text();
            Ast.IntLiteral size = def.size();
            if (type.equalsIgnoreCase("INTEGER")) {
                if (size != null) {
                    throw error(size.column(), "INTEGER does not take a size");
                }
                columns.add(Column.integer(name));
            } else if (type.equalsIgnoreCase("TEXT")) {
                if (size == null) {
                    throw error(def.type().column(), "TEXT requires a size, e.g. TEXT(32)");
                }
                if (size.value() < 1 || size.value() > SlottedLeaf.MAX_RECORD_SIZE) {
                    throw error(size.column(),
                            "TEXT size must be between 1 and " + SlottedLeaf.MAX_RECORD_SIZE + ", got " + size.value());
                }
                columns.add(Column.text(name, (int) size.value()));
            } else {
                throw error(def.type().column(), "unknown type '" + type + "'");
            }
        }

        if (columns.isEmpty()) {
            throw error(create.table().column(), "table must have at least one column");
        }
        if (columns.get(0).type() != Column.Type.INTEGER) {
            throw error(create.columns().get(0).type().column(),
                    "first column must be INTEGER (it is the B-tree key)");
        }

        if (columns.size() > 0xFF) {
            throw error(create.columns().get(0xFF).name().column(),
                    "too many columns: " + columns.size() + ", max is " + 0xFF);
        }

        Schema schema = new Schema(create.table().text(), columns);
        int maxSize = RecordCodec.maxEncodedSize(schema);
        if (maxSize > SlottedLeaf.MAX_RECORD_SIZE) {
            throw error(create.table().column(),
                    "row too large: up to " + maxSize + " bytes, max is " + SlottedLeaf.MAX_RECORD_SIZE);
        }
        return schema;
    }

    

    private static Statement bindInsert(Ast.Insert insert, Database db) throws PrepareException {
        Schema schema = schemaOf(insert.table(), db);
        List<Ast.Literal> vals = insert.values();
        int n = schema.columnCount();
        if (vals.size() != n) {
            int col = vals.size() > n ? vals.get(n).column() : vals.get(vals.size() - 1).column();
            throw error(col, "expected " + n + " values, got " + vals.size());
        }
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            values.add(convert(vals.get(i), schema.column(i)));
        }
        if ((int) values.get(0) <= 0) {
            throw error(vals.get(0).column(), schema.column(0).name() + " must be positive");
        }
        return Statement.insert(insert.table().text(), new Row(values));
    }

    private static Statement bindSelect(Ast.Select select, Database db) throws PrepareException {
        Schema schema = schemaOf(select.table(), db);

        List<Integer> projection = new ArrayList<>();
        if (select.columns().isEmpty()) {
            for (int i = 0; i < schema.columnCount(); i++) {
                projection.add(i);
            }
        } else {
            for (Ast.Name name : select.columns()) {
                projection.add(columnIndex(name, schema));
            }
        }
        Statement.Filter filter = null;
        if (select.where() != null) {
            int idx = columnIndex(select.where().column(), schema);
            filter = new Statement.Filter(idx, convert(select.where().value(), schema.column(idx)));
        }
        return Statement.select(select.table().text(), projection, filter);
    }

    private static Schema schemaOf(Ast.Name table, Database db) throws PrepareException {
        BTree tree = db.table(table.text());
        if (tree == null) {
            throw error(table.column(), "no such table '" + table.text() + "'");
        }
        return tree.schema();
    }

    private static int columnIndex(Ast.Name name, Schema schema) throws PrepareException {
        int i = schema.indexOf(name.text());
        if (i < 0) {
            throw error(name.column(), "no such column '" + name.text() + "'");
        }
        return i;
    }

    private static Object convert(Ast.Literal lit, Column col) throws PrepareException {
        return switch (col.type()) {
            case INTEGER -> toInt(lit, col.name());
            case TEXT -> toText(lit, col);
        };
    }

    private static int toInt(Ast.Literal lit, String what) throws PrepareException {
        if (!(lit instanceof Ast.IntLiteral intLit)) {
            throw error(lit.column(), what + " must be an integer");
        }
        long v = intLit.value();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw error(lit.column(), what + " is out of range for a 32-bit integer");
        }
        return (int) v;
    }

    private static String toText(Ast.Literal lit, Column col) throws PrepareException {
        if (!(lit instanceof Ast.StringLiteral strLit)) {
            throw error(lit.column(), col.name() + " must be a string");
        }
        String value = strLit.value();
        if (value.getBytes(StandardCharsets.UTF_8).length > col.size()) {
            throw error(lit.column(), col.name() + " is too long (max " + col.size() + " bytes)");
        }
        return value;
    }

    private static PrepareException error(int column, String message) {
        return new PrepareException("Error at column " + column + ": " + message);
    }
}
