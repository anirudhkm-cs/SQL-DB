package db;

public record Row(int id, String username, String email) {
    public static final int USERNAME_MAX = 32;
    public static final int EMAIL_MAX = 255;
}