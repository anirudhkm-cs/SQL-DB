package db;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

final class Session {

    enum Kind { STATEMENT, META, ERROR }

    record Result(Kind kind, String error) {
        static final Result STATEMENT = new Result(Kind.STATEMENT, null);
        static final Result META = new Result(Kind.META, null);
        static Result error(String message) { return new Result(Kind.ERROR, message); }
    }

    static final long DEFAULT_LOCK_TIMEOUT_MS = 5000;

    private final Database db;
    private final long lockTimeoutMs;
 
    private boolean holdsWriteLock = false;

    Session(Database db) {
        this(db, DEFAULT_LOCK_TIMEOUT_MS);
    }

    Session(Database db, long lockTimeoutMs) {
        this.db = db;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    boolean inTransaction() {
        return holdsWriteLock;
    }

    Result handle(String line, Output out) throws IOException {
        boolean write;
        try {
            write = needsWriteLock(line);
        } catch (PrepareException e) {
            return Result.error(e.getMessage());
        }
        if(holdsWriteLock){
            Result r = run(line, out);
            if(!db.inTransaction()){
                holdsWriteLock = false;
                db.lock.writeLock().unlock();
            }
            return r;
        }

        Lock lock = write ? db.lock.writeLock() : db.lock.readLock();
        if(!acquire(lock)){
            return Result.error("database is busy: another client's transaction is in progress (waited " + lockTimeoutMs + " ms)");
        }
        try{
            return run(line, out);
        }finally{
            if(write && db.inTransaction()){
                holdsWriteLock = true;
            }else{
                lock.unlock();
            }
        }
    }

    static boolean needsWriteLock(String line) throws PrepareException {
        if(line.startsWith(".")){
            return false;
        }
        List<Token> tokens = Lexer.tokenize(line);
        return tokens.isEmpty() || tokens.get(0).type() != TokenType.SELECT;
    }

    private boolean acquire(Lock lock) {
        try {
            return lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    boolean close() {
        if(!holdsWriteLock){
            return false;
        }
        try{
            db.rollback();
        }catch(ExecuteException e){
            System.err.println("rollback on close failed: " + e.getMessage());
        }finally{
            holdsWriteLock = false;
            db.lock.writeLock().unlock();
        }
        return true;
    }

    private Result run(String line, Output out) throws IOException {
        if (line.startsWith(".")) {
            return doMetaCommand(line, out) ? Result.META : Result.error("Unrecognized command: " + line);
        }
        Statement statement;
        try {
            statement = Parser.prepare(line, db);
        } catch (PrepareException e) {
            return Result.error(e.getMessage());
        }
        try {
            Executor.execute(statement, db, out);
        } catch (ExecuteException e) {
            return Result.error(e.getMessage());
        }
        return Result.STATEMENT;
    }

    private boolean doMetaCommand(String line, Output out) throws IOException {
        String[] parts = line.split("\\s+");
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1] : null;

        if (cmd.equals(".crash")) {                                  
            if (arg == null) {
                out.message("Usage: .crash <point>   points: " + CrashPoints.POINTS);
                return true;
            }
            try {
                CrashPoints.arm(arg);
                out.message("armed: the next commit will crash at '" + arg + "'");
            } catch (IllegalArgumentException e) {
                out.message(e.getMessage());
            }
            return true;
        }
        if (cmd.equals(".plan")) {                                    
            out.message(Executor.plan());
            return true;
        }
        if (cmd.equals(".indexes")) {                                 
            List<Index> all = db.allIndexes();
            if (all.isEmpty()) {
                out.message("(no indexes)");
            }
            for (Index idx : all) {
                if (arg == null || idx.tableName.equalsIgnoreCase(arg)) {
                    out.message(idx.name + " on " + idx.tableName + "("
                            + db.table(idx.tableName).schema().column(idx.column).name() + ")");
                }
            }
            return true;
        }
        if (cmd.equals(".pages")) {                                   
            out.message(db.pageStats());
            return true;
        }
        if (cmd.equals(".tables")) {
            List<String> names = db.tableNames();
            if (names.isEmpty()) {
                out.message("(no tables)");
            }
            for (String name : names) {
                out.message(name);
            }
            return true;
        }
        if (cmd.equals(".schema")) {
            if (arg == null) {
                out.message("Usage: .schema <table>");
                return true;
            }
            String sql = db.sqlFor(arg);
            if (sql == null) {
                out.message("No such table: " + arg);
                return true;
            }
            out.message(sql);
            return true;
        }
        if (cmd.equals(".btree") || cmd.equals(".check")) {
            if (arg == null) {
                if (cmd.equals(".btree")) {
                    out.message("Usage: .btree <table>");
                    return true;
                }
  
                boolean allOk = checkTree(db.catalog(), out);
                for (String name : db.tableNames()) {
                    allOk &= checkTree(db.table(name), out);
                }
                for (Index idx : db.allIndexes()) {                   
                    allOk &= checkIndex(idx, out);
                }
                if (allOk) {
                    String err = db.checkPages();
                    out.message("pages: " + (err == null ? "OK" : err));
                } else {
                    out.message("pages: skipped (fix the tree errors first)");
                }
                return true;
            }
            BTree tree = db.tree(arg);                                
            if (tree == null) {
                out.message("No such table or index: " + arg);
                return true;
            }
            if (cmd.equals(".btree")) {
                tree.printTree(out);
            } else if (db.index(arg) != null) {
                checkIndex(db.index(arg), out);
            } else {
                checkTree(tree, out);
            }
            return true;
        }


        return false;
    }

    private boolean checkIndex(Index idx, Output out) throws IOException {
        String err = idx.tree.check();
        if (err == null) {
            err = idx.verify(db.table(idx.tableName));
        }
        out.message(idx.name + ": " + (err == null ? "OK" : err));
        return err == null;
    }

    private boolean checkTree(BTree tree, Output out) throws IOException {
        String err = tree.check();
        out.message(tree.name() + ": " + (err == null ? "OK" : err));
        return err == null;
    }
}
