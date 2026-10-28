package db;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;

public final class Server {

    private Server() {}

    static final class FrameOutput implements Output {
        private final DataOutputStream out;
        private IOException failed;

        FrameOutput(DataOutputStream out) {
            this.out = out;
        }

        @Override
        public void row(List<Object> values) {
            send(Wire.ROW, Wire.encodeRow(values));
        }

        @Override
        public void message(String line) {
            send(Wire.MESSAGE, Wire.text(line));
        }

        private void send(byte type, byte[] payload) {
            if (failed != null) return;
            try {
                Wire.write(out, type, payload);
            } catch (IOException e) {
                failed = e;
            }
        }

        void check() throws IOException {
            if (failed != null) throw failed;
        }
    }

    static final long DEFAULT_IDLE_TIMEOUT_MS = 60_000;

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 4) {
            System.out.println("Usage: java -cp out db.Server <database-file> [port] [lock-timeout-ms] [idle-timeout-ms]");
            System.exit(1);
        }
        Path dbPath = Path.of(args[0]);
        int port = args.length >= 2 ? Integer.parseInt(args[1]) : 5435;
        long lockTimeout = args.length >= 3 ? Long.parseLong(args[2]) : Session.DEFAULT_LOCK_TIMEOUT_MS;
        long idleTimeout = args.length >= 4 ? Long.parseLong(args[3]) : DEFAULT_IDLE_TIMEOUT_MS;

        Database db;
        try {
            db = new Database(dbPath);
        } catch (IOException e) {
            System.err.println("Error opening " + dbPath + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        try (ServerSocket listener = new ServerSocket(port)) {
            System.out.println("listening on port " + listener.getLocalPort());
            System.out.flush();
            int nextId = 1;
            while (true) {
                Socket socket = listener.accept();
                int id = nextId++;
                new Thread(() -> handle(socket, db, lockTimeout, idleTimeout, id), "client-" + id).start();
            }
        }
    }

    static void handle(Socket socket, Database db, long lockTimeout, long idleTimeout, int id) {
        System.out.println("client " + id + " connected from " + socket.getRemoteSocketAddress());
        boolean shutdown = false;
        try (socket) {
            shutdown = serve(socket, db, lockTimeout, idleTimeout, id);
        } catch (IOException e) {
            System.out.println("client " + id + ": " + e.getMessage());
        }
        System.out.println("client " + id + " left");
        if (shutdown) {
            db.lock.writeLock().lock();
            try {
                db.close();
            } catch (IOException e) {
                System.out.println("close failed: " + e.getMessage());
            }
            System.out.println("shut down");
            System.out.flush();
            System.exit(0);
        }
    }

    static boolean serve(Socket socket, Database db, long lockTimeout, long idleTimeout, int id) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        Session session = new Session(db, lockTimeout);
        try {
            while (true) {
               socket.setSoTimeout(session.inTransaction() ? (int) idleTimeout : 0);
                Wire.Frame frame;
                try {
                    frame = Wire.read(in);
                } catch (SocketTimeoutException e) {
                    Wire.write(out, Wire.ERROR, Wire.text("idle in a transaction for more than " + idleTimeout + " ms: rolled back, connection closed"));
                    out.flush();
                    return false;
                }
                if (frame == null) {
                    return false;                                   
                }
                if (frame.type() != Wire.QUERY) {
                    Wire.write(out, Wire.ERROR,
                            Wire.text("protocol error: expected a Q frame, got '" + (char) frame.type() + "'"));
                    out.flush();
                    return false;
                }
                String line = Wire.text(frame.payload()).trim();
                if (line.equals(".shutdown")) {
                    Wire.write(out, Wire.DONE, new byte[] {0});
                    out.flush();
                    return true;
                }
                FrameOutput results = new FrameOutput(out);
                Session.Result r = line.isEmpty() ? Session.Result.META : session.handle(line, results);
                results.check();
                if (r.kind() == Session.Kind.ERROR) {
                    Wire.write(out, Wire.ERROR, Wire.text(r.error()));
                } else {
                    Wire.write(out, Wire.DONE, new byte[] {(byte) (session.inTransaction() ? 1 : 0)});
                }
                out.flush();
            }
        } catch (IOException e) {
            System.out.println("client " + id + " error: " + e.getMessage());
            return false;
        } finally {
            if (session.close()) {
                System.out.println("client " + id + ": rolled back the open transaction of a client that left");
            }
        }
    }
}
