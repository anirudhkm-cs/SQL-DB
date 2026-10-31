package db.sql;


public record Token(TokenType type, String text, int column) {

    public String describe() {
        if (type == TokenType.EOF) return "end of input";
        return "'" + text + "'";
    }
}
