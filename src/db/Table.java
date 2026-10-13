package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class Table implements AutoCloseable {

    public static final int ROWS_PER_PAGE = Pager.PAGE_SIZE / RowLayout.ROW_SIZE;  // 14

    static final int MAGIC = 0x4A444231;          // "JDB1" in ASCII
    static final int HEADER_MAGIC_OFFSET = 0;
    static final int HEADER_NUM_ROWS_OFFSET = 4;

    private final Pager pager;
    private final Set<Integer> ids = new HashSet<>();
    private int numRows;

    public Table(Path path) throws IOException {
        this.pager = new Pager(path);

        if (pager.numPages() == 0) {
            ByteBuffer header = pager.getPage(0);
            header.putInt(HEADER_MAGIC_OFFSET, MAGIC);
            header.putInt(HEADER_NUM_ROWS_OFFSET, 0);
            pager.flushPage(0);
            pager.sync();
            this.numRows = 0;
        } else {
            ByteBuffer header = pager.getPage(0);
            if(header.getInt(HEADER_MAGIC_OFFSET) != MAGIC){
                pager.close();
                throw new IOException("Not a JDB1 database file: " + path);
            }
            int storedRows = header.getInt(HEADER_NUM_ROWS_OFFSET);
            int capacity = (pager.numPages() - 1) * ROWS_PER_PAGE;
            if(storedRows < 0 || storedRows > capacity){
                pager.close();
                throw new IOException("Corrupt header: numRows=" + storedRows+ " but file only has room for " + capacity);
            }
            this.numRows = storedRows;
        }

        for(Cursor c = Cursor.atStart(this); !c.isEndOfTable(); c.advance()){
            ids.add(c.read().id());
        }
    }

    Pager pager() {
        return pager;
    }

    public int numRows() {
        return numRows;
    }

    public void insert(Row row) throws ExecuteException {
        if(ids.contains(row.id())){
            throw new ExecuteException("Id: " + row.id() + " already present in db");
        }
        try {
            Cursor end = Cursor.atEnd(this);
            end.write(row);
            pager.flushPage(end.pageNum());
            pager.sync();
            
            ByteBuffer header = pager.getPage(0);
            header.putInt(HEADER_NUM_ROWS_OFFSET, numRows+1);
            pager.flushPage(0);
            pager.sync();
        } catch (IOException e) {
           throw new ExecuteException("Insert failed: " + e.getMessage());
        }
        numRows++;
        ids.add(row.id());
    }

    @Override
    public void close() throws IOException {
        pager.close();
    }
}
