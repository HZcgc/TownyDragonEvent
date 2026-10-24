package net.townysmp.dragonevent;

/** Combat values independent of Minecraft's maximum-health attribute limit. */
final class BossBalance {
    private BossBalance() {}

    record Difficulty(double effectiveHealth, double physicalHealth,
                      double incomingMultiplier, double attackMultiplier) {}

    static Difficulty difficulty(double base, double perFighter, double maximum,
                                 int fighters, double modeMultiplier,
                                 boolean scaling, double attackStep, double maximumAttack) {
        base = finite(base, 5000.0, 1.0, 1_000_000.0);
        perFighter = finite(perFighter, 1500.0, 0.0, 1_000_000.0);
        maximum = Math.max(base, finite(maximum, 40000.0, 1.0, 10_000_000.0));
        modeMultiplier = finite(modeMultiplier, 1.0, 0.05, 20.0);
        int additional = Math.max(0, fighters - 1);
        double normal = scaling ? Math.min(maximum, base + additional * perFighter) : base;
        double effective = Math.max(1.0, normal * modeMultiplier);
        double physical = Math.min(1024.0, effective);
        double attack = scaling ? Math.min(finite(maximumAttack, 2.0, 1.0, 20.0),
                1.0 + additional * finite(attackStep, 0.05, 0.0, 5.0)) : 1.0;
        return new Difficulty(effective, physical, physical / effective, attack);
    }

    /** Called once for an automatic start; explicit modes never roll again. */
    static EventMode chooseMode(double roll, double foolsChance, double freddieChance) {
        if (!Double.isFinite(roll) || roll < 0.0 || roll >= 1.0)
            throw new IllegalArgumentException("Mode roll must be in [0, 1)");
        double fools = finite(foolsChance, 0.05, 0.0, 1.0);
        double freddie = Math.min(1.0 - fools, finite(freddieChance, 0.05, 0.0, 1.0));
        if (roll < fools) return EventMode.APRIL_FOOLS;
        if (roll < fools + freddie) return EventMode.FREDDIE;
        return EventMode.NORMAL;
    }

    static double finite(double value, double fallback, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, Double.isFinite(value) ? value : fallback));
    }
}
