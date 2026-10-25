package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BTree {

    private final Pager pager;
    private final String name;
    private final int rootPageNum;
    private final Schema schema;

    private final Set<Integer> dirty = new LinkedHashSet<>();

    public BTree(Pager pager, String name, int rootPageNum, Schema schema) {
        this.pager = pager;
        this.name = name;
        this.rootPageNum = rootPageNum;
        this.schema = schema;
    }

    static void initializeEmptyRoot(Pager pager, int pageNum) throws IOException {
        ByteBuffer root = pager.getPage(pageNum);
        SlottedLeaf.initialize(root);                 
        LeafNode.setRoot(root, true);
    }

    String name() {
        return name;
    }

    Schema schema() {
        return schema;
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
        while (!LeafNode.isLeaf(page)) {
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
            int midKey = SlottedLeaf.key(leaf, mid);
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

   
    int subtreeMaxKey(int pageNum) throws IOException {
        ByteBuffer page = pager.getPage(pageNum);
        if(LeafNode.isLeaf(page)){
            return SlottedLeaf.maxKey(page);
        }
        return subtreeMaxKey(InternalNode.rightChild(page));
    }

    public void insert(Row row) throws ExecuteException {
        try {
            Cursor c = find(row.key());         
            ByteBuffer page = pager.getPage(c.pageNum());
            int numCells = LeafNode.numCells(page);

            if (c.cellNum() < numCells && SlottedLeaf.key(page, c.cellNum()) == row.key()) {
                throw new ExecuteException("Duplicate key");
            }

            byte[] cell = SlottedLeaf.makeCell(row.key(), RecordCodec.encode(row));
            if (!SlottedLeaf.hasRoomFor(page, cell.length)) {
                splitAndInsert(c, cell);
            } else {
                SlottedLeaf.insertCell(page, c.cellNum(), cell);
                markDirty(c.pageNum());
            }
            flushDirty();
        } catch (IOException e) {
            dirty.clear();
            throw new ExecuteException("Insert failed: " + e.getMessage());
        }
    }

    private void markDirty(int pageNum) {
        dirty.add(pageNum);
    }

    private void flushDirty() throws IOException {
        for (int pageNum: dirty){
            if(pageNum!=rootPageNum){     
                pager.flushPage(pageNum);
            }
        }
        pager.sync();
        if(dirty.contains(rootPageNum)){
            pager.flushPage(rootPageNum);
            pager.sync();
        }
        dirty.clear();
    }

    private void splitAndInsert(Cursor c, byte[] cell) throws IOException {
        int oldPageNum = c.pageNum();
        ByteBuffer oldLeaf = pager.getPage(oldPageNum);

        int oldMaxKey = SlottedLeaf.maxKey(oldLeaf);
        boolean wasRoot = LeafNode.isRoot(oldLeaf);
        int parentPage = LeafNode.parent(oldLeaf);

        int newPageNum = pager.allocatePage();
        ByteBuffer newLeaf = pager.getPage(newPageNum);
        SlottedLeaf.initialize(newLeaf);                  
        LeafNode.setParent(newLeaf, parentPage);

        int n = LeafNode.numCells(oldLeaf);
        List<byte[]> cells = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            cells.add(SlottedLeaf.cellBytes(oldLeaf, i));
        }
        cells.add(c.cellNum(), cell);

        int total = 0;
        for (byte[] x : cells) {
            total += x.length + SlottedLeaf.POINTER_SIZE;
        }

        List<byte[]> left = new ArrayList<>();
        List<byte[]> right = new ArrayList<>();
        int leftCost = 0;
        int rightCost = 0;
        for (byte[] x : cells) {
            int cost = x.length + SlottedLeaf.POINTER_SIZE;
            if (leftCost < total / 2) {
                left.add(x);
                leftCost += cost;
            } else {
                right.add(x);
                rightCost += cost;
            }
        }

        if (left.isEmpty() || right.isEmpty()
                || leftCost > SlottedLeaf.USABLE_SPACE || rightCost > SlottedLeaf.USABLE_SPACE) {
            throw new IllegalStateException("bad leaf split: left=" + leftCost + " right=" + rightCost
                    + " total=" + total + " usable=" + SlottedLeaf.USABLE_SPACE);
        }

        SlottedLeaf.rebuild(oldLeaf, left);
        SlottedLeaf.rebuild(newLeaf, right);

        LeafNode.setNextLeaf(newLeaf, LeafNode.nextLeaf(oldLeaf));
        LeafNode.setNextLeaf(oldLeaf, newPageNum);

        markDirty(oldPageNum);
        markDirty(newPageNum);

        if (wasRoot) {
            createNewRoot(newPageNum);
        } else {
            insertIntoParent(parentPage, oldPageNum, oldMaxKey, newPageNum);
        }
    }

    private void insertIntoParent(int parentPage, int oldChildPage, int oldMaxKey, int newChildPage)
            throws IOException {
        ByteBuffer parent = pager.getPage(parentPage);
        if (InternalNode.numKeys(parent) < InternalNode.MAX_KEYS) {
            updateParentAfterSplit(parentPage, oldChildPage, oldMaxKey, newChildPage);
        } else {
            splitInternalAndInsert(parentPage, oldChildPage, oldMaxKey, newChildPage);
        }
    }

    private void updateParentAfterSplit(int parentPage, int oldChildPage, int oldMaxKey, int newChildPage)
            throws IOException {
        ByteBuffer parent = pager.getPage(parentPage);
        int n = InternalNode.numKeys(parent);

        int i = InternalNode.findChildIndex(parent, oldMaxKey);
        if (InternalNode.child(parent, i) != oldChildPage) {
            throw new IllegalStateException("parent " + parentPage + " has no entry for child " + oldChildPage);
        }

        int oldChildNewMax = subtreeMaxKey(oldChildPage);

        InternalNode.setNumKeys(parent, n + 1);
        if (i < n) {
            for (int j = n; j > i + 1; j--) {
                InternalNode.setChild(parent, j, InternalNode.child(parent, j - 1));
                InternalNode.setKey(parent, j, InternalNode.key(parent, j - 1));
            }
            InternalNode.setKey(parent, i, oldChildNewMax);
            InternalNode.setChild(parent, i + 1, newChildPage);
            InternalNode.setKey(parent, i + 1, oldMaxKey);
        } else {
            InternalNode.setChild(parent, n, oldChildPage);
            InternalNode.setKey(parent, n, oldChildNewMax);
            InternalNode.setRightChild(parent, newChildPage);
        }
        markDirty(parentPage);
    }

    private void splitInternalAndInsert(int nodePage, int oldChildPage, int oldMaxKey, int newChildPage)
            throws IOException {
        ByteBuffer node = pager.getPage(nodePage);
        boolean wasRoot = LeafNode.isRoot(node);
        int nodeParent = LeafNode.parent(node);

        int oldNodeMaxKey = (InternalNode.rightChild(node) == oldChildPage) ? oldMaxKey : subtreeMaxKey(nodePage);

        List<Integer> children = new ArrayList<>();
        List<Integer> keys = new ArrayList<>();

        int n = InternalNode.numKeys(node);
        for(int j=0; j<n; j++){
            children.add(InternalNode.child(node, j));
            keys.add(InternalNode.key(node, j));
        }
        children.add(InternalNode.rightChild(node));

        int i = children.indexOf(oldChildPage);
        if(i<0){
            throw new IllegalStateException("node " + nodePage + " has no entry for child " + oldChildPage);
        }
        int oldChildNewMax = subtreeMaxKey(oldChildPage);
        if(i<children.size() - 1){
            keys.set(i, oldChildNewMax);
            children.add(i+1, newChildPage);
            keys.add(i+1, oldMaxKey);
        }else{
            keys.add(oldChildNewMax);
            children.add(newChildPage);
        }
        if(children.size() != keys.size()+1){
            throw new IllegalStateException("split bookkeeping broken at node " + nodePage);
        }

        int rightPage = pager.allocatePage();
        ByteBuffer right = pager.getPage(rightPage);
        InternalNode.initialize(right);
        LeafNode.setParent(right, nodeParent);

        int L = (children.size()+1)/2;

        InternalNode.setNumKeys(node, L-1);
        for(int j=0; j<L-1; j++){
            InternalNode.setChild(node, j, children.get(j));
            InternalNode.setKey(node, j, keys.get(j));
        }
        InternalNode.setRightChild(node, children.get(L-1));

        int rightCount = children.size()-L;
        InternalNode.setNumKeys(right, rightCount-1);
        for(int j=0; j<rightCount-1; j++){
            InternalNode.setChild(right, j, children.get(L+j));
            InternalNode.setKey(right, j, keys.get(L+j));
        }
        InternalNode.setRightChild(right, children.get(children.size()-1));
        
        for(int j=L; j<children.size(); j++){
            int c = children.get(j);
            LeafNode.setParent(pager.getPage(c), rightPage);
            markDirty(c);
        }

        if(children.indexOf(newChildPage)<L){
            LeafNode.setParent(pager.getPage(newChildPage), nodePage);
            markDirty(newChildPage);
        }
        markDirty(nodePage);
        markDirty(rightPage);

        if(wasRoot){
            createNewRoot(rightPage);
        }else{
            insertIntoParent(nodeParent, nodePage, oldNodeMaxKey, rightPage);
        }
    }

    private int createNewRoot(int rightChildPage) throws IOException {
        ByteBuffer root = pager.getPage(rootPageNum);
        ByteBuffer right = pager.getPage(rightChildPage);

        int leftChildPage = pager.allocatePage();
        ByteBuffer left = pager.getPage(leftChildPage);
        System.arraycopy(root.array(), 0, left.array(), 0, Pager.PAGE_SIZE);

        LeafNode.setRoot(left, false);
        LeafNode.setParent(left, rootPageNum);
        LeafNode.setParent(right, rootPageNum);

        if(!LeafNode.isLeaf(left)){
            int n = InternalNode.numKeys(left);
            for(int i=0; i<=n; i++){
                int childPage = InternalNode.child(left, i);
                LeafNode.setParent(pager.getPage(childPage), leftChildPage);
                markDirty(childPage);
            }
        }

        InternalNode.initialize(root);
        LeafNode.setRoot(root, true);
        InternalNode.setNumKeys(root, 1);
        InternalNode.setChild(root, 0, leftChildPage);

        InternalNode.setKey(root, 0, subtreeMaxKey(leftChildPage));
        InternalNode.setRightChild(root, rightChildPage);

        markDirty(rootPageNum);
        markDirty(leftChildPage);
        markDirty(rightChildPage);
        return leftChildPage;
    }

    public String check() throws IOException {
        List<Integer> leavesInOrder = new ArrayList<>();
        int[] leafDepth = {-1}; 


        String err = checkNode(rootPageNum, 0, 0, leavesInOrder, leafDepth);
        if(err!=null){
            return err;
        }

        int pageNum = leavesInOrder.get(0);
        boolean havePrev = false;
        int prevKey = 0;

        for(int i=0; i<leavesInOrder.size(); i++){
            if(pageNum == 0){
                return "leaf chain: ended after " + i + " leaves, expected " + leavesInOrder.size();
            }
            if(pageNum != leavesInOrder.get(i)){
                return "leaf chain: step " + i + " is page " + pageNum + ", expected " + leavesInOrder.get(i);
            }

            ByteBuffer page = pager.getPage(pageNum);
            int n = LeafNode.numCells(page);
            for(int c=0; c<n; c++){
                int k = SlottedLeaf.key(page, c);
                if(havePrev && k<=prevKey){
                    return "leaf chain: key " + k + " in leaf " + pageNum + " is not greater than previous key " + prevKey;
                }
                prevKey = k;
                havePrev = true;
            }
            pageNum = LeafNode.nextLeaf(page);
        }

        if(pageNum != 0){
            return "leaf chain: last leaf " + leavesInOrder.get(leavesInOrder.size() - 1) + " points to page " + pageNum + ", expected 0";
        }
        return null;
    }

    private String checkNode(int pageNum, int expectedParent, int depth,
                             List<Integer> leavesInOrder, int[] leafDepth) throws IOException {
        ByteBuffer page = pager.getPage(pageNum);

        if (pageNum != rootPageNum && LeafNode.isRoot(page)) {
            return "page " + pageNum + ": root flag set on non-root page";
        }
        if (pageNum != rootPageNum && LeafNode.parent(page) != expectedParent){
            return "page " + pageNum + ": parent is " + LeafNode.parent(page) + ", expected " + expectedParent;
        }
        if (LeafNode.isLeaf(page)) {
            int n = LeafNode.numCells(page);

            int contentStart = SlottedLeaf.contentStart(page);
            int pointerEnd = SlottedLeaf.HEADER_SIZE + n * SlottedLeaf.POINTER_SIZE;
            if(contentStart < pointerEnd || contentStart > Pager.PAGE_SIZE){
                return "leaf " + pageNum + ": contentStart " + contentStart + " outside [" + pointerEnd + ", " + Pager.PAGE_SIZE + "]";
            }
            int[][] spans = new int[n][];
            for(int i=0; i<n; i++){
                int start = SlottedLeaf.cellStart(page, i);
                if(start < contentStart || start + SlottedLeaf.CELL_HEADER_SIZE > Pager.PAGE_SIZE){
                    return "leaf " + pageNum + ": cell " + i + " starts at " + start + ", outside [" + contentStart + ", " + Pager.PAGE_SIZE + ")";
                }
                int end = start + SlottedLeaf.cellLength(page, i);
                if(end > Pager.PAGE_SIZE){
                    return "leaf " + pageNum + ": cell " + i + " runs to " + end + ", past end of page";
                }
                spans[i] = new int[]{start, end, i};
            }
            Arrays.sort(spans, (a, b) -> Integer.compare(a[0], b[0]));
            for(int k=1; k<n; k++){
                if(spans[k-1][1] > spans[k][0]){
                    return "leaf " + pageNum + ": cells " + spans[k-1][2] + " and " + spans[k][2] + " overlap";
                }
            }

            for(int i=1; i<n; i++){
                if(SlottedLeaf.key(page, i-1) >= SlottedLeaf.key(page, i)){
                    return "leaf " + pageNum + ": keys not increasing at cell " + i + " (" + SlottedLeaf.key(page, i - 1) + ", " + SlottedLeaf.key(page, i) + ")";
                }
            }
            if(leafDepth[0] == -1){
                leafDepth[0] = depth;
            }else if(depth != leafDepth[0]){
                return "leaf " + pageNum + ": depth " + depth + ", expected " + leafDepth[0];
            }
            leavesInOrder.add(pageNum);
        } else {
            
            int n = InternalNode.numKeys(page);
            if(n < 1 || n > InternalNode.MAX_KEYS){
                return "internal " + pageNum + ": numKeys " + n + " out of range [1, " + InternalNode.MAX_KEYS + "]";
            }
            for(int i=1; i<n; i++){
                if(InternalNode.key(page, i-1) >= InternalNode.key(page, i)){
                    return "internal " + pageNum + ": keys not increasing at " + i + " (" + InternalNode.key(page, i - 1) + ", " + InternalNode.key(page, i) + ")";
                }
            }
            for(int i=0; i<=n; i++){
                int child = InternalNode.child(page, i);
                String err = checkNode(child, pageNum, depth+1, leavesInOrder, leafDepth);
                if(err!=null){
                    return err;
                }
                if(i<n){
                    int max = subtreeMaxKey(child);
                    if(InternalNode.key(page, i) != max){
                        return "internal " + pageNum + ": key " + i + " is " + InternalNode.key(page, i) + " but subtree max of child " + child + " is " + max;
                    }
                }
            }
        }
        return null;
    }

    void collectPages(List<Integer> out) throws IOException {
        collectPages(rootPageNum, out);
    }

    private void collectPages(int pageNum, List<Integer> out) throws IOException {
        out.add(pageNum);
        ByteBuffer page = pager.getPage(pageNum);
        if (LeafNode.isLeaf(page)) {
            return;
        }
        int n = InternalNode.numKeys(page);
        for (int i = 0; i <= n; i++) {
            collectPages(InternalNode.child(page, i), out);
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
                System.out.println(indent + "  - " + SlottedLeaf.key(page, i));
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
}
