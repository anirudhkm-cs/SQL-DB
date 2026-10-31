package db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

public class Main {

    private static void printPrompt(Database db) {
        System.out.print(db.inTransaction() ? "db*> " : "db > ");
    }

    private static void exit(Database db) throws IOException {
        if (db.inTransaction()) {
            System.out.println("Open transaction discarded (it was never committed)");
        }
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

        Session session = new Session(db);
        Output out = new ConsoleOutput();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            printPrompt(db);

            String line = readInput(in, db);

            if (line.isBlank()) {
                continue;
            }
            if (line.equals(".exit") || line.startsWith(".exit ")) {
                exit(db);
            }

            Session.Result result = session.handle(line, out);
            if (result.kind() == Session.Kind.ERROR) {
                System.out.println(result.error());
            } else if (result.kind() == Session.Kind.STATEMENT) {
                System.out.println("Executed");
            }
        }
    }
}
