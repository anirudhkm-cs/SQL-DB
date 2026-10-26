package db;

public enum TokenType {
    INSERT, INTO, VALUES, SELECT, FROM, WHERE,
    CREATE, TABLE,                       
    DELETE, UPDATE, SET,                 
    BEGIN, COMMIT, ROLLBACK,             

    IDENTIFIER,     
                    
    INTEGER,        
    STRING,

    LPAREN, RPAREN, COMMA, STAR, EQUALS, SEMICOLON,

    EOF
}
