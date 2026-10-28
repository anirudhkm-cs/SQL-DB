package db;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Wire {

    private Wire() {}

    static final byte QUERY = 'Q';
    static final byte ROW = 'R';
    static final byte MESSAGE = 'M';
    static final byte DONE = 'D';
    static final byte ERROR = 'E';

    static final int MAX_FRAME = 16 * 1024 * 1024;

    record Frame(byte type, byte[] payload) {}

    static void write(DataOutputStream out, byte type, byte[] payload) throws IOException {
        out.writeInt(payload.length + 1);
        out.writeByte(type);
        out.write(payload);
    }

    static Frame read(DataInputStream in) throws IOException {
        int b0 = in.read();
        if(b0 == -1){
            return null;
        }
        int length = (b0 << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) |  in.readUnsignedByte();
        if (length < 1 || length > MAX_FRAME) {
            throw new IOException("protocol error: bad frame length " + length);
        }
        byte type = in.readByte();
        byte[] payload = new byte[length - 1];
        in.readFully(payload);              
        return new Frame(type, payload);
    }

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    static byte[] encodeRow(List<Object> values) {
        return RecordCodec.encode(new Row(values));
    }

    static List<Object> decodeRow(byte[] payload) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        try {
            int count = buf.get() & 0xFF;
            List<Object> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte tag = buf.get();
                if (tag == RecordCodec.TYPE_INTEGER) {
                    values.add(buf.getInt());
                } else if (tag == RecordCodec.TYPE_TEXT) {
                    int len = buf.getShort() & 0xFFFF;
                    byte[] bytes = new byte[len];
                    buf.get(bytes);
                    values.add(text(bytes));
                } else {
                    throw new IOException("protocol error: unknown value tag " + tag);
                }
            }
            if (buf.hasRemaining()) {
                throw new IOException("protocol error: " + buf.remaining() + " extra bytes after row");
            }
            return values;
        } catch (BufferUnderflowException e) {
            throw new IOException("protocol error: row frame cut short");
        }
    }
}
