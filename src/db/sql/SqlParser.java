package db.sql;

import java.util.ArrayList;
import java.util.List;

public final class SqlParser {

    private final List<Token> tokens;
    private int pos = 0;

    private SqlParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Ast.Stmt parse(List<Token> tokens) throws PrepareException {
        return new SqlParser(tokens).parseStatement();
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != TokenType.EOF) pos++;
        return t;
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type, String what) throws PrepareException {
        if (check(type)) return advance();
        throw error("expected " + what);
    }

    private PrepareException error(String message) {
        Token t = peek();
        return new PrepareException("Syntax error at column " + t.column() + ": "
                + message + ", found " + t.describe());
    }


    private Ast.Stmt parseStatement() throws PrepareException {
        Ast.Stmt stmt;
        if(check(TokenType.INSERT)){
            stmt = parseInsert();
        }else if(check(TokenType.SELECT)){
            stmt = parseSelect();
        }else if(check(TokenType.CREATE)){            
            stmt = parseCreate();
        }else if(check(TokenType.DELETE)){            
            stmt = parseDelete();
        }else if(check(TokenType.UPDATE)){            
            stmt = parseUpdate();
        }else if(match(TokenType.BEGIN)){             
            stmt = new Ast.Begin();
        }else if(match(TokenType.COMMIT)){            
            stmt = new Ast.Commit();
        }else if(match(TokenType.ROLLBACK)){          
            stmt = new Ast.Rollback();
        }else{
            throw error("expected INSERT, SELECT, CREATE, UPDATE, DELETE, BEGIN, COMMIT or ROLLBACK");
        }
        match(TokenType.SEMICOLON);
        expect(TokenType.EOF, "end of input");
        return stmt;
    }

    private Ast.Insert parseInsert() throws PrepareException {
        expect(TokenType.INSERT, "INSERT");
        expect(TokenType.INTO, "INTO");
        Ast.Name table = parseName("table name");
        expect(TokenType.VALUES, "VALUES");
        expect(TokenType.LPAREN, "'('");
        List<Ast.Literal> values = new ArrayList<>();
        do{
            values.add(parseValue());
        }while(match(TokenType.COMMA));

        expect(TokenType.RPAREN, "')' or ','");
        return new Ast.Insert(table, values);
    }

    private Ast.Select parseSelect() throws PrepareException {
        expect(TokenType.SELECT, "SELECT");

        List<Ast.ColumnRef> columns = new ArrayList<>();
        boolean count = false;
        if (match(TokenType.COUNT)) {
            expect(TokenType.LPAREN, "'(' after COUNT");
            expect(TokenType.STAR, "'*' inside COUNT(...)");
            expect(TokenType.RPAREN, "')'");
            count = true;
        } else if (!match(TokenType.STAR)) {
            do {
                columns.add(parseColumnRef("column name or '*'"));
            } while (match(TokenType.COMMA));
        }

        expect(TokenType.FROM, "FROM");
        Ast.Name table = parseName("table name");

        Ast.Join join = null;
        if (match(TokenType.JOIN)) {
            Ast.Name joinTable = parseName("table name after JOIN");
            expect(TokenType.ON, "ON");
            Ast.ColumnRef left = parseColumnRef("column name after ON");
            expect(TokenType.EQUALS, "'='");
            Ast.ColumnRef right = parseColumnRef("column name after '='");
            join = new Ast.Join(joinTable, left, right);
        }

        Ast.Expr where = parseOptionalWhere();

        Ast.OrderBy orderBy = null;
        if (match(TokenType.ORDER)) {
            expect(TokenType.BY, "BY after ORDER");
            Ast.ColumnRef ref = parseColumnRef("column name after ORDER BY");
            boolean descending = false;
            if (match(TokenType.DESC)) descending = true;
            else match(TokenType.ASC);
            orderBy = new Ast.OrderBy(ref, descending);
        }

        Ast.IntLiteral limit = null;
        if (match(TokenType.LIMIT)) {
            if (!check(TokenType.INTEGER)) throw error("expected a row count after LIMIT");
            limit = (Ast.IntLiteral) parseValue();
        }

        return new Ast.Select(columns, count, table, join, where, orderBy, limit);
    }

    private Ast.ColumnRef parseColumnRef(String what) throws PrepareException {
        Ast.Name first = parseName(what);
        if(match(TokenType.DOT)){
            Ast.Name column = parseName("column name after '.'");
            return  new Ast.ColumnRef(first, column);
        }
        return new Ast.ColumnRef(null, first);
    }

    private Ast.Expr parseOptionalWhere() throws PrepareException {
        if (!match(TokenType.WHERE)) {
            return null;
        }
        return parseOr();
    }

    private Ast.Expr parseOr() throws PrepareException {
        List<Ast.Expr> parts = new ArrayList<>();
        parts.add(parseAnd());
        while (match(TokenType.OR)) {
            parts.add(parseAnd());
        }
        return parts.size() == 1 ? parts.get(0) : new Ast.Or(parts);
    }

    private Ast.Expr parseAnd() throws PrepareException {
        List<Ast.Expr> parts = new ArrayList<>();
        parts.add(parsePrimary());
        while (match(TokenType.AND)) {
            parts.add(parsePrimary());
        }
        return parts.size() == 1 ? parts.get(0) : new Ast.And(parts);
    }

    private Ast.Expr parsePrimary() throws PrepareException {
        if (match(TokenType.LPAREN)) {
        Ast.Expr inner = parseOr();
            expect(TokenType.RPAREN, "')'");
            return inner;
        }

        Ast.ColumnRef column = parseColumnRef("column name or '('");     // 10b: may be t.col

        Ast.CompareOp op = switch (peek().type()) {
            case EQUALS         -> Ast.CompareOp.EQ;
            case NOT_EQUALS     -> Ast.CompareOp.NE;
            case LESS           -> Ast.CompareOp.LT;
            case LESS_EQUALS    -> Ast.CompareOp.LE;
            case GREATER        -> Ast.CompareOp.GT;
            case GREATER_EQUALS -> Ast.CompareOp.GE;
            default -> throw error("expected a comparison (=, !=, <, <=, >, >=)");
        };
        advance();

        Ast.Literal value = parseValue();
        return new Ast.Compare(column, op, value);
    }

    private Ast.Stmt parseCreate() throws PrepareException {
        expect(TokenType.CREATE, "CREATE");
        if (match(TokenType.INDEX)) {                                  
            Ast.Name index = parseName("index name");
            expect(TokenType.ON, "ON");
            Ast.Name table = parseName("table name");
            expect(TokenType.LPAREN, "'('");
            Ast.Name column = parseName("column name");
            expect(TokenType.RPAREN, "')'");
            return new Ast.CreateIndex(index, table, column);
        }
        expect(TokenType.TABLE, "TABLE or INDEX");
        Ast.Name table = parseName("table name");
        expect(TokenType.LPAREN, "'('");
        List<Ast.ColumnDef> columns = new ArrayList<>();
        do {
            columns.add(parseColumnDef());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN, "')' or ','");
        return new Ast.CreateTable(table, columns);
    }

    private Ast.ColumnDef parseColumnDef() throws PrepareException {
        Ast.Name name = parseName("column name");
        Ast.Name type = parseName("type name");
        Ast.IntLiteral size = null;
        if(match(TokenType.LPAREN)){
            if (!check(TokenType.INTEGER)){
                throw error("expected column size");
            }
            size = (Ast.IntLiteral) parseValue();
            expect(TokenType.RPAREN, "')'");
        }
        return new Ast.ColumnDef(name, type, size);
    }

    private Ast.Delete parseDelete() throws PrepareException {
        expect(TokenType.DELETE, "DELETE");
        expect(TokenType.FROM, "FROM");
        Ast.Name table = parseName("table name");
        return new Ast.Delete(table, parseOptionalWhere());
    }

    private Ast.Update parseUpdate() throws PrepareException {
        expect(TokenType.UPDATE, "UPDATE");
        Ast.Name table = parseName("table name");
        expect(TokenType.SET, "SET");
        List<Ast.Assignment> assignments = new ArrayList<>();
        do {
            Ast.Name column = parseName("column name");
            expect(TokenType.EQUALS, "'='");
            assignments.add(new Ast.Assignment(column, parseValue()));
        } while (match(TokenType.COMMA));
        return new Ast.Update(table, assignments, parseOptionalWhere());
    }

    private Ast.Literal parseValue() throws PrepareException {
        Token t = peek();
        if (t.type() == TokenType.INTEGER) {
            try {
                long value = Long.parseLong(t.text());
                advance();
                return new Ast.IntLiteral(value, t.column());
            } catch (NumberFormatException e) {
                throw new PrepareException("Syntax error at column " + t.column()
                        + ": integer out of range, found " + t.describe());
            }
        }
        if (t.type() == TokenType.STRING) {
            advance();
            return new Ast.StringLiteral(t.text(), t.column());
        }
        throw error("expected a value");
    }

    private Ast.Name parseName(String name) throws PrepareException {
        Token t = expect(TokenType.IDENTIFIER, name);
        return new Ast.Name(t.text(), t.column());
    }
}
