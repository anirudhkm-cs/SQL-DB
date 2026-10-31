package db.exec;

import db.Database;
import db.record.Row;
import db.sql.Predicate;
import db.sql.Statement;
import db.storage.BTree;
import db.storage.Cursor;
import db.storage.Index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class Planner {

    private Planner() {}

    record Bounds(Object lo, Object hi, boolean empty) {
        static final Bounds ALL = new Bounds(null, null, false);

        boolean isBounded() { return lo != null || hi != null; }

        boolean isPoint() { return lo != null && hi != null && Predicate.compareValues(lo, hi) == 0; }

        @Override
        public String toString() {
            return "[" + (lo == null ? "-inf" : lo) + ", " + (hi == null ? "+inf" : hi) + "]";
        }
    }

    static List<Predicate> conjuncts(Predicate where) {
        if (where == null) return List.of();
        if (where instanceof Predicate.And and) return and.parts();
        return List.of(where);
    }

    static Bounds bounds(List<Predicate> conjuncts, int column) {
        Object lo = null, hi = null;
        for(Predicate p : conjuncts){
            if(!(p instanceof Predicate.Compare c) || c.column() != column) continue;
            Object v = c.value();
            switch (c.op()) {
                case EQ -> { lo = max(lo, v); hi = min(hi, v);}
                case GT, GE -> lo = max(lo, v);
                case LT, LE -> hi = min(hi, v);
                case NE -> {}
            }
        }
        boolean empty = lo != null && hi != null && Predicate.compareValues(lo, hi) > 0;
        return new Bounds(lo, hi, empty);
    }

    private static Object max(Object a, Object b) {
        return a == null || Predicate.compareValues(b, a) > 0 ? b : a;
    }

    private static Object min(Object a, Object b) {
        return a == null || Predicate.compareValues(b, a) < 0 ? b : a;
    }

    static List<Row> matchingRows(Statement s, Database db) throws IOException {
        return matchingRows(db.table(s.table()), s.where(), db);
    }

    static List<Row> matchingRows(BTree tree, Predicate where, Database db) throws IOException {
        List<Predicate> parts = conjuncts(where);

        Bounds key = bounds(parts, 0);
        List<Index> indexes = db.indexesOn(tree.name());
        List<Bounds> ib = new ArrayList<>();
        for(Index idx: indexes){
            ib.add(bounds(parts, idx.column));
        }

        boolean empty = key.empty();
        for(Bounds b: ib) empty |= b.empty();
        if(empty){
            Executor.setPlan("empty: the WHERE can't match any row");
            return new ArrayList<>();
        }

        String t = tree.name();
        String keyName = tree.schema().column(0).name();
        List<Row> candidates;
        Index idx;
        if(key.isPoint()){
            Executor.setPlan("key lookup on " + t);
            candidates = scanKeyRange(tree, key);
        } else if((idx = pick(indexes, ib, true)) != null){
            Executor.setPlan("index " + idx.name + " on " + t + "(" + columnName(tree, idx) + ")");
            candidates = fetch(tree, idx.lookup(ib.get(indexes.indexOf(idx)).lo()));
        } else if (key.isBounded()) {
            Executor.setPlan("key range on " + t + ": " + keyName + " in " + key);
            candidates = scanKeyRange(tree, key);
        } else if ((idx = pick(indexes, ib, false)) != null) {
            Bounds b = ib.get(indexes.indexOf(idx));
            Executor.setPlan("index range " + idx.name + " on " + t + ": " + columnName(tree, idx) + " in " + b);
            candidates = fetch(tree, idx.range(b.lo(), b.hi()));
        } else {
            Executor.setPlan("full scan of " + t);
            candidates = scanKeyRange(tree, Bounds.ALL);
        }

        if (where == null){
            return candidates;
        }

        List<Row> out = new ArrayList<>();
        for (Row row : candidates) if (where.test(row)) out.add(row);
        return out;
    }

    static List<Row> joinRows(Statement s, Database db) throws IOException {
        Statement.JoinSpec join = s.query().join();
        BTree left = db.table(s.table());
        BTree right = db.table(join.table());
        int width = left.schema().columnCount();

        List<Row> outer = matchingRows(left, restrict(s.where(), 0, width), db);
        String outerPlan = Executor.plan();

        int rc = join.rightColumn();
        Index idx = rc == 0 ? null : db.indexOn(right.name(), rc);
        List<Row> allRight = null;
        String innerPlan;
        if (rc == 0) {
            innerPlan = "key lookup on " + right.name();
        } else if (idx != null) {
            innerPlan = "index " + idx.name + " on " + right.name() + "(" + columnName(right, idx) + ")";
        } else {
            allRight = scanKeyRange(right, Bounds.ALL);
            innerPlan = "full scan of " + right.name() + " (once), compared with every outer row";
        }

        List<Row> out = new ArrayList<>();
        for (Row o : outer) {
            Object v = o.get(join.leftColumn());
            if (v == null) continue;   

            List<Row> inner;
            if (rc == 0) {
                if (!(v instanceof Integer)) continue;   
                inner = scanKeyRange(right, new Bounds(v, v, false));
            } else if (idx != null) {
                inner = fetch(right, idx.lookup(v));
            } else {
                inner = new ArrayList<>();
                for (Row r : allRight) {
                    Object w = r.get(rc);
                    if (w != null && Predicate.compareValues(w, v) == 0) inner.add(r);
                }
            }

            for (Row i : inner) {
                List<Object> vals = new ArrayList<>(o.values());
                vals.addAll(i.values());
                Row combined = new Row(vals);
                if (s.where() == null || s.where().test(combined)) out.add(combined);
            }
        }

        Executor.setPlan("join: " + outerPlan + "; then for each row: " + innerPlan);
        return out;
    }

    static Predicate restrict(Predicate where, int from, int to) {
        List<Predicate> keep = new ArrayList<>();
        for (Predicate p : conjuncts(where)) {
            if (p.usesOnly(from, to)) keep.add(p);
        }
        if (keep.isEmpty()) return null;
        return keep.size() == 1 ? keep.get(0) : new Predicate.And(keep);
    }

    private static Index pick(List<Index> indexes, List<Bounds> bounds, boolean point) {
        for (int i = 0; i < indexes.size(); i++) {
            if (point ? bounds.get(i).isPoint() : bounds.get(i).isBounded()) return indexes.get(i);
        }
        return null;
    }

    private static String columnName(BTree tree, Index idx) {
        return tree.schema().column(idx.column).name();
    }

    static List<Row> scanKeyRange(BTree tree, Bounds b) throws IOException {
        Long lo = b.lo() == null ? Long.MIN_VALUE : (Integer) b.lo();
        Long hi = b.hi() == null ? null : (long) (Integer) b.hi();

        List<Row> out = new ArrayList<>();
        Cursor c = Cursor.atOrAfter(tree, lo);
        while (!c.isEndOfTable()) {
            if(hi != null && c.key() > hi){
                break;
            }
            out.add(c.read());
            c.advance();
        }
        return out;
    }

    static List<Row> fetch(BTree tree, List<Integer> ids) throws IOException {
        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        List<Row> out = new ArrayList<>();
        for (int id : sorted) {
            Cursor c = tree.find(id);
            Row row = c.isEndOfTable() ? null : c.read();
            if (row == null || row.key() != id) {
                throw new IllegalStateException("index points at row " + id + ", which isn't in " + tree.name());
            }
            out.add(row);
        }
        return out;
    }
}
