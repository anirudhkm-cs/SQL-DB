package db;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Cursor {

    private final Table table;
    private final int pageNum;
    private int cellNum;
    private boolean endOfTable;

    Cursor(Table table, int pageNum, int cellNum) throws IOException {
        this.table = table;
        this.pageNum = pageNum;
        this.cellNum = cellNum;
        ByteBuffer page = table.pager().getPage(pageNum);
        this.endOfTable = cellNum>= LeafNode.numCells(page);
    }

    public static Cursor atStart(Table table) throws IOException {
        return new Cursor(table, table.rootPageNum(), 0);
    }

    public boolean isEndOfTable() {
        return endOfTable;
    }

    public void advance() throws IOException {
        cellNum++;
        ByteBuffer page = table.pager().getPage(pageNum);
        if (cellNum >= LeafNode.numCells(page)) {
            endOfTable = true;
        }
    }

    int pageNum() {
        return pageNum;
    }

    int cellNum() {
        return cellNum;
    }

    public Row read() throws IOException {
        ByteBuffer page = table.pager().getPage(pageNum);
        return RowLayout.deserialize(page, LeafNode.valueOffset(cellNum));
    }
}
