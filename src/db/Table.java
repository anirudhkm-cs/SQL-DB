package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-memory for now. In milestone 3 this becomes a Pager + pages. */
public class Table implements AutoCloseable{

    private final List<Row> rows;
    private final Set<Integer> ids;
    private final FileChannel channel;

    /** On-disk row: 4-byte id, then username and email as zero-padded UTF-8. */
    private static final int ROW_SIZE = Integer.BYTES + Row.USERNAME_MAX + Row.EMAIL_MAX;

    public Table(Path dbPath) throws IOException {
        this.rows = new ArrayList<>();
        this.ids = new HashSet<>();
        this.channel = FileChannel.open(dbPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = channel.size();
        int validLength = (int)(size-size%ROW_SIZE);
        if(validLength != size){
            channel.truncate(validLength);
        }
        ByteBuffer buf = ByteBuffer.allocate(validLength);
        while(buf.hasRemaining()){
            if(channel.read(buf, buf.position()) < 0){
                throw new IOException("Unexpected end of file: " + dbPath);
            }
        }
        buf.flip();
        while(buf.hasRemaining()){
            Row row = new Row(buf.getInt(), readString(buf, Row.USERNAME_MAX), readString(buf, Row.EMAIL_MAX));
            this.ids.add(row.id());
            this.rows.add(row);
        }
    }

    private static String readString(ByteBuffer buf, int size) {
        byte[] bytes = new byte[size];
        buf.get(bytes);
        int len = 0;
        while(len < size && bytes[len] != 0){
            len++;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    public void insert(Row row) throws ExecuteException {
        if(ids.contains(row.id())){
            throw new ExecuteException("Id already present");
        }
        ByteBuffer buf = ByteBuffer.allocate(ROW_SIZE);
        buf.putInt(row.id());
        writeString(buf, row.username(), Row.USERNAME_MAX);
        writeString(buf, row.email(), Row.EMAIL_MAX);

        buf.flip();

        // Write at the row's slot rather than the channel position, so a failed
        // partial write is overwritten by the next insert instead of misaligning it.
        long offset = (long) rows.size() * ROW_SIZE;
        try{
            while(buf.hasRemaining()){
                channel.write(buf, offset + buf.position());
            }
        }catch (IOException e) {
            throw new ExecuteException("Failed to write row to disk: " + e.getMessage());
        }
        this.ids.add(row.id());
        this.rows.add(row);
    }

    private static void writeString(ByteBuffer buf, String value, int size) throws ExecuteException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length > size){
            throw new ExecuteException("String too long to store: " + value);
        }
        buf.put(bytes);
        buf.position(buf.position() + size - bytes.length);
    }

    public List<Row> selectAll() {
        return this.rows;
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}