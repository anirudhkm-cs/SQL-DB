package db;

import java.util.List;

final class ConsoleOutput implements Output {

    @Override
    public void row(List<Object> values) {
        System.out.println(Output.format(values));
    }

    @Override
    public void message(String line) {
        System.out.println(line);
    }
}
