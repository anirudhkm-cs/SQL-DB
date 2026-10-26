package db;

import java.util.List;

final class CrashPoints {

    private CrashPoints() {}

    static final List<String> POINTS = List.of(
            "mid-frame",        
            "before-commit",    
            "after-commit",     
            "mid-checkpoint",   
            "before-truncate"); 

    static final int EXIT_CODE = 42;

    private static String armed;

    static void arm(String point) {
        if (!POINTS.contains(point)) {
            throw new IllegalArgumentException("unknown crash point '" + point + "', expected one of " + POINTS);
        }
        armed = point;
    }

    static boolean isArmed(String point) {
        return point.equals(armed);
    }

    static void hit(String point) {
        if (isArmed(point)) {
            System.out.flush();
            Runtime.getRuntime().halt(EXIT_CODE);
        }
    }
}
