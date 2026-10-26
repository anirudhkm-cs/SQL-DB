package db;

public enum TokenType {
    INSERT, INTO, VALUES, SELECT, FROM, WHERE,
    CREATE, TABLE,                       
    DELETE, UPDATE, SET,                 

    IDENTIFIER,     
                    
    INTEGER,        
    STRING,

    LPAREN, RPAREN, COMMA, STAR, EQUALS, SEMICOLON,

    EOF
}
