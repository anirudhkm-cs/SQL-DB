package db.storage;

import java.nio.ByteBuffer;

public final class LeafNode {

    private LeafNode() {}

    public static final byte TYPE_INTERNAL = 0;
    public static final byte TYPE_LEAF = 1;

    static final int NODE_TYPE_OFFSET = 0;
    static final int IS_ROOT_OFFSET = 1;
    static final int PARENT_OFFSET = 2;
    static final int COMMON_HEADER_SIZE = 6;

    static final int NUM_CELLS_OFFSET = COMMON_HEADER_SIZE;                     // 6
    static final int NEXT_LEAF_OFFSET = NUM_CELLS_OFFSET + Integer.BYTES;       // 10
    static final int CONTENT_START_OFFSET = NEXT_LEAF_OFFSET + Integer.BYTES;   // 14   
    static final int LEAF_HEADER_SIZE = CONTENT_START_OFFSET + Short.BYTES;     // 16   

    public static boolean isLeaf(ByteBuffer page) {
        return page.get(NODE_TYPE_OFFSET) == TYPE_LEAF;
    }

    public static boolean isRoot(ByteBuffer page) {
        return page.get(IS_ROOT_OFFSET) == 1;
    }

    public static void setRoot(ByteBuffer page, boolean root) {
        page.put(IS_ROOT_OFFSET, (byte) (root ? 1 : 0));
    }

    public static int parent(ByteBuffer page) {
        return page.getInt(PARENT_OFFSET);
    }

    public static void setParent(ByteBuffer page, int parentPage) {
        page.putInt(PARENT_OFFSET, parentPage);
    }

    public static void initialize(ByteBuffer page) {
        page.put(NODE_TYPE_OFFSET, TYPE_LEAF);
        setRoot(page, false);
        page.putInt(NUM_CELLS_OFFSET, 0);
        setParent(page, 0);
        setNextLeaf(page, 0);
    }

    public static int numCells(ByteBuffer page) {
        return page.getInt(NUM_CELLS_OFFSET);
    }

    public static void setNumCells(ByteBuffer page, int n) {
        page.putInt(NUM_CELLS_OFFSET, n);
    }

    public static int nextLeaf(ByteBuffer page) {
        return page.getInt(NEXT_LEAF_OFFSET);
    }

    public static void setNextLeaf(ByteBuffer page, int nextPage) {
        page.putInt(NEXT_LEAF_OFFSET, nextPage);
    }
}
