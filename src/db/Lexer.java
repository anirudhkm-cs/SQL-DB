package db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Lexer {

    private Lexer() {}

    private static final Map<String, TokenType> KEYWORDS = Map.of(
            "insert", TokenType.INSERT,
            "into", TokenType.INTO,
            "values", TokenType.VALUES,
            "select", TokenType.SELECT,
            "from", TokenType.FROM,
            "where", TokenType.WHERE,
            "create", TokenType.CREATE,     
            "table", TokenType.TABLE);      

    private static final Map<Character, TokenType> SYMBOLS = Map.of(
            '(', TokenType.LPAREN,
            ')', TokenType.RPAREN,
            ',', TokenType.COMMA,
            '*', TokenType.STAR,
            '=', TokenType.EQUALS,
            ';', TokenType.SEMICOLON);

    public static List<Token> tokenize(String input) throws PrepareException {
        List<Token> tokens = new ArrayList<>();
        int i = 0;

        while (i < input.length()) {
            char ch = input.charAt(i);
            int column = i + 1;

            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }

            TokenType symbol = SYMBOLS.get(ch);
            if (symbol != null) {
                tokens.add(new Token(symbol, String.valueOf(ch), column));
                i++;
                continue;
            }

            if (Character.isDigit(ch)) {
                int start = i;
                while (i < input.length() && Character.isDigit(input.charAt(i))) i++;
                if (i < input.length() && isWordChar(input.charAt(i))) {
                    throw new PrepareException("Syntax error at column " + column
                            + ": invalid number '" + input.substring(start, i + 1) + "...'");
                }
                tokens.add(new Token(TokenType.INTEGER, input.substring(start, i), column));
                continue;
            }

            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < input.length() && isWordChar(input.charAt(i))) i++;
                String word = input.substring(start, i);
                TokenType keyword = KEYWORDS.get(word.toLowerCase());
                tokens.add(new Token(keyword != null ? keyword : TokenType.IDENTIFIER, word, column));
                continue;
            }

            if (ch == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < input.length()) {
                    char c = input.charAt(i);
                    if (c == '\'') {
                        if (i + 1 < input.length() && input.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                        } else {
                            i++; 
                            closed = true;
                            break;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                }
                if (!closed) {
                    throw new PrepareException("Syntax error at column " + column + ": unterminated string");
                }
                tokens.add(new Token(TokenType.STRING, sb.toString(), column));
                continue;
            }

            throw new PrepareException("Syntax error at column " + column + ": unexpected character '" + ch + "'");
        }

        tokens.add(new Token(TokenType.EOF, "", input.length() + 1));
        return tokens;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
