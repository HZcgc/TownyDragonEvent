package net.townysmp.dragonevent;

import java.util.Locale;

enum EventMode {
    NORMAL,
    APRIL_FOOLS,
    FREDDIE;

    static EventMode parse(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "normal", "dragon" -> NORMAL;
            case "april_fools", "aprilfools", "april" -> APRIL_FOOLS;
            case "freddie", "frog", "freddie_the_frog" -> FREDDIE;
            default -> null;
        };
    }

    String commandName() {
        return name().toLowerCase(Locale.ROOT);
    }

    boolean isExplosiveBoss() {
        return this == APRIL_FOOLS || this == FREDDIE;
    }
}

