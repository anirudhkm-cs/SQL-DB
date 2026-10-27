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
        if (stmt instanceof Ast.Delete delete) return bindDelete(delete, db);          
        if (stmt instanceof Ast.Update update) return bindUpdate(update, db);          
        if (stmt instanceof Ast.CreateIndex ci) return bindCreateIndex(ci, db, sql);              
        if (stmt instanceof Ast.Begin) return Statement.transaction(StatementType.BEGIN);        
        if (stmt instanceof Ast.Commit) return Statement.transaction(StatementType.COMMIT);      
        if (stmt instanceof Ast.Rollback) return Statement.transaction(StatementType.ROLLBACK);  
        throw new IllegalStateException("unknown statement " + stmt);
    }


    private static Statement bindCreate(Ast.CreateTable create, Database db, String sql)
            throws PrepareException {
        Schema schema = schemaFrom(create);
        String name = create.table().text();
        if(db.nameInUse(name)){                                                    
            throw error(create.table().column(), "'" + name + "' already exists");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > Database.MAX_TABLE_NAME_BYTES){
            throw error(create.table().column(), "table name too long (max " + Database.MAX_TABLE_NAME_BYTES + " bytes)");
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > Database.MAX_SQL_BYTES){
            throw error(1, "statement too long (max " + Database.MAX_SQL_BYTES + " bytes)");
        }
        return Statement.createTable(schema, sql);
    }

    private static Statement bindCreateIndex(Ast.CreateIndex ci, Database db, String sql) throws PrepareException {
        String name = ci.index().text();
        if (db.nameInUse(name)) {
            throw error(ci.index().column(), "'" + name + "' already exists");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > Database.MAX_TABLE_NAME_BYTES) {
            throw error(ci.index().column(), "index name too long (max " + Database.MAX_TABLE_NAME_BYTES + " bytes)");
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > Database.MAX_SQL_BYTES) {
            throw error(1, "statement too long (max " + Database.MAX_SQL_BYTES + " bytes)");
        }
        Schema schema = schemaOf(ci.table(), db);
        int col = columnIndex(ci.column(), schema);
        if (col == 0) {
            throw error(ci.column().column(), "'" + schema.column(0).name()
                    + "' is the table's key - the table itself is already that index");
        }
        Index existing = db.indexOn(ci.table().text(), col);
        if (existing != null) {
            throw error(ci.column().column(), "column '" + schema.column(col).name()
                    + "' already has an index: " + existing.name);
        }
        return Statement.createIndex(name, ci.table().text(), col, sql);
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

    static final class Scope {
        final List<String> names = new ArrayList<>();
        final List<Schema> schemas = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        int width = 0;

        void add(String name, Schema schema) {
            names.add(name);
            schemas.add(schema);
            offsets.add(width);
            width += schema.columnCount();
        }

        Column column(int combined) {
            for (int t = schemas.size() - 1; t >= 0; t--) {
                if (combined >= offsets.get(t)) return schemas.get(t).column(combined - offsets.get(t));
            }
            throw new IllegalArgumentException("column " + combined);
        }

        int resolve(Ast.ColumnRef ref) throws PrepareException {
            String name = ref.column().text();

            if(ref.table() != null){
                for(int t=0; t<names.size(); t++){
                    if(names.get(t).equalsIgnoreCase(ref.table().text())){
                        int i = schemas.get(t).indexOf(name);
                        if(i<0){
                            throw error(ref.column().column(), "no such column '" + ref + "'");
                        }
                        return offsets.get(t) + i;
                    }
                }
                throw error(ref.table().column(), "no table '" + ref.table().text() + "' in this statement");
            }

            int found = -1, foundTable = -1;
            for(int t=0; t<names.size(); t++){
                int i = schemas.get(t).indexOf(name);
                if (i < 0) continue;
                if (found >= 0) {
                    throw error(ref.column().column(), "ambiguous column '" + name + "' (" + names.get(foundTable) + "." + name + " or " + names.get(t) + "." + name + "?)");
                }
                found = offsets.get(t) + i;
                foundTable = t;
            }
            if (found < 0){
                throw error(ref.column().column(), "no such column '" + name + "'");
            } 
            return found;
            }
    }

    private static Scope scopeOf(Ast.Name table, Database db) throws PrepareException {
        Scope scope = new Scope();
        scope.add(table.text(), schemaOf(table, db));
        return scope;
    }

    private static Statement bindSelect(Ast.Select select, Database db) throws PrepareException {
        Scope scope = scopeOf(select.table(), db);
        int leftWidth = scope.width;

        Statement.JoinSpec join = null;
        if (select.join() != null) {
            Ast.Join j = select.join();
            if (j.table().text().equalsIgnoreCase(select.table().text())) {
                throw error(j.table().column(), "joining a table with itself needs table aliases, which aren't supported");
            }
            scope.add(j.table().text(), schemaOf(j.table(), db));
            int a = scope.resolve(j.left());
            int b = scope.resolve(j.right());
            if ((a < leftWidth) == (b < leftWidth)) {
                throw error(j.left().column().column(), "ON must compare a column of " + select.table().text()
                        + " with a column of " + j.table().text());
            }
            int left = Math.min(a, b), right = Math.max(a, b);
            if (scope.column(left).type() != scope.column(right).type()) {
                throw error(j.left().column().column(), "ON compares " + scope.column(left).type() + " with "
                        + scope.column(right).type());
            }
            join = new Statement.JoinSpec(j.table().text(), left, right - leftWidth);
        }

        List<Integer> projection = new ArrayList<>();
        if (!select.count()) {
            if (select.columns().isEmpty()) {
                for (int i = 0; i < scope.width; i++) projection.add(i);
            } else {
                for (Ast.ColumnRef ref : select.columns()) projection.add(scope.resolve(ref));
            }
        }

        Predicate filter = bindWhere(select.where(), scope);

        Statement.OrderSpec order = null;
        if (select.orderBy() != null) {
            order = new Statement.OrderSpec(scope.resolve(select.orderBy().column()), select.orderBy().descending());
        }

        int limit = -1;
        if (select.limit() != null) {
            long n = select.limit().value();
            if (n < 0 || n > Integer.MAX_VALUE) {
                throw error(select.limit().column(), "LIMIT must be between 0 and " + Integer.MAX_VALUE);
            }
            limit = (int) n;
        }
        return Statement.select(select.table().text(), projection, filter,
                new Statement.SelectSpec(join, order, limit, select.count()));
    }

       private static Predicate bindWhere(Ast.Expr where, Scope scope) throws PrepareException {
        if (where == null) {
            return null;
        }
        if (where instanceof Ast.Compare c) {
            int idx = scope.resolve(c.column());
            return new Predicate.Compare(idx, c.op(), convert(c.value(), scope.column(idx)));
        }
        List<Ast.Expr> parts = where instanceof Ast.And and ? and.parts() : ((Ast.Or) where).parts();
        List<Predicate> bound = new ArrayList<>();
        for (Ast.Expr part : parts) {
            bound.add(bindWhere(part, scope));
        }
        return where instanceof Ast.And ? new Predicate.And(bound) : new Predicate.Or(bound);
    }

    private static Statement bindDelete(Ast.Delete delete, Database db) throws PrepareException {
        Predicate filter = bindWhere(delete.where(), scopeOf(delete.table(), db));
        return Statement.delete(delete.table().text(), filter);
    }

    private static Statement bindUpdate(Ast.Update update, Database db) throws PrepareException {
        Schema schema = schemaOf(update.table(), db);
        List<Statement.Assignment> assignments = new ArrayList<>();
        for(Ast.Assignment a : update.assignments()){
            int idx = columnIndex(a.column(), schema);
            if(idx == 0){
                throw error(a.column().column(),
                    "cannot update the key column '" + schema.column(0).name() + "'");
            }
            for(Statement.Assignment existing: assignments){
                if(existing.column() == idx){
                    throw error(a.column().column(),
                        "column '" + schema.column(idx).name() + "' assigned more than once");
                }
            }
            assignments.add(new Statement.Assignment(idx, convert(a.value(), schema.column(idx))));
        }
        Predicate filter = bindWhere(update.where(), scopeOf(update.table(), db));
        return Statement.update(update.table().text(), assignments, filter);
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
