package db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BTree {

    private final Pager pager;
    private final String name;
    private final int rootPageNum;
    private final Schema schema;

    
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
        pager.markDirty(pageNum);                  
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

    Integer subtreeMin(int pageNum) throws IOException {
        ByteBuffer page = pager.getPage(pageNum);
        if (LeafNode.isLeaf(page)) {
            return LeafNode.numCells(page) == 0 ? null : SlottedLeaf.key(page, 0);
        }
        int n = InternalNode.numKeys(page);
        for (int i = 0; i <= n; i++) {
            Integer k = subtreeMin(InternalNode.child(page, i));
            if (k != null) return k;
        }
        return null;
    }

    Integer subtreeMax(int pageNum) throws IOException {
        ByteBuffer page = pager.getPage(pageNum);
        if (LeafNode.isLeaf(page)) {
            int n = LeafNode.numCells(page);
            return n == 0 ? null : SlottedLeaf.key(page, n - 1);
        }
        int n = InternalNode.numKeys(page);
        for (int i = n; i >= 0; i--) {
            Integer k = subtreeMax(InternalNode.child(page, i));
            if (k != null) return k;
        }
        return null;
    }

    public void insert(Row row) throws IOException,ExecuteException {
        Cursor c = find(row.key());
        ByteBuffer page = pager.getPage(c.pageNum());
        int numCells = LeafNode.numCells(page);

        if (c.cellNum() < numCells && SlottedLeaf.key(page, c.cellNum()) == row.key()) {
            throw new ExecuteException("Duplicate key");
        }

        byte[] cell = SlottedLeaf.makeCell(row.key(), RecordCodec.encode(row));
        insertCellAt(c, cell);  
    }

    private void insertCellAt(Cursor c, byte[] cell) throws IOException {
        ByteBuffer page = pager.getPage(c.pageNum());
        if (SlottedLeaf.hasRoomFor(page, cell.length)) {
            SlottedLeaf.insertCell(page, c.cellNum(), cell);
            markDirty(c.pageNum());
            return;
        }
        if (SlottedLeaf.totalFree(page) >= cell.length + SlottedLeaf.POINTER_SIZE) {
            SlottedLeaf.compact(page);
            SlottedLeaf.insertCell(page, c.cellNum(), cell);
            markDirty(c.pageNum());
            return;
        }
        splitAndInsert(c, cell);
    }

    public boolean delete(int key) throws IOException, ExecuteException {
            Cursor c = find(key);
            ByteBuffer page = pager.getPage(c.pageNum());
            if (c.cellNum() >= LeafNode.numCells(page)
                    || SlottedLeaf.key(page, c.cellNum()) != key) {
                return false;
            }

            SlottedLeaf.removeCell(page, c.cellNum());
            markDirty(c.pageNum());
            if (!LeafNode.isRoot(page) && usedBytes(page) < MIN_LEAF_BYTES) {
                rebalance(c.pageNum());
            }
            return true;
    }

    static final int MIN_LEAF_BYTES = SlottedLeaf.USABLE_SPACE / 4;
    static final int MIN_KEYS = Math.max(1, InternalNode.MAX_KEYS / 4);

    static int usedBytes(ByteBuffer leaf) {
        return SlottedLeaf.USABLE_SPACE - SlottedLeaf.totalFree(leaf);
    }

    private void rebalance(int pageNum) throws IOException {
        ByteBuffer node = pager.getPage(pageNum);
        int parentPage = LeafNode.parent(node);
        ByteBuffer parent = pager.getPage(parentPage);

        int i = indexOfChild(parent, pageNum);
        if(i<0){
            throw new IllegalStateException("parent " + parentPage + " has no entry for child " + pageNum);
        }
        int leftIdx = i < InternalNode.numKeys(parent) ? i : i-1;
        int leftPage = InternalNode.child(parent, leftIdx);
        int rightPage = InternalNode.child(parent, leftIdx+1);

        boolean merged = LeafNode.isLeaf(node) ? rebalanceLeaves(parentPage, leftIdx, leftPage, rightPage) : rebalanceInternals(parentPage, leftIdx, leftPage, rightPage);
        if(!merged){
            return;
        }

        removeFromParent(parentPage, leftIdx);
        pager.freePage(rightPage);

        parent = pager.getPage(parentPage);
        if(LeafNode.isRoot(parent)){
            if(InternalNode.numKeys(parent) == 0){
                collapseRoot();
            }
        }else if(InternalNode.numKeys(parent) < MIN_KEYS){
            rebalance(parentPage);
        }
    }

    private boolean rebalanceLeaves(int parentPage, int leftIdx, int leftPage, int rightPage) throws IOException {
        ByteBuffer left = pager.getPage(leftPage);
        ByteBuffer right = pager.getPage(rightPage);
        
        List<byte[]> cells = new ArrayList<>();
        for(int j=0; j<LeafNode.numCells(left); j++){
            cells.add(SlottedLeaf.cellBytes(left, j));
        }
        for(int j=0; j<LeafNode.numCells(right); j++){
            cells.add(SlottedLeaf.cellBytes(right, j));
        }

        int total = 0;
        for(byte[] x: cells){
            total += x.length + SlottedLeaf.POINTER_SIZE;
        }

        if(total <= SlottedLeaf.USABLE_SPACE){
            SlottedLeaf.rebuild(left, cells);
            LeafNode.setNextLeaf(left, LeafNode.nextLeaf(right));
            markDirty(leftPage);
            markDirty(rightPage);
            return true;
        }

        List<byte[]> leftCells = new ArrayList<>();
        List<byte[]> rightCells = new ArrayList<>();
        int leftCost = 0;
        for (byte[] x : cells) {
            if (leftCost < total / 2) {
                leftCells.add(x);
                leftCost += x.length + SlottedLeaf.POINTER_SIZE;
            } else {
                rightCells.add(x);
            }
        }
        SlottedLeaf.rebuild(left, leftCells);
        SlottedLeaf.rebuild(right, rightCells);

        ByteBuffer parent = pager.getPage(parentPage);
        InternalNode.setKey(parent, leftIdx, SlottedLeaf.maxKey(left));

        markDirty(leftPage);
        markDirty(rightPage);
        markDirty(parentPage);
        return false;
    }

    private boolean rebalanceInternals(int parentPage, int leftIdx, int leftPage, int rightPage) throws IOException {
        ByteBuffer parent = pager.getPage(parentPage);
        ByteBuffer left = pager.getPage(leftPage);
        ByteBuffer right = pager.getPage(rightPage);

        List<Integer> keys = keysOf(left);
        keys.add(InternalNode.key(parent, leftIdx));
        keys.addAll(keysOf(right));
        List<Integer> children = childrenOf(left);
        children.addAll(childrenOf(right));

        if(keys.size() <= InternalNode.MAX_KEYS){
            writeInternal(left, keys, children);
            markDirty(leftPage);
            markDirty(rightPage);
            setParents(children, leftPage);
            return true;
        }

        int L = (children.size() +1) / 2;
        int promoted = keys.get(L-1);
        List<Integer> leftChildren = new ArrayList<>(children.subList(0, L));
        List<Integer> rightChildren = new ArrayList<>(children.subList(L, children.size()));

        writeInternal(left, new ArrayList<>(keys.subList(0, L-1)), leftChildren);
        writeInternal(right, new ArrayList<>(keys.subList(L, keys.size())), rightChildren);
        markDirty(leftPage);
        markDirty(rightPage);

        setParents(leftChildren, leftPage);
        setParents(rightChildren, rightPage);

        parent = pager.getPage(parentPage);
        InternalNode.setKey(parent, leftIdx, promoted);
        markDirty(parentPage);
        return false;
    }

    private void collapseRoot() throws IOException {
        ByteBuffer root = pager.getPage(rootPageNum);
        int childPage = InternalNode.rightChild(root);
        ByteBuffer child = pager.getPage(childPage);

        System.arraycopy(child.array(), 0, root.array(), 0, Pager.PAGE_SIZE);
        LeafNode.setRoot(root, true);
        LeafNode.setParent(root, 0);
        markDirty(rootPageNum);

        if(!LeafNode.isLeaf(root)){
            setParents(childrenOf(root), rootPageNum);
        }
        pager.freePage(childPage);
    }

    private void removeFromParent(int parentPage, int leftIdx) throws IOException {
        ByteBuffer parent = pager.getPage(parentPage);
        List<Integer> keys = keysOf(parent);
        List<Integer> children = childrenOf(parent);
        keys.remove(leftIdx);
        children.remove(leftIdx+1);
        writeInternal(parent, keys, children);
        markDirty(parentPage);
    }

    static List<Integer> keysOf(ByteBuffer node) {
        List<Integer> out = new ArrayList<>();
        for (int j = 0; j < InternalNode.numKeys(node); j++) out.add(InternalNode.key(node, j));
        return out;
    }
  
    static List<Integer> childrenOf(ByteBuffer node) {
        List<Integer> out = new ArrayList<>();
        for (int j = 0; j <= InternalNode.numKeys(node); j++) out.add(InternalNode.child(node, j));
        return out;
    }

    static void writeInternal(ByteBuffer node, List<Integer> keys, List<Integer> children) {
        if (children.size() != keys.size() + 1) {
            throw new IllegalStateException("internal node: " + keys.size() + " keys but " + children.size() + " children");
        }
        InternalNode.setNumKeys(node, keys.size());
        for (int j = 0; j < keys.size(); j++) {
            InternalNode.setChild(node, j, children.get(j));
            InternalNode.setKey(node, j, keys.get(j));
        }
        InternalNode.setRightChild(node, children.get(children.size() - 1));
    }

    private void setParents(List<Integer> children, int parentPage) throws IOException {
        for (int c : children) {
            ByteBuffer p = pager.getPage(c);
            if (LeafNode.parent(p) != parentPage) {
                LeafNode.setParent(p, parentPage);
                markDirty(c);
            }
        }
    }

    public boolean update(Row row) throws ExecuteException, IOException {
        Cursor c = find(row.key());
        ByteBuffer page = pager.getPage(c.pageNum());

        if (c.cellNum() >= LeafNode.numCells(page)
                || SlottedLeaf.key(page, c.cellNum()) != row.key()) {
            return false;
        }

        SlottedLeaf.removeCell(page, c.cellNum());
        markDirty(c.pageNum());

        byte[] cell = SlottedLeaf.makeCell(row.key(), RecordCodec.encode(row));
        insertCellAt(c, cell);
        return true;
    }

    
    private void markDirty(int pageNum) {
        pager.markDirty(pageNum);
    }


    private void splitAndInsert(Cursor c, byte[] cell) throws IOException {
        int oldPageNum = c.pageNum();
        ByteBuffer oldLeaf = pager.getPage(oldPageNum);

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

        int separator = SlottedLeaf.maxKey(oldLeaf);
        if (wasRoot) {
            createNewRoot(newPageNum, separator);
        } else {
            insertIntoParent(parentPage, oldPageNum, newPageNum, separator);
        }
    }

    private void insertIntoParent(int parentPage, int oldChildPage, int newChildPage, int separator)
            throws IOException {
        ByteBuffer parent = pager.getPage(parentPage);
        if (InternalNode.numKeys(parent) < InternalNode.MAX_KEYS) {
            updateParentAfterSplit(parentPage, oldChildPage, newChildPage, separator);
        } else {
            splitInternalAndInsert(parentPage, oldChildPage, newChildPage, separator);
        }
    }

    private static int indexOfChild(ByteBuffer parent, int child) {
        int n = InternalNode.numKeys(parent);
        for (int i = 0; i <= n; i++) {
            if (InternalNode.child(parent, i) == child) return i;
        }
        return -1;
    }

    private void updateParentAfterSplit(int parentPage, int oldChildPage, int newChildPage, int separator)
            throws IOException {
        ByteBuffer parent = pager.getPage(parentPage);
        int n = InternalNode.numKeys(parent);

        int i = indexOfChild(parent, oldChildPage);                   
        if (i < 0) {
            throw new IllegalStateException("parent " + parentPage + " has no entry for child " + oldChildPage);
        }

        InternalNode.setNumKeys(parent, n + 1);
        if (i < n) {
            int oldUpperBound = InternalNode.key(parent, i);        
            for (int j = n; j > i + 1; j--) {
                InternalNode.setChild(parent, j, InternalNode.child(parent, j - 1));
                InternalNode.setKey(parent, j, InternalNode.key(parent, j - 1));
            }
            InternalNode.setKey(parent, i, separator);
            InternalNode.setChild(parent, i + 1, newChildPage);
            InternalNode.setKey(parent, i + 1, oldUpperBound);
        } else {
            InternalNode.setChild(parent, n, oldChildPage);
            InternalNode.setKey(parent, n, separator);
            InternalNode.setRightChild(parent, newChildPage);
        }
        markDirty(parentPage);
    }

    private void splitInternalAndInsert(int nodePage, int oldChildPage, int newChildPage, int separator)
            throws IOException {
        ByteBuffer node = pager.getPage(nodePage);
        boolean wasRoot = LeafNode.isRoot(node);
        int nodeParent = LeafNode.parent(node);

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
        if(i<children.size() - 1){
            int oldUpperBound = keys.get(i);                           
            keys.set(i, separator);
            children.add(i+1, newChildPage);
            keys.add(i+1, oldUpperBound);
        }else{
            keys.add(separator);                                       
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
        int promoted = keys.get(L-1);                                  

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
            createNewRoot(rightPage, promoted);
        }else{
            insertIntoParent(nodeParent, nodePage, rightPage, promoted);
        }
    }

    private int createNewRoot(int rightChildPage, int separator) throws IOException {
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

        InternalNode.setKey(root, 0, separator);
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
            if (n == 0 && pageNum != rootPageNum) {        // 7b: merging means no empty leaves
                return "leaf " + pageNum + ": empty non-root leaf";
            }

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
            int minKeys = pageNum == rootPageNum ? 1 : MIN_KEYS;    // 7b
            if(n < minKeys || n > InternalNode.MAX_KEYS){
                return "internal " + pageNum + ": numKeys " + n + " out of range [" + minKeys + ", " + InternalNode.MAX_KEYS + "]";
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
                if(i < n){
                    Integer max = subtreeMax(child);
                    if(max != null && max > InternalNode.key(page, i)){
                        return "internal " + pageNum + ": child " + i + " max " + max + " > key(" + i + ") " + InternalNode.key(page, i);
                    }
                }
                if(i > 0){
                    Integer min = subtreeMin(child);
                    if(min != null && min <= InternalNode.key(page, i-1)){
                        return "internal " + pageNum + ": child " + i + " min " + min + " <= key(" + (i-1) + ") " + InternalNode.key(page, i-1);
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
