package net.townysmp.dragonevent;

import java.util.Locale;

enum EventMode {
    NORMAL,
    APRIL_FOOLS;

    static EventMode parse(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "normal", "dragon" -> NORMAL;
            case "april_fools", "aprilfools", "april" -> APRIL_FOOLS;
            default -> null;
        };
    }

    String commandName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
