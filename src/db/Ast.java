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

    public interface Expr {}

    public enum CompareOp { EQ, NE, LT, LE, GT, GE }

    public record ColumnRef(Name table, Name column) {
        @Override
        public String toString() {
            return table == null ? column.text() : table.text() + "." + column.text();
        }
    }

    public record Compare(ColumnRef column, CompareOp op, Literal value) implements Expr {}

    public record And(List<Expr> parts) implements Expr {}

    public record Or(List<Expr> parts) implements Expr {}

    public record Select(List<ColumnRef> columns, boolean count, Name table, Join join, Expr where, OrderBy orderBy, IntLiteral limit) implements Stmt {}

    public record Join(Name table, ColumnRef left, ColumnRef right) {}

    public record OrderBy(ColumnRef column, boolean descending) {}

    public record ColumnDef(Name name, Name type, IntLiteral size) {}

    public record CreateTable(Name table, List<ColumnDef> columns) implements Stmt {}

    public record Delete(Name table, Expr where) implements Stmt {}

    public record Assignment(Name column, Literal value) {}

    public record Update(Name table, List<Assignment> assignments, Expr where) implements Stmt {}

    public record CreateIndex(Name index, Name table, Name column) implements Stmt {}

    public record Begin() implements Stmt {}

    public record Commit() implements Stmt {}

    public record Rollback() implements Stmt {}
}
