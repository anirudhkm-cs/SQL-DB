package db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

public class Main {

    private static void printPrompt() {
        System.out.print("db > ");
    }

    private static void exit(Table table) throws IOException{
        table.close();
        System.exit(0);
    }

    private static String readInput(BufferedReader in, Table table) throws IOException {
        String line = in.readLine();
        if(line == null){
            System.out.println();
            exit(table);
        }
        return line.trim();
    }

    private static boolean doMetaCommand(String line, Table table) throws IOException {
        if(line.equals(".exit")){
            exit(table);
            return true;
        }
        if(line.equals(".btree")){
            table.printTree();
            return true;
        }
        if(line.equals(".check")){
            String err = table.check();
            System.out.println(err == null ? "OK" : err);
            return true;
        }
        return false;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: java -cp out db.Main <database-file>");
            System.exit(1);
        }
        Path dbPath = Path.of(args[0]);

        Table table;
        try {
            table = new Table(dbPath);
        } catch (IOException e) {
            System.err.println("Error opening " + dbPath + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            printPrompt();

            String line = readInput(in, table);

            if(line.isEmpty() || line.isBlank()){
                continue;
            }
            
            if (line.startsWith(".")) {
                if(!doMetaCommand(line, table)){
                    System.out.println("Unrecognized command: " + line);
                }
                continue;
            }

            Statement statement;

            try {
                statement = Parser.prepare(line);
            } catch (PrepareException e) {
                System.out.println(e.getMessage());
                continue;
            }

            try {
                Executor.execute(statement, table);
            } catch (ExecuteException e) {
                System.out.println(e.getMessage());
                continue;
            }
            System.out.println("Executed");
        }
    }
}