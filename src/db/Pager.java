package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public class Pager implements AutoCloseable {

    public static final int PAGE_SIZE = 4096;

    private final FileChannel channel;
    private final Map<Integer, ByteBuffer> cache = new HashMap<>();
    private int numPages; 

    public Pager(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        long size = channel.size();
       
        if(size % PAGE_SIZE != 0){
            channel.close();
            throw new IOException("Database file is corrupt");
        }


        this.numPages = (int) (size / PAGE_SIZE);
    }

    public int numPages() {
        return numPages;
    }

    public ByteBuffer getPage(int n) throws IOException {
        ByteBuffer cached = cache.get(n);
        if(cached!=null){
            return cached;
        }
        ByteBuffer page = ByteBuffer.allocate(PAGE_SIZE);
        long pagesOnDisc = channel.size()/PAGE_SIZE;
        if(n<pagesOnDisc){
            long offset = (long) n * PAGE_SIZE;
            while (page.hasRemaining()) {
                int read = channel.read(page, offset + page.position());
                if(read == -1){
                    throw new IOException("Unexpected end of file reading page: " + n);
                }
            }
            page.clear();
        }
        if(n >= numPages){
            numPages = n+1;
        }
        cache.put(n, page);
        return page;
    }

    public void flushPage(int n) throws IOException {
        ByteBuffer cached = cache.get(n);
        if(cached == null){
            throw new IllegalStateException("Page: " + n + " isn't loaded in cache");
        }
        ByteBuffer out = cached.duplicate();
        out.clear();
        long offset = (long) n * PAGE_SIZE;
        while(out.hasRemaining()){
            channel.write(out, offset+out.position());
        }
    }
    
    public void sync() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
       channel.close();
    }
}
