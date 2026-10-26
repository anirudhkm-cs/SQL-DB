package db;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Cursor {

    private final BTree tree;        
    private int pageNum;
    private int cellNum;
    private boolean endOfTable;

    Cursor(BTree tree, int pageNum, int cellNum) throws IOException {
        this.tree = tree;
        this.pageNum = pageNum;
        this.cellNum = cellNum;
        ByteBuffer page = tree.pager().getPage(pageNum);
        this.endOfTable = cellNum >= LeafNode.numCells(page);
    }

    public static Cursor atStart(BTree tree) throws IOException {
        Cursor c = tree.find(Integer.MIN_VALUE);
        c.skipEmptyLeaves();                     
        return c;
    }

    private void skipEmptyLeaves() throws IOException {
        ByteBuffer page = tree.pager().getPage(pageNum);
        while (cellNum >= LeafNode.numCells(page)) {
            int next = LeafNode.nextLeaf(page);
            if (next == 0) break;             
            pageNum = next;
            cellNum = 0;
            page = tree.pager().getPage(pageNum);
        }
        endOfTable = cellNum >= LeafNode.numCells(page);
    }

    public boolean isEndOfTable() {
        return endOfTable;
    }

    public void advance() throws IOException {
        cellNum++;
        skipEmptyLeaves();                       
    }


    int pageNum() {
        return pageNum;
    }

    int cellNum() {
        return cellNum;
    }

    public Row read() throws IOException {
        ByteBuffer page = tree.pager().getPage(pageNum);
        return RecordCodec.decode(tree.schema(), page, SlottedLeaf.recordOffset(page, cellNum));
    }
}
