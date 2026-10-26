package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;


public final class Pager implements AutoCloseable {

    public static final int PAGE_SIZE = 4096;

    static final int HEADER_FREE_LIST_OFFSET = 8;
    static final byte TYPE_FREE = 2;            
    static final int FREE_NEXT_OFFSET = 4;

    private final FileChannel channel;
    private final Wal wal;
    private final Map<Integer, ByteBuffer> cache = new HashMap<>();
    private int numPages;

    private final Set<Integer> dirty = new TreeSet<>();
    private int committedNumPages;

    public Pager(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.wal = new Wal(path);

        try {
            recover();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }

        long size = channel.size();
        if (size % PAGE_SIZE != 0) {
            close();
            throw new IOException("Database file is corrupt");
        }
        this.numPages = (int) (size / PAGE_SIZE);
        this.committedNumPages = numPages;
    }

    public int numPages() {
        return numPages;
    }

    public int allocatePage() throws IOException {
        ByteBuffer header = getPage(0);
        int head = header.getInt(HEADER_FREE_LIST_OFFSET);
        if(head == 0){
            getPage(numPages);
            return numPages-1;
        }

        ByteBuffer page = getPage(head);
        if(page.get(0) != TYPE_FREE){
            throw new IllegalStateException("free list head " + head + " is not a free page");
        }
        header.putInt(HEADER_FREE_LIST_OFFSET, page.getInt(FREE_NEXT_OFFSET));
        Arrays.fill(page.array(), (byte) 0);
        markDirty(0);
        markDirty(head);
        return head;
    }

    public void freePage(int n) throws IOException {
        if(n <= 0 || n>= numPages){
            throw new IllegalArgumentException("cannot free page " + n);
        }
        ByteBuffer header = getPage(0);
        ByteBuffer page = getPage(n);

        Arrays.fill(page.array(), (byte)0);
        page.put(0, TYPE_FREE);
        page.putInt(FREE_NEXT_OFFSET, header.getInt(HEADER_FREE_LIST_OFFSET));
        header.putInt(HEADER_FREE_LIST_OFFSET, n); 

        markDirty(0);
        markDirty(n);
    }

    List<Integer> freeList() throws IOException {
        List<Integer> out = new ArrayList<>();
        int p = getPage(0).getInt(HEADER_FREE_LIST_OFFSET);
        while (p != 0) {
            if (p < 0 || p >= numPages || out.size() > numPages) {
                throw new IllegalStateException("free list: bad page " + p + " after " + out);
            }
            out.add(p);
            p = getPage(p).getInt(FREE_NEXT_OFFSET);
        }
        return out;
    }

    public ByteBuffer getPage(int n) throws IOException {
        ByteBuffer cached = cache.get(n);
        if (cached != null) {
            return cached;
        }
        ByteBuffer page = ByteBuffer.allocate(PAGE_SIZE);
        long pagesOnDisc = channel.size() / PAGE_SIZE;
        if (n < pagesOnDisc) {
            long offset = (long) n * PAGE_SIZE;
            while (page.hasRemaining()) {
                int read = channel.read(page, offset + page.position());
                if (read == -1) {
                    throw new IOException("Unexpected end of file reading page: " + n);
                }
            }
            page.clear();
        }
        if (n >= numPages) {
            numPages = n + 1;
        }
        cache.put(n, page);
        return page;
    }

    public void markDirty(int n) {
        if (!cache.containsKey(n)) {
            throw new IllegalStateException("page " + n + " marked dirty but isn't in the cache");
        }
        dirty.add(n);
    }

    public void commit() throws IOException {
        if (dirty.isEmpty()) {
            return;
        }

        for (int n : dirty) {
            wal.appendFrame(n, cache.get(n));
        }

        CrashPoints.hit("before-commit");

        wal.appendCommit(dirty.size());
        wal.sync();

        CrashPoints.hit("after-commit");

        boolean first = true;
        for (int n : dirty) {
            writePageToFile(n, cache.get(n));
            if (first) {
                CrashPoints.hit("mid-checkpoint");
                first = false;
            }
        }
        syncFile();

        CrashPoints.hit("before-truncate");

        wal.truncate();
        committedNumPages = numPages;
        dirty.clear();
    }

    public void abort() {
        for(int n: dirty){
            cache.remove(n);
        }

        cache.keySet().removeIf(n -> n>=committedNumPages);

        numPages = committedNumPages;
        dirty.clear();
    }

    private void recover() throws IOException {
        if (wal.isEmpty()) {
            return;
        }
        for (Wal.Frame frame : wal.readCommitted()) {
            writePageToFile(frame.pageNum(), ByteBuffer.wrap(frame.data()));
        }
        syncFile();
        wal.truncate();
    }


    private void writePageToFile(int n, ByteBuffer page) throws IOException {
        ByteBuffer out = page.duplicate();
        out.clear();
        long offset = (long) n * PAGE_SIZE;
        while (out.hasRemaining()) {
            channel.write(out, offset + out.position());
        }
    }

    private void syncFile() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        try {
            wal.close();
        } finally {
            channel.close();
        }
    }
}
