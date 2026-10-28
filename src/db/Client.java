package db;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public final class Client {

    private Client() {}

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5435;

        Socket socket;
        try {
            socket = new Socket(host, port);
        } catch (IOException e) {
            System.err.println("Can't connect to " + host + ":" + port + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        boolean inTransaction = false;

        while (true) {
            System.out.print(inTransaction ? "db*> " : "db > ");
            String line = stdin.readLine();
            if (line == null) {
                System.out.println();
                leave(socket, inTransaction);
            }
            line = line.trim();
            if (line.isBlank()) {
                continue;
            }
            if (line.equals(".exit") || line.startsWith(".exit ")) {
                leave(socket, inTransaction);
            }

            try {
                Wire.write(out, Wire.QUERY, Wire.text(line));
                out.flush();
                boolean replyOver = false;
                while (!replyOver) {
                    Wire.Frame frame = Wire.read(in);
                    if(frame == null){
                        System.out.println("server closed the connection");
                        System.exit(1);
                    }
                    byte[] payload = frame.payload();
                    switch (frame.type()) {
                        case Wire.ROW -> System.out.println(Output.format(Wire.decodeRow(payload)));
                        case Wire.MESSAGE -> System.out.println(Wire.text(payload));
                        case Wire.ERROR -> {
                            System.out.println(Wire.text(payload));
                            replyOver = true;
                        }
                        case Wire.DONE -> {
                            inTransaction = payload[0] == 1;
                            if (!line.startsWith(".")) {
                                System.out.println("Executed");
                            }
                            replyOver = true;
                        }
                        default -> {
                            System.out.println("protocol error: unexpected frame type " + (char) frame.type());
                            System.exit(1);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("server closed the connection");
                System.exit(1);
            }
            
        }
    }

    private static void leave(Socket socket, boolean inTransaction) throws IOException {
        if (inTransaction) {
            System.out.println("Open transaction discarded (it was never committed)");
        }
        socket.close();
        System.exit(0);
    }
}
