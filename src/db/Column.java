package db;

public record Column(String name, Type type, int size) {

    public enum Type { INTEGER, TEXT }

    public static Column integer(String name) {
        return new Column(name, Type.INTEGER, Integer.BYTES);
    }

    public static Column text(String name, int maxBytes) {
        return new Column(name, Type.TEXT, maxBytes);
    }
}
