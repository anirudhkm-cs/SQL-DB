package db.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public final class Wal implements AutoCloseable {

    static final byte TYPE_FRAME = 1;
    static final byte TYPE_COMMIT = 2;
    static final int FRAME_SIZE = 1 + 4 + Pager.PAGE_SIZE + 4;   // 4105
    static final int COMMIT_SIZE = 1 + 4 + 4;                    // 9

    record Frame(int pageNum, byte[] data) {}

    private final FileChannel channel;
    private long end;

    public Wal(Path dbPath) throws IOException {
        Path walPath = dbPath.resolveSibling(dbPath.getFileName() + "-wal");
        this.channel = FileChannel.open(walPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.end = channel.size();
    }

    boolean isEmpty() throws IOException {
        return channel.size() == 0;
    }

    void appendFrame(int pageNum, ByteBuffer page) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(FRAME_SIZE);
        buf.put(TYPE_FRAME);                                   // type: 1 byte
        buf.putInt(pageNum);                                   // page number: 4 bytes
        buf.put(page.duplicate().clear());                     // page content: 4096 bytes (cached page's position untouched)
        buf.putInt(checksum(buf.array(), 0, FRAME_SIZE - 4));  // CRC32 of the 4101 bytes above
        buf.flip();                                            // position 0, limit 4105


        if (CrashPoints.isArmed("mid-frame")) {         
            buf.limit(buf.limit() / 2);
            writeFully(buf, end);
            sync();
            CrashPoints.hit("mid-frame");
        }

        writeFully(buf, end);
        end += FRAME_SIZE;
    }

    void appendCommit(int frameCount) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(COMMIT_SIZE);
        buf.put(TYPE_COMMIT);
        buf.putInt(frameCount);
        buf.putInt(checksum(buf.array(), 0, COMMIT_SIZE - 4));
        buf.flip();
        writeFully(buf, end);
        end += COMMIT_SIZE;
    }

    void sync() throws IOException {
        channel.force(true);
    }

    void truncate() throws IOException {
        channel.truncate(0);   
        channel.force(true);  
        end = 0;       
    }

    List<Frame> readCommitted() throws IOException {
        List<Frame> committed = new ArrayList<>();
        List<Frame> pending = new ArrayList<>();
        long size = channel.size();
        long pos = 0;

        while (pos < size) {
            byte type = readFully(pos, 1).get();

            if (type == TYPE_FRAME) {
                if (size - pos < FRAME_SIZE) break;                   
                ByteBuffer buf = readFully(pos, FRAME_SIZE);
                int stored = buf.getInt(FRAME_SIZE - 4);
                if (checksum(buf.array(), 0, FRAME_SIZE - 4) != stored) break;   

                buf.position(1);                                        
                int pageNum = buf.getInt();
                byte[] data = new byte[Pager.PAGE_SIZE];
                buf.get(data);
                pending.add(new Frame(pageNum, data));
                pos += FRAME_SIZE;

            } else if (type == TYPE_COMMIT) {
                if (size - pos < COMMIT_SIZE) break;                    
                ByteBuffer buf = readFully(pos, COMMIT_SIZE);
                int stored = buf.getInt(COMMIT_SIZE - 4);
                if (checksum(buf.array(), 0, COMMIT_SIZE - 4) != stored) break;  

                int frameCount = buf.getInt(1);
                if (frameCount != pending.size()) break;                

                committed.addAll(pending);
                pending = new ArrayList<>();
                pos += COMMIT_SIZE;

            } else {
                break;                                                  
            }
        }

        return committed;
    }

    static int checksum(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private ByteBuffer readFully(long pos, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (channel.read(buf, pos + buf.position()) < 0) {
                throw new IOException("unexpected end of WAL");
            }
        }
        buf.flip();
        return buf;
    }

    private void writeFully(ByteBuffer buf, long pos) throws IOException {
        long start = pos - buf.position();
        while (buf.hasRemaining()) {
            channel.write(buf, start + buf.position());
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
