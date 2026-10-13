package db;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Cursor {

    private final Table table;
    private int rowNum;
    private boolean endOfTable;

    private Cursor(Table table, int rowNum) {
        this.table = table;
        this.rowNum = rowNum;
        endOfTable = rowNum >= table.numRows();
    }

    public static Cursor atStart(Table table) {
        return new Cursor(table, 0);
    }

    public static Cursor atEnd(Table table) {
        return new Cursor(table, table.numRows());
    }

    public boolean isEndOfTable() {
        return endOfTable;
    }

    public void advance() {
        rowNum++;
        endOfTable = rowNum>=table.numRows();
    }

    /*(page 0 is the header). */
    int pageNum() {
        return 1 + rowNum/Table.ROWS_PER_PAGE;
    }

    int offsetInPage() {
        return (rowNum % Table.ROWS_PER_PAGE) * RowLayout.ROW_SIZE;
    }

    public Row read() throws IOException {
        ByteBuffer page = table.pager().getPage(pageNum());
        return RowLayout.deserialize(page, offsetInPage());
        
    }

    public void write(Row row) throws IOException, ExecuteException {
        ByteBuffer page = table.pager().getPage(pageNum());
        RowLayout.serialize(row, page, offsetInPage());
    }
}
