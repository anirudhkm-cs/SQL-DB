package db;

import java.nio.ByteBuffer;

public final class LeafNode {

    private LeafNode() {}

    public static final byte TYPE_INTERNAL = 0;
    public static final byte TYPE_LEAF = 1;

    static final int NODE_TYPE_OFFSET = 0;
    static final int IS_ROOT_OFFSET = 1;
    static final int PARENT_OFFSET = 2;
    static final int COMMON_HEADER_SIZE = 6;

    static final int NUM_CELLS_OFFSET = COMMON_HEADER_SIZE;
    static final int LEAF_HEADER_SIZE = COMMON_HEADER_SIZE + Integer.BYTES;   // 10

    static final int KEY_SIZE = Integer.BYTES;
    static final int VALUE_SIZE = RowLayout.ROW_SIZE;
    static final int CELL_SIZE = KEY_SIZE + VALUE_SIZE;                         // 295
    static final int SPACE_FOR_CELLS = Pager.PAGE_SIZE - LEAF_HEADER_SIZE;
    public static final int MAX_CELLS = SPACE_FOR_CELLS / CELL_SIZE;            // 13

    
    public static boolean isLeaf(ByteBuffer page) {
        return page.get(NODE_TYPE_OFFSET) == TYPE_LEAF;
    }

    public static boolean isRoot(ByteBuffer page) {
        return page.get(IS_ROOT_OFFSET) == 1;
    }

    public static void setRoot(ByteBuffer page, boolean root) {
        page.put(IS_ROOT_OFFSET, (byte) (root ? 1 : 0));
    }

    public static void initialize(ByteBuffer page) {
        page.put(NODE_TYPE_OFFSET, TYPE_LEAF);
        setRoot(page, false);
        page.putInt(NUM_CELLS_OFFSET, 0);
    }

    public static int numCells(ByteBuffer page) {
       return page.getInt(NUM_CELLS_OFFSET);
    }

    public static void setNumCells(ByteBuffer page, int n) {
        page.putInt(NUM_CELLS_OFFSET, n);
    }

    static int cellOffset(int i) {
        return LEAF_HEADER_SIZE + (i * CELL_SIZE);
    }

    public static int key(ByteBuffer page, int i) {
        return page.getInt(cellOffset(i));
    }

    public static int valueOffset(int i) {
        return cellOffset(i) + KEY_SIZE;
    }

    public static void insertCell(ByteBuffer page, int cellNum, int key, Row row) {
        int n = numCells(page);
        
        byte[] bytes = page.array();
        int base = page.arrayOffset();
        System.arraycopy(bytes, base+cellOffset(cellNum), bytes, base+cellOffset(cellNum+1), (n-cellNum) * CELL_SIZE);
    
        page.putInt(cellOffset(cellNum), key);
        RowLayout.serialize(row, page, valueOffset(cellNum));

        setNumCells(page, n+1);
    }
}
