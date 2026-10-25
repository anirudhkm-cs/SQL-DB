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
        return tree.find(Integer.MIN_VALUE);
    }

    public boolean isEndOfTable() {
        return endOfTable;
    }

    public void advance() throws IOException {
        cellNum++;
        ByteBuffer page = tree.pager().getPage(pageNum);
        if (cellNum >= LeafNode.numCells(page)) {
            int next = LeafNode.nextLeaf(page);
            if (next == 0) {
                endOfTable = true;
            } else {
                pageNum = next;
                cellNum = 0;
            }
        }
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
