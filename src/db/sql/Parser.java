package db.sql;

import db.Database;

public final class Parser {

    private Parser() {}

    public static Statement prepare(String line, Database db) throws PrepareException {
        return Binder.bind(SqlParser.parse(Lexer.tokenize(line)), db, line);
    }
}
