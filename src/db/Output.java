package db;

import java.util.List;
import java.util.StringJoiner;

interface Output {

    void row(List<Object> values);

    void message(String line);

    static String format(List<Object> values) {
        StringJoiner out = new StringJoiner(", ", "( ", " )");
        for (Object v : values) {
            out.add(String.valueOf(v));
        }
        return out.toString();
    }
}
