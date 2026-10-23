package db;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Binder {

    private Binder() {}

    public static Statement bind(Ast.Stmt stmt) throws PrepareException {
        if (stmt instanceof Ast.Insert insert) return bindInsert(insert);
        if (stmt instanceof Ast.Select select) return bindSelect(select);
        throw new IllegalStateException("unknown statement " + stmt);
    }

    private static Statement bindInsert(Ast.Insert insert) throws PrepareException {
        checkTable(insert.table());
        List<Ast.Literal> vals = insert.values();
        if(vals.size() != 3){
            int col = vals.size() > 3 ? vals.get(3).column() : vals.get(vals.size()-1).column();
            throw error(col, "expected 3 values, got " + vals.size());
        }
        int id = toInt(vals.get(0), "id");
        if(id<=0){
            throw error(vals.get(0).column(), "id must be positive");
        }
        String username = toText(vals.get(1), UsersColumn.USERNAME);
        String email = toText(vals.get(2), UsersColumn.EMAIL);
        return new Statement(StatementType.INSERT, new Row(id, username, email), null, null);
    }

    private static Statement bindSelect(Ast.Select select) throws PrepareException {
        checkTable(select.table());

        List<UsersColumn> projection = new ArrayList<>();
        if(select.columns().isEmpty()){
            projection.addAll(List.of(UsersColumn.values()));
        }else{
            for(Ast.Name name : select.columns()){
                projection.add(column(name));
            }
        }
        Statement.Filter filter = null;
        if(select.where()!=null){
            UsersColumn col = column(select.where().column());
            filter = new Statement.Filter(col, convert(select.where().value(), col));
        }

        return new Statement(StatementType.SELECT, null, projection, filter);
    }

    private static void checkTable(Ast.Name table) throws PrepareException {
        if(!table.text().equalsIgnoreCase(UsersColumn.TABLE_NAME)){
            throw error(table.column(), "no such table '" + table.text() + "'");
        }
    }

    private static UsersColumn column(Ast.Name name) throws PrepareException {
        UsersColumn col = UsersColumn.fromName(name.text());
        if(col == null){
            throw error(name.column(), "no such column '" + name.text() + "'");
        }
        return col;
    }

    private static Object convert(Ast.Literal lit, UsersColumn col) throws PrepareException {
        return switch(col.type){
            case INTEGER -> toInt(lit, col.sqlName);
            case TEXT -> toText(lit, col);
        };
    }

    private static int toInt(Ast.Literal lit, String what) throws PrepareException {
        if(!(lit instanceof Ast.IntLiteral intLit)){
            throw error(lit.column(), what + " must be an integer");
        }
        long v = intLit.value();
        if(v < Integer.MIN_VALUE || v > Integer.MAX_VALUE){
            throw error(lit.column(), what + " is out of range for a 32-bit integer");
        }
        return (int) v;
    }

    private static String toText(Ast.Literal lit, UsersColumn col) throws PrepareException {
        if(!(lit instanceof Ast.StringLiteral strLit)){
            throw error(lit.column(), col.sqlName + " must be a string");
        }
        String value = strLit.value();
        if(value.getBytes(StandardCharsets.UTF_8).length > col.maxBytes){
            throw error(lit.column(), col.sqlName + " is too long (max " + col.maxBytes + " bytes)");
        }
        return value;
    }

    private static PrepareException error(int column, String message) {
        return new PrepareException("Error at column " + column + ": " + message);
    }
}
