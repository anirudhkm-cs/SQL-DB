package db;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * How a Row is laid out in bytes. Reads and writes a row at a given offset
 * inside a page buffer; it never allocates its own buffer.
 *
 * Layout (291 bytes):
 *   [0..4)     id        int, big-endian
 *   [4..36)    username  UTF-8, zero-padded
 *   [36..291)  email     UTF-8, zero-padded
 */
public final class RowLayout {

    private RowLayout() {}

    public static final int ID_SIZE = Integer.BYTES;
    public static final int ID_OFFSET = 0;
    public static final int USERNAME_OFFSET = ID_OFFSET + ID_SIZE;
    public static final int EMAIL_OFFSET = USERNAME_OFFSET + Row.USERNAME_MAX;
    public static final int ROW_SIZE = EMAIL_OFFSET + Row.EMAIL_MAX;


    public static void serialize(Row row, ByteBuffer page, int offset) {
        page.putInt(offset+ID_OFFSET, row.id());
        writeString(page, offset+USERNAME_OFFSET, Row.USERNAME_MAX, row.username());
        writeString(page, offset+EMAIL_OFFSET, Row.EMAIL_MAX, row.email());
    }

    public static Row deserialize(ByteBuffer page, int offset) {
        int id = page.getInt(offset+ID_OFFSET);
        String username = readString(page, offset+USERNAME_OFFSET, Row.USERNAME_MAX);
        String email = readString(page, offset+EMAIL_OFFSET, Row.EMAIL_MAX);
        return new Row(id, username, email);
    }

    private static void writeString(ByteBuffer page, int offset, int fieldSize, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length > fieldSize){
            throw new IllegalArgumentException("String too long to store: " + value);
        }
        page.put(offset, bytes);
        for (int i = bytes.length; i < fieldSize; i++) {
            page.put(offset + i, (byte) 0);
        }
    }

    private static String readString(ByteBuffer page, int offset, int fieldSize) {
        byte[] bytes = new byte[fieldSize];
        page.get(offset, bytes);
        int len = 0;
        while(len < fieldSize && bytes[len] != 0){
            len++;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
