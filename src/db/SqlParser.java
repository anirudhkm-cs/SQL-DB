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
        }else if(check(TokenType.CREATE)){            
            stmt = parseCreate();
        }else if(check(TokenType.DELETE)){            
            stmt = parseDelete();
        }else if(check(TokenType.UPDATE)){            
            stmt = parseUpdate();
        }else{
            throw error("expected INSERT, SELECT, CREATE, UPDATE or DELETE");
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

        return new Ast.Select(columns, table, parseOptionalWhere());   
    }

    private Ast.Where parseOptionalWhere() throws PrepareException {
        if (!match(TokenType.WHERE)) {
            return null;
        }
        Ast.Name column = parseName("column name");
        expect(TokenType.EQUALS, "'='");
        return new Ast.Where(column, parseValue());
    }

    private Ast.CreateTable parseCreate() throws PrepareException {
        expect(TokenType.CREATE, "CREATE");
        expect(TokenType.TABLE, "TABLE");
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
