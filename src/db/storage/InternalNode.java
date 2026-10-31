package db.storage;

import java.nio.ByteBuffer;

public final class InternalNode {

    private InternalNode() {}

    static final int NUM_KEYS_OFFSET = LeafNode.COMMON_HEADER_SIZE;            // 6
    static final int RIGHT_CHILD_OFFSET = NUM_KEYS_OFFSET + Integer.BYTES;     // 10
    static final int HEADER_SIZE = RIGHT_CHILD_OFFSET + Integer.BYTES;         // 14

    static final int CHILD_SIZE = Integer.BYTES;
    static final int KEY_SIZE = Long.BYTES;                                    // 9
    static final int CELL_SIZE = CHILD_SIZE + KEY_SIZE;                        // 8

    static final int REAL_MAX_KEYS = (Pager.PAGE_SIZE - HEADER_SIZE) / CELL_SIZE;

    public static final int MAX_KEYS = REAL_MAX_KEYS;

    public static void initialize(ByteBuffer page) {
        page.put(LeafNode.NODE_TYPE_OFFSET, LeafNode.TYPE_INTERNAL);
        LeafNode.setRoot(page, false);
        LeafNode.setParent(page, 0);
        page.putInt(NUM_KEYS_OFFSET, 0);
        page.putInt(RIGHT_CHILD_OFFSET, 0);
    }

    public static int numKeys(ByteBuffer page) {
        return page.getInt(NUM_KEYS_OFFSET);
    }

    public static void setNumKeys(ByteBuffer page, int n) {
        page.putInt(NUM_KEYS_OFFSET, n);
    }

    public static int rightChild(ByteBuffer page) {
        return page.getInt(RIGHT_CHILD_OFFSET);
    }

    public static void setRightChild(ByteBuffer page, int childPage) {
        page.putInt(RIGHT_CHILD_OFFSET, childPage);
    }

    static int cellOffset(int i) {
        return HEADER_SIZE + (i*CELL_SIZE);
    }

    public static int child(ByteBuffer page, int i) {
        int n = numKeys(page);
        if(i<0 || i>n){
            throw new IllegalArgumentException("child index " + i + " out of range [0, " + n + "]");
        }
        if(i==n){
            return rightChild(page);
        }
        return page.getInt(cellOffset(i));
    }

    public static void setChild(ByteBuffer page, int i, int childPage) {
        int n = numKeys(page);
        if(i<0 || i>n){
            throw new IllegalArgumentException("child index " + i + " out of range [0, " + n + "]");
        }
        if(i==n){
            setRightChild(page, childPage);
        }else{
            page.putInt(cellOffset(i), childPage);
        }
    }

    public static long key(ByteBuffer page, int i) {
        return page.getLong(cellOffset(i) + CHILD_SIZE);
    }

    public static void setKey(ByteBuffer page, int i, long key) {
        page.putLong(cellOffset(i)+CHILD_SIZE, key);
    }

    public static int findChildIndex(ByteBuffer page, long key) {
       int lo = 0;
       int hi = numKeys(page);
       while(lo < hi){
            int mid = (lo + hi) >>> 1;
            if(key(page, mid) >= key){
                hi = mid;
            }else{
                lo = mid+1;
            }
       }
       return lo;
    }
}
