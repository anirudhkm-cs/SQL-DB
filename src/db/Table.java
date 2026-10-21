package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public class Table implements AutoCloseable {

    // ---- File header (page 0) ----
    static final int MAGIC = 0x4A444233;        
    static final int HEADER_MAGIC_OFFSET = 0;
    static final int HEADER_ROOT_PAGE_OFFSET = 4;

    private final Pager pager;
    private int rootPageNum;

    public Table(Path path) throws IOException {
        this.pager = new Pager(path);

        if (pager.numPages() == 0) {
            ByteBuffer root = pager.getPage(1);
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
            if (header.getInt(HEADER_MAGIC_OFFSET) != MAGIC) {
                pager.close();
                throw new IOException("Not a valid database file");
            }
            int root = header.getInt(HEADER_ROOT_PAGE_OFFSET);
            if (root < 1 || root >= pager.numPages()) {
                pager.close();
                throw new IOException("Corrupt database: root page " + root + " out of range");
            }

            ByteBuffer rootPage = pager.getPage(root);
            byte type = rootPage.get(LeafNode.NODE_TYPE_OFFSET);
            if (type != LeafNode.TYPE_LEAF && type != LeafNode.TYPE_INTERNAL) {
                pager.close();
                throw new IOException("Corrupt database: root page " + root
                        + " has unknown node type " + type);
            }
            if (!LeafNode.isRoot(rootPage)) {
                pager.close();
                throw new IOException("Corrupt database: root page " + root + " is not marked as root");
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
        int pageNum = rootPageNum;
        ByteBuffer page = pager.getPage(pageNum);
        while(!LeafNode.isLeaf(page)){
            pageNum = InternalNode.child(page, InternalNode.findChildIndex(page, key));
            page = pager.getPage(pageNum);
        }
        return leafFind(pageNum, key);
    }

    private Cursor leafFind(int pageNum, int key) throws IOException {
        ByteBuffer leaf = pager.getPage(pageNum);
        int low = 0;
        int high = LeafNode.numCells(leaf);
        while (low < high) {
            int mid = (low + high) >>> 1;
            int midKey = LeafNode.key(leaf, mid);
            if (midKey == key) {
                return new Cursor(this, pageNum, mid);
            } else if (midKey < key) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return new Cursor(this, pageNum, low);
    }


    public void insert(Row row) throws ExecuteException {
        try {
            Cursor c = find(row.id());
            ByteBuffer page = pager.getPage(c.pageNum());
            int numCells = LeafNode.numCells(page);

            if (c.cellNum() < numCells && LeafNode.key(page, c.cellNum()) == row.id()) {
                throw new ExecuteException("Duplicate key");
            }

            if (numCells >= LeafNode.MAX_CELLS) {
                splitAndInsert(c, row);      
                return;
            }

            LeafNode.insertCell(page, c.cellNum(), row.id(), row);
            pager.flushPage(c.pageNum());
            pager.sync();
        } catch (IOException e) {
            throw new ExecuteException("Insert failed: " + e.getMessage());
        }
    }

    private void splitAndInsert(Cursor c, Row row) throws IOException, ExecuteException {
        int oldPageNum = c.pageNum();
        ByteBuffer oldLeaf = pager.getPage(oldPageNum);

        
        int oldMaxKey = LeafNode.maxKey(oldLeaf);
        boolean wasRoot = LeafNode.isRoot(oldLeaf);
        int parentPage = LeafNode.parent(oldLeaf);

        if(!wasRoot && InternalNode.numKeys(pager.getPage(parentPage)) >= InternalNode.MAX_KEYS){
            throw new ExecuteException("Table Full");
        }

        int newPageNum = pager.numPages();
        ByteBuffer newLeaf = pager.getPage(newPageNum);
        LeafNode.initialize(newLeaf);
        LeafNode.setParent(newLeaf, parentPage);

        for(int i = LeafNode.MAX_CELLS; i >= 0; i--){
            ByteBuffer dest = i>= LeafNode.LEFT_SPLIT_COUNT ? newLeaf : oldLeaf;
            int idx = i % LeafNode.LEFT_SPLIT_COUNT;
            if(i == c.cellNum()){
                LeafNode.writeCell(dest, idx, row.id(), row);
            }else if(i > c.cellNum()){
                LeafNode.copyCell(oldLeaf, i-1, dest, idx);
            }else{
                LeafNode.copyCell(oldLeaf, i, dest, idx);
            }
        }
        LeafNode.setNumCells(oldLeaf, LeafNode.LEFT_SPLIT_COUNT);
        LeafNode.setNumCells(newLeaf, LeafNode.RIGHT_SPLIT_COUNT);

        // TODO 3: link siblings: new.next = old.next; old.next = new

        LeafNode.setNextLeaf(newLeaf, LeafNode.nextLeaf(oldLeaf));
        LeafNode.setNextLeaf(oldLeaf, newPageNum);

        // TODO 4: fix up the tree above
        //   old leaf was the root -> createNewRoot(newPageNum)
        //   otherwise             -> updateParentAfterSplit(parent, oldPageNum, oldMaxKey, newPageNum)

        // TODO 5: flush every page you touched and sync.
        //   Question 2: what ORDER is crash-safe here? Is there one?
        //   Start simple (flush all, sync once), get it correct, then come back to this.

        pager.flushPage(newPageNum);
        if(wasRoot){
            int leftChildPage = createNewRoot(newPageNum);
            pager.flushPage(leftChildPage);
        }else{
            updateParentAfterSplit(parentPage, oldPageNum, oldMaxKey, newPageNum);
            pager.flushPage(parentPage);
        }
        pager.flushPage(oldPageNum);
        pager.sync();
    }

    private int createNewRoot(int rightChildPage) throws IOException {
        ByteBuffer root = pager.getPage(1);
        ByteBuffer right = pager.getPage(rightChildPage);

        int leftChildPage = pager.numPages();
        ByteBuffer left = pager.getPage(leftChildPage);
        System.arraycopy(root.array(), 0, left.array(), 0, Pager.PAGE_SIZE);

        LeafNode.setRoot(left, false);
        LeafNode.setParent(left, 1);
        LeafNode.setParent(right, 1);

        InternalNode.initialize(root);
        LeafNode.setRoot(root, true);
        InternalNode.setNumKeys(root, 1);
        InternalNode.setChild(root, 0, leftChildPage);
        InternalNode.setKey(root, 0, LeafNode.maxKey(left));
        InternalNode.setRightChild(root, rightChildPage);

        return leftChildPage;
    }

    private void updateParentAfterSplit(int parentPage, int oldChildPage, int oldMaxKey, int newChildPage)
            throws IOException {
        
        ByteBuffer parent = pager.getPage(parentPage);
        int n = InternalNode.numKeys(parent);

        int i = InternalNode.findChildIndex(parent, oldMaxKey);
        if(InternalNode.child(parent, i) != oldChildPage){
            throw new IllegalStateException("parent " + parentPage + " has no entry for child " + oldChildPage);
        }

        int oldChildNewMax = LeafNode.maxKey(pager.getPage(oldChildPage));
        InternalNode.setNumKeys(parent, n+1);
        if(i<n){
            for(int j=n; j > i+1; j--){
                InternalNode.setChild(parent, j, InternalNode.child(parent, j-1));
                InternalNode.setKey(parent, j, InternalNode.key(parent, j-1));
            }
            InternalNode.setKey(parent, i, oldChildNewMax);
            InternalNode.setChild(parent, i+1, newChildPage);
            InternalNode.setKey(parent, i+1, oldMaxKey);
        }else{
            InternalNode.setChild(parent, n, oldChildPage);
            InternalNode.setKey(parent, n, oldChildNewMax);
            InternalNode.setRightChild(parent, newChildPage);
        }
    }

    public void printTree() throws IOException {
        printNode(rootPageNum, 0);
    }

    public void printNode(int pageNum, int indentLevel) throws IOException {
        ByteBuffer page = pager.getPage(pageNum);
        String indent = "  ".repeat(indentLevel);

        if (LeafNode.isLeaf(page)) {
            int n = LeafNode.numCells(page);
            System.out.println(indent + "- leaf (size " + n + ")");
            for (int i = 0; i < n; i++) {
                System.out.println(indent + "  - " + LeafNode.key(page, i));
            }
        } else {
            int n = InternalNode.numKeys(page);
            System.out.println(indent + "- internal (size " + n + ")");
            for (int i = 0; i < n; i++) {
                printNode(InternalNode.child(page, i), indentLevel + 1);
                System.out.println(indent + "  - key " + InternalNode.key(page, i));
            }
            printNode(InternalNode.rightChild(page), indentLevel + 1);
        }
    }

    @Override
    public void close() throws IOException {
        pager.close();
    }
}
