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

    public record Delete(Name table, Where where) implements Stmt {}

    public record Assignment(Name column, Literal value) {}

    public record Update(Name table, List<Assignment> assignments, Where where) implements Stmt {}

    // 9: CREATE INDEX <index> ON <table> (<column>)
    public record CreateIndex(Name index, Name table, Name column) implements Stmt {}

    public record Begin() implements Stmt {}

    public record Commit() implements Stmt {}

    public record Rollback() implements Stmt {}
}
