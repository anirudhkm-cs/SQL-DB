package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public class Table implements AutoCloseable {

    static final int MAGIC = 0x4A444232;              
    static final int HEADER_MAGIC_OFFSET = 0;
    static final int HEADER_ROOT_PAGE_OFFSET = 4;     

    private final Pager pager;
    private int rootPageNum;

    public Table(Path path) throws IOException {
        this.pager = new Pager(path);

        if (pager.numPages() == 0) {

            ByteBuffer root  = pager.getPage(1);
            LeafNode.initialize(root);
            LeafNode.setRoot(root, true);

            ByteBuffer header = pager.getPage(0);
            header.putInt(HEADER_MAGIC_OFFSET, MAGIC);
            header.putInt(HEADER_ROOT_PAGE_OFFSET, 1);
            this.rootPageNum = 1;

            pager.flushPage(1);
            pager.sync();
            pager.flushPage(0);
            pager.sync();
            

        } else {
            ByteBuffer header = pager.getPage(0);

            if(header.getInt(HEADER_MAGIC_OFFSET) != MAGIC){
                pager.close();
                throw new IOException("Not a valid database file");
            }
            int root = header.getInt(HEADER_ROOT_PAGE_OFFSET);
            if(root < 1 || root >= pager.numPages()){
                pager.close();
                throw new IOException("Corrupt database: root page " + root + " out of range");
            }
            if(!LeafNode.isLeaf(pager.getPage(root))){
                pager.close();
                throw new IOException("Corrupt database: " + root + " is not a leaf");
            }

            this.rootPageNum = root;
        }
    }

    Pager pager() {
        return pager;
    }

    int rootPageNum() {
        return rootPageNum;
    }

    
    public Cursor find(int key) throws IOException {
        ByteBuffer root = pager.getPage(rootPageNum);

        int low = 0;
        int high = LeafNode.numCells(root);
        while(low < high){
            int mid = (low + high) >>> 1;
            int midKey = LeafNode.key(root, mid);
            if(midKey == key){
                return new Cursor(this, rootPageNum, mid);
            }else if(midKey < key){
                low = mid + 1;
            }else{
                high = mid;
            }
        }
        return new Cursor(this, rootPageNum, low);
    }

    public void insert(Row row) throws ExecuteException {
        try {
            Cursor c = find(row.id());
            ByteBuffer page = pager.getPage(c.pageNum());
            int numCells = LeafNode.numCells(page);
            
            if(c.cellNum() < numCells && LeafNode.key(page, c.cellNum()) == row.id()){
                throw new ExecuteException("Duplicate key");
            }
            
            if(numCells >= LeafNode.MAX_CELLS){
                throw new ExecuteException("Table Full");
            }

            LeafNode.insertCell(page, c.cellNum(), row.id(), row);
            pager.flushPage(c.pageNum());
            pager.sync();
        } catch (IOException e) {
            throw new ExecuteException("Insert failed: " + e.getMessage());
        }
    }

    public void printTree() throws IOException {
        printNode(rootPageNum, 0);
    }

    public void printNode(int pageNum, int indentLevel) throws IOException{
        ByteBuffer page = pager.getPage(pageNum);
        String indent = " ".repeat(indentLevel);

        if(LeafNode.isLeaf(page)){
            int n = LeafNode.numCells(page);
            System.out.println(indent + "leaf(size: " + n + " )");
            for(int i=0; i<n; i++){
                System.out.println(indent + " - " + i + " : " + LeafNode.key(page, i));
            }
        }else{
            throw new UnsupportedOperationException("internal nodes not yet supported");
        }

    }

    @Override
    public void close() throws IOException {
        pager.close();
    }
}
