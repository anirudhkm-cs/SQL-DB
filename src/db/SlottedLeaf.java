package db;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class SlottedLeaf {

    private SlottedLeaf() {}

    static final int HEADER_SIZE = LeafNode.LEAF_HEADER_SIZE;         // 16
    static final int POINTER_SIZE = Short.BYTES;                      // 2
    static final int KEY_SIZE = Long.BYTES;                           // 8 
    static final int LENGTH_SIZE = Short.BYTES;                       // 2
    static final int CELL_HEADER_SIZE = KEY_SIZE + LENGTH_SIZE;       // 10

    static final int USABLE_SPACE = Pager.PAGE_SIZE - HEADER_SIZE;    // 4080

    static final int MAX_CELL_SPACE = USABLE_SPACE / 3;                                 // 1360
    public static final int MAX_RECORD_SIZE = MAX_CELL_SPACE - POINTER_SIZE - CELL_HEADER_SIZE; // 1352

    public static void initialize(ByteBuffer page) {
        LeafNode.initialize(page);
        setContentStart(page, Pager.PAGE_SIZE);
    }

    static int contentStart(ByteBuffer page) {
        return page.getShort(LeafNode.CONTENT_START_OFFSET) & 0xFFFF;
    }

    static void setContentStart(ByteBuffer page, int offset) {
        page.putShort(LeafNode.CONTENT_START_OFFSET, (short) offset);
    }

    static int pointerOffset(int i) {
        return HEADER_SIZE + i * POINTER_SIZE;
    }

    static int cellStart(ByteBuffer page, int i) {
        return page.getShort(pointerOffset(i)) & 0xFFFF;
    }

    static void setCellStart(ByteBuffer page, int i, int offset) {
        page.putShort(pointerOffset(i), (short) offset);
    }

    public static long key(ByteBuffer page, int i) {
        return page.getLong(cellStart(page, i));
    }

    public static long maxKey(ByteBuffer page) {
        return key(page, LeafNode.numCells(page) - 1);
    }

    static int recordLength(ByteBuffer page, int i) {
        return page.getShort(cellStart(page, i) + KEY_SIZE) & 0xFFFF;
    }
    
    public static int recordOffset(ByteBuffer page, int i) {
        return cellStart(page, i) + CELL_HEADER_SIZE;
    }

    static int cellLength(ByteBuffer page, int i) {
        return CELL_HEADER_SIZE + recordLength(page, i);
    }

    static byte[] cellBytes(ByteBuffer page, int i) {
        byte[] out = new byte[cellLength(page, i)];
        page.get(cellStart(page, i), out);
        return out;
    }

    static byte[] makeCell(long key, byte[] record) {
        ByteBuffer cell = ByteBuffer.allocate(CELL_HEADER_SIZE + record.length);
        cell.putLong(key);
        cell.putShort((short) record.length);
        cell.put(record);
        return cell.array();
    }

    static int freeSpace(ByteBuffer page) {
        int pointerEnd = pointerOffset(LeafNode.numCells(page));
        return contentStart(page) - pointerEnd;
    }

    static boolean hasRoomFor(ByteBuffer page, int cellLength) {
        return freeSpace(page) >= cellLength + POINTER_SIZE;
    }

    static void insertCell(ByteBuffer page, int i, byte[] cell) {
        int numCells = LeafNode.numCells(page);

        int start = contentStart(page) - cell.length;
        page.put(start, cell);
        setContentStart(page, start);

        for(int j = numCells; j>i; j--){
            setCellStart(page, j, cellStart(page, j-1));
        }

        setCellStart(page, i, start);
        LeafNode.setNumCells(page, numCells+1);
    }

    static void rebuild(ByteBuffer page, List<byte[]> cells) {
        LeafNode.setNumCells(page, 0);
        setContentStart(page, Pager.PAGE_SIZE);
        for (byte[] cell : cells) {
            insertCell(page, LeafNode.numCells(page), cell);
        }
    }

    static void removeCell(ByteBuffer page, int i) {
        int numCells = LeafNode.numCells(page);
        for(int j=i; j<numCells-1; j++){
            setCellStart(page, j, cellStart(page, j+1));
        }
        LeafNode.setNumCells(page, numCells-1);
    }

    static int totalFree(ByteBuffer page) {
        int numCells = LeafNode.numCells(page);
        int used = 0;
        for(int i=0; i<numCells; i++){
            used += cellLength(page, i) + POINTER_SIZE;
        }
        return USABLE_SPACE - used;
    }

    static void compact(ByteBuffer page) {
        int numCells = LeafNode.numCells(page);
        List<byte[]> cells = new ArrayList<>(numCells);
        for (int i = 0; i < numCells; i++) {
            cells.add(cellBytes(page, i));   
        }
        rebuild(page, cells);
    }
}
