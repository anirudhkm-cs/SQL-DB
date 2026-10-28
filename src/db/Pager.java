package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Pager implements AutoCloseable {

    public static final int PAGE_SIZE = 4096;

    static final int HEADER_FREE_LIST_OFFSET = 8;
    static final byte TYPE_FREE = 2;
    static final int FREE_NEXT_OFFSET = 4;

    static final class Version {
        final long version;
        final ByteBuffer page;
        Version older;

        Version(long version, ByteBuffer page, Version older) {
            this.version = version;
            this.page = page;
            this.older = older;
        }
    }

    record Snapshot(long version, int numPages, Catalog catalog) {}

    private final FileChannel channel;
    private final Wal wal;

    private final Map<Integer, Version> versions = new HashMap<>();
    private final Set<Integer> withOldVersions = new TreeSet<>();
    private long committedVersion = 0;
    private int committedNumPages;
    private Catalog committedCatalog;
    private final TreeMap<Long, Integer> readers = new TreeMap<>();
    private boolean closed = false;

    private final Map<Integer, ByteBuffer> working = new HashMap<>();
    private final Set<Integer> dirty = new TreeSet<>();
    private int numPages;

    private final ThreadLocal<Snapshot> reading = new ThreadLocal<>();

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

    synchronized Snapshot beginRead() throws IOException {
        if(closed){
            throw new IOException("the database is shutting down");
        }
        if(reading.get() != null) throw new IllegalStateException("this thread is already reading");

        Snapshot s = new Snapshot(committedVersion, committedNumPages, committedCatalog);
        readers.merge(s.version, 1, Integer::sum);
        reading.set(s);
        return s;
    }

    synchronized void endRead() {
        Snapshot s = reading.get();
        if(s == null){
            throw new IllegalStateException("this thread isn't reading");
        }
        reading.remove();

        boolean wasOldest = s.version() == readers.firstKey();
        int left = readers.get(s.version()) - 1;
        if(left == 0){
            readers.remove(s.version);
        }else{
            readers.put(s.version(), left);
        }
        if(wasOldest && left == 0){
            prune(new ArrayList<>(withOldVersions));
        }
        notifyAll();
    }

    Snapshot snapshot() {
        return reading.get();
    }

    private void prune(Iterable<Integer> pages) {
        long oldest = readers.isEmpty() ? committedVersion : readers.firstKey();
        for(int n : pages){
            Version head = versions.get(n);
            if(head == null) continue;

            Version v = head;
            while ((v.version > oldest && v.older != null)) {
                v = v.older;
            }
            v.older = null;

            if(head.older == null){
                withOldVersions.remove(n);
            }
        }
    }

    /** For `.versions`: how many pages are cached, how many old versions are kept, for how many readers. */
    synchronized String versionStats() {
        int old = 0;
        for (int n : withOldVersions) {
            for (Version v = versions.get(n).older; v != null; v = v.older) old++;
        }
        int active = 0;
        for (int c : readers.values()) active += c;
        if (reading.get() != null) active--;            // not counting the reader asking
        return versions.size() + " pages cached, " + old + " old versions kept, " + active + " other readers, last commit " + committedVersion;
    }

    // ------------------------------------------------------------------ pages

    public synchronized int numPages() {
        Snapshot s = reading.get();
        return s != null ? s.numPages() : numPages;
    }

    /**
     * The newest committed version of page n, loading it from the file if it was never
     * loaded. Only pages that existed when the file was opened can be missing from
     * `versions`: every page written since then got a version when it was committed, and
     * prune always keeps the newest one. So the file's copy is exactly version 0.
     */
    private Version committed(int n) throws IOException {
        Version v = versions.get(n);
        if (v != null) {
            return v;
        }
        ByteBuffer page = ByteBuffer.allocate(PAGE_SIZE);
        long offset = (long) n * PAGE_SIZE;
        while (page.hasRemaining()) {
            int read = channel.read(page, offset + page.position());
            if (read == -1) {
                throw new IOException("Unexpected end of file reading page: " + n);
            }
        }
        page.clear();
        v = new Version(0, page, null);
        versions.put(n, v);
        return v;
    }

    public synchronized ByteBuffer getPage(int n) throws IOException {
        Snapshot s = reading.get();
        if (s != null) {
            if(n<0 || n>=s.numPages()){
                throw new IllegalStateException("page " + n + " does not exist in snapshot " + s.version());
            }
            Version v = committed(n);
            while(v!=null && v.version>s.version){
                v = v.older;
            }
            if(v==null){
                throw new IllegalStateException("page " + n + " has no version <= " + s.version() + " (pruned too early)");
            }
            return v.page.duplicate();
        }

        ByteBuffer page = working.get(n);
        if(page!=null){
            return page;
        }
        page = ByteBuffer.allocate(PAGE_SIZE);
        if(n<committedNumPages){
            page.put(0, committed(n).page, 0, PAGE_SIZE);
        }
        if(n>=numPages){
            numPages = n+1;
        }
        working.put(n, page);
        return page;
    }

    public synchronized void markDirty(int n) {
        if (reading.get() != null) {
            throw new IllegalStateException("a reader can't change page " + n);
        }
        if (!working.containsKey(n)) {
            throw new IllegalStateException("page " + n + " marked dirty but the writer never loaded it");
        }
        dirty.add(n);
    }

    public synchronized int allocatePage() throws IOException {
        ByteBuffer header = getPage(0);
        int head = header.getInt(HEADER_FREE_LIST_OFFSET);
        if (head == 0) {
            getPage(numPages);
            return numPages - 1;
        }
        ByteBuffer page = getPage(head);
        if (page.get(0) != TYPE_FREE) {
            throw new IllegalStateException("free list head " + head + " is not a free page");
        }
        header.putInt(HEADER_FREE_LIST_OFFSET, page.getInt(FREE_NEXT_OFFSET));
        Arrays.fill(page.array(), (byte) 0);
        markDirty(0);
        markDirty(head);
        return head;
    }

    public synchronized void freePage(int n) throws IOException {
        if (n <= 0 || n >= numPages) {
            throw new IllegalArgumentException("cannot free page " + n);
        }
        ByteBuffer header = getPage(0);
        ByteBuffer page = getPage(n);
        Arrays.fill(page.array(), (byte) 0);
        page.put(0, TYPE_FREE);
        page.putInt(FREE_NEXT_OFFSET, header.getInt(HEADER_FREE_LIST_OFFSET));
        header.putInt(HEADER_FREE_LIST_OFFSET, n);
        markDirty(0);
        markDirty(n);
    }

    synchronized List<Integer> freeList() throws IOException {
        List<Integer> out = new ArrayList<>();
        int pages = numPages();
        int p = getPage(0).getInt(HEADER_FREE_LIST_OFFSET);
        while (p != 0) {
            if (p < 0 || p >= pages || out.size() > pages) {
                throw new IllegalStateException("free list: bad page " + p + " after " + out);
            }
            out.add(p);
            p = getPage(p).getInt(FREE_NEXT_OFFSET);
        }
        return out;
    }

    // ------------------------------------------------------------------ commit / abort

    /** At open: the catalog every snapshot sees until the first commit. */
    synchronized void publishCatalog(Catalog catalog) {
        committedCatalog = catalog;
    }

    /**
     * Commit the writer's changes. `catalog` is the catalog as of this commit: readers whose
     * snapshot starts after it see it, together with the new pages.
     *
     * NOT synchronized: the WAL and the file belong to the writer, and readers must be able to
     * read while it waits for fsync. Only installing the new versions takes the monitor.
     */
    public void commit(Catalog catalog) throws IOException {
        if (dirty.isEmpty()) {
            working.clear();
            return;
        }

        for (int n : dirty) {
            wal.appendFrame(n, working.get(n));
        }
        CrashPoints.hit("before-commit");
        wal.appendCommit(dirty.size());
        wal.sync();
        CrashPoints.hit("after-commit");

        // The commit point has passed: make it visible to new readers, all at once.
        List<ByteBuffer> pages = new ArrayList<>();
        synchronized (this) {
            long v = committedVersion + 1;
            for(int n : dirty){
                ByteBuffer page = working.get(n);
                Version old = versions.get(n);
                versions.put(n, new Version(v, page, old));
                if( old!=null){
                    withOldVersions.add(n);
                }
                pages.add(page);
            }
            committedVersion = v;
            committedNumPages = numPages;
            committedCatalog = catalog;
            prune(new ArrayList<>(dirty));
        }

        // Checkpoint. Readers never read these pages from the file (they all have versions),
        // so this can overlap with them. The bytes are installed versions: nobody changes them.
        boolean first = true;
        int i = 0;
        for (int n : dirty) {
            writePageToFile(n, pages.get(i++));
            if (first) {
                CrashPoints.hit("mid-checkpoint");
                first = false;
            }
        }
        syncFile();
        CrashPoints.hit("before-truncate");
        wal.truncate();

        dirty.clear();
        working.clear();
    }

    /** Roll back: the committed versions were never touched, so just forget the copies. */
    public synchronized void abort() {
        working.clear();
        dirty.clear();
        numPages = committedNumPages;
    }

    // ------------------------------------------------------------------ files

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

    /** Wait for every active reader to finish, then close. New readers are refused. */
    @Override
    public void close() throws IOException {
        synchronized (this) {
            closed = true;
            while (!readers.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        try {
            wal.close();
        } finally {
            channel.close();
        }
    }
}
