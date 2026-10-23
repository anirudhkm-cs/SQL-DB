package db;

public enum UsersColumn {
    ID("id", Type.INTEGER, 0),
    USERNAME("username", Type.TEXT, Row.USERNAME_MAX),
    EMAIL("email", Type.TEXT, Row.EMAIL_MAX);

    public enum Type { INTEGER, TEXT }

    public static final String TABLE_NAME = "users";

    public final String sqlName;
    public final Type type;

    public final int maxBytes;

    UsersColumn(String sqlName, Type type, int maxBytes) {
        this.sqlName = sqlName;
        this.type = type;
        this.maxBytes = maxBytes;
    }

    public static UsersColumn fromName(String name) {
        for (UsersColumn c : values()) {
            if (c.sqlName.equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    public Object valueIn(Row row) {
        return switch(this){
            case ID -> row.id();
            case USERNAME -> row.username();
            case EMAIL -> row.email();
        };
    }
}
