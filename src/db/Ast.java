package db;

import java.util.List;

public final class Ast {

    private Ast() {}

    public interface Stmt {}

    public interface Literal {
        int column();
    }

    public record IntLiteral(long value, int column) implements Literal {}

    public record StringLiteral(String value, int column) implements Literal {}

    public record Name(String text, int column) {}

    public record Insert(Name table, List<Literal> values) implements Stmt {}

    public record Where(Name column, Literal value) {}

    public record Select(List<Name> columns, Name table, Where where) implements Stmt {}

    public record ColumnDef(Name name, Name type, IntLiteral size) {}

    public record CreateTable(Name table, List<ColumnDef> columns) implements Stmt {}
}
