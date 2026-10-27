package db;

public enum TokenType {
    INSERT, INTO, VALUES, SELECT, FROM, WHERE,
    CREATE, TABLE,                       
    DELETE, UPDATE, SET,                 
    BEGIN, COMMIT, ROLLBACK,
    INDEX, ON,                          
    AND, OR,                                 
    JOIN, ORDER, BY, ASC, DESC, LIMIT, COUNT,   

    IDENTIFIER,     
                    
    INTEGER,        
    STRING,

    LPAREN, RPAREN, COMMA, STAR, EQUALS, SEMICOLON,
    DOT,                               
    NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS,   

    EOF
}
