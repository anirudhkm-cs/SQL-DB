package db;

import java.nio.charset.StandardCharsets;

public final class Parser {

    private Parser() {}

    private static Statement prepareInsert(String line) throws PrepareException {
        String[] tokens = line.trim().split("\\s+");
        if(tokens.length != 4){
            throw new PrepareException("Syntax Error: Expected 4 arguements, Received: " + tokens.length);
        }
        try {
            Integer id = Integer.parseInt(tokens[1]);
            if(id <= 0){
                throw new PrepareException("Id must be a positive integer");
            }
            if(tokens[2].getBytes(StandardCharsets.UTF_8).length>Row.USERNAME_MAX){
                throw new PrepareException("UserName exceeds max length of: " + Row.USERNAME_MAX + " bytes");
            }
            if(tokens[3].getBytes(StandardCharsets.UTF_8).length>Row.EMAIL_MAX){
                throw new PrepareException("Email exceeds max length of: " + Row.EMAIL_MAX + " bytes");
            }
            return  new Statement(StatementType.INSERT, new Row(id, tokens[2], tokens[3]));
        } catch (NumberFormatException e) {
            throw new PrepareException("Id must be an Integer");
        }
    }

    public static Statement prepare(String line) throws PrepareException {
        String[] tokens = line.trim().split("\\s+");
        String operation = tokens[0];
        if(operation.equals("insert")){
            return prepareInsert(line);

        }
        if(operation.equals("select")){
            return new Statement(StatementType.SELECT, null);
        }
        throw new PrepareException("unrecognised keyword at start of: " + line);
    }
}