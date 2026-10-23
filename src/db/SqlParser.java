package db;

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
        }else{
            throw error("expected INSERT or SELECT");
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
        List<Ast.Name> columns = new ArrayList<>();
        expect(TokenType.SELECT, "SELECT");
        if(!match(TokenType.STAR)){
            do{
                columns.add(parseName("column name or '*'"));
            }while(match((TokenType.COMMA)));
        }
        expect(TokenType.FROM, "FROM");
        Ast.Name table = parseName("table name");

        Ast.Where where = null;
        if (match(TokenType.WHERE)) {
            Ast.Name column = parseName("column name");
            expect(TokenType.EQUALS, "'='");
            where = new Ast.Where(column, parseValue());
        }
        return new Ast.Select(columns, table, where);
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
