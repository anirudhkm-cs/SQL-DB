package db;

import java.util.List;

public record Schema(String tableName, List<Column> columns) {

    public Schema {
        columns = List.copyOf(columns);
    }

    public int columnCount() {
        return columns.size();
    }

    public Column column(int i) {
        return columns.get(i);
    }

    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
