package db.record;

import java.util.List;


public record Row(List<Object> values) {

    public Row {
        values = List.copyOf(values);
    }

    public Object get(int column) {
        return values.get(column);
    }

    public int key() {
        return (Integer) values.get(0);
    }
}
