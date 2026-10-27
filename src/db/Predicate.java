package db;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

sealed interface Predicate permits Predicate.Compare, Predicate.And, Predicate.Or {

    boolean test(Row row);

    default boolean usesOnly(int from, int to) {
        if (this instanceof Compare c) return c.column() >= from && c.column() < to;
        List<Predicate> parts = this instanceof And a ? a.parts() : ((Or) this).parts();
        for (Predicate p : parts) {
            if (!p.usesOnly(from, to)) return false;
        }
        return true;
    }

    record Compare(int column, Ast.CompareOp op, Object value) implements Predicate {
        public boolean test(Row row) {
            int c = compareValues(row.get(column), value);
            return switch (op){
                case EQ -> c == 0;
                case NE -> c != 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
            };
        }
    }

    record And(List<Predicate> parts) implements Predicate {
        public boolean test(Row row) {
            for(Predicate p : parts){
                if(!p.test(row)){
                    return false;
                }
            }
            return true;
        }
    }

    record Or(List<Predicate> parts) implements Predicate {
        public boolean test(Row row) {
            for(Predicate p: parts){
                if(p.test(row)){
                    return true;
                }
            }
            return false;
        }
    }

    static int compareValues(Object a, Object b) {
        if (a instanceof Integer x) {
            return Integer.compare(x, (Integer) b);
        }
        return Arrays.compareUnsigned(((String) a).getBytes(StandardCharsets.UTF_8),
                ((String) b).getBytes(StandardCharsets.UTF_8));
    }
}
