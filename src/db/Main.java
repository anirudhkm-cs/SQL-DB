package db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;

public class Main {

    private static void printPrompt() {
        System.out.print("db > ");
    }

    private static void exit(Database db) throws IOException {
        db.close();
        System.exit(0);
    }

    private static String readInput(BufferedReader in, Database db) throws IOException {
        String line = in.readLine();
        if (line == null) {
            System.out.println();
            exit(db);
        }
        return line.trim();
    }

    private static boolean doMetaCommand(String line, Database db) throws IOException {
        String[] parts = line.split("\\s+");
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : null;

        if (cmd.equals(".exit")) {
            exit(db);
            return true;
        }
        if (cmd.equals(".tables")) {
            List<String> names = db.tableNames();
            if (names.isEmpty()) {
                System.out.println("(no tables)");
            }
            for (String name : names) {
                System.out.println(name);
            }
            return true;
        }
        if (cmd.equals(".schema")) {
            if (arg == null) {
                System.out.println("Usage: .schema <table>");
                return true;
            }
            String sql = db.sqlFor(arg);
            if (sql == null) {
                System.out.println("No such table: " + arg);
                return true;
            }
            System.out.println(sql);
            return true;
        }
        if (cmd.equals(".btree") || cmd.equals(".check")) {
            if (arg == null) {
                if (cmd.equals(".btree")) {
                    System.out.println("Usage: .btree <table>");
                    return true;
                }
  
                boolean allOk = checkTree(db.catalog());
                for (String name : db.tableNames()) {
                    allOk &= checkTree(db.table(name));
                }
                if (allOk) {
                    String err = db.checkPages();
                    System.out.println("pages: " + (err == null ? "OK" : err));
                } else {
                    System.out.println("pages: skipped (fix the tree errors first)");
                }
                return true;
            }
            BTree tree = db.table(arg);
            if (tree == null) {
                System.out.println("No such table: " + arg);
                return true;
            }
            if (cmd.equals(".btree")) {
                tree.printTree();
            } else {
                checkTree(tree);
            }
            return true;
        }


        return false;
    }

    private static boolean checkTree(BTree tree) throws IOException {
        String err = tree.check();
        System.out.println(tree.name() + ": " + (err == null ? "OK" : err));
        return err == null;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: java -cp out db.Main <database-file>");
            System.exit(1);
        }
        Path dbPath = Path.of(args[0]);

        Database db;
        try {
            db = new Database(dbPath);
        } catch (IOException e) {
            System.err.println("Error opening " + dbPath + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            printPrompt();

            String line = readInput(in, db);

            if (line.isBlank()) {
                continue;
            }

            if (line.startsWith(".")) {
                if (!doMetaCommand(line, db)) {
                    System.out.println("Unrecognized command: " + line);
                }
                continue;
            }

            Statement statement;
            try {
                statement = Parser.prepare(line, db);
            } catch (PrepareException e) {
                System.out.println(e.getMessage());
                continue;
            }

            try {
                Executor.execute(statement, db);
            } catch (ExecuteException e) {
                System.out.println(e.getMessage());
                continue;
            }
            System.out.println("Executed");
        }
    }
}
