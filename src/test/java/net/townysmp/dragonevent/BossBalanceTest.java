package net.townysmp.dragonevent;

import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BossBalanceTest {
    @Test void automaticStartHasExactlyFivePercentPerSpecialMode() {
        var counts = new EnumMap<EventMode, Integer>(EventMode.class);
        for (int bucket = 0; bucket < 10000; bucket++)
            counts.merge(BossBalance.chooseMode((bucket + 0.5) / 10000.0, 0.05, 0.05), 1, Integer::sum);
        assertEquals(500, counts.get(EventMode.APRIL_FOOLS));
        assertEquals(500, counts.get(EventMode.FREDDIE));
        assertEquals(9000, counts.get(EventMode.NORMAL));
        assertEquals(EventMode.APRIL_FOOLS, BossBalance.chooseMode(0, 0.05, 0.05));
        assertEquals(EventMode.FREDDIE, BossBalance.chooseMode(0.05, 0.05, 0.05));
        assertEquals(EventMode.NORMAL, BossBalance.chooseMode(0.1, 0.05, 0.05));
    }

    @Test void disabledSpecialModesAndInvalidConfigRemainPredictable() {
        assertEquals(EventMode.NORMAL, BossBalance.chooseMode(0, 0, 0));
        assertEquals(EventMode.APRIL_FOOLS, BossBalance.chooseMode(0.999, 2, 1));
        assertEquals(EventMode.NORMAL, BossBalance.chooseMode(0.7, Double.NaN, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> BossBalance.chooseMode(1, .05, .05));
        assertThrows(IllegalArgumentException.class, () -> BossBalance.chooseMode(-.1, .05, .05));
        assertThrows(IllegalArgumentException.class, () -> BossBalance.chooseMode(Double.NaN, .05, .05));
    }

    @Test void everyBossScalesBeyondTheNativeHealthCapWithoutChangingDamageCredit() {
        for (int fighters = 1; fighters <= 200; fighters++) for (double mode : new double[] {1.0, 2.0}) {
            var health = BossBalance.difficulty(5000, 1500, 40000, fighters, mode, true, .05, 2.0);
            double expected = Math.min(40000, 5000 + (fighters - 1) * 1500) * mode;
            assertEquals(expected, health.effectiveHealth());
            assertTrue(health.physicalHealth() <= 1024);
            assertEquals(expected, health.physicalHealth() / health.incomingMultiplier(), 1e-6);
            assertEquals(120.0, (120.0 * health.incomingMultiplier()) / health.incomingMultiplier(), 1e-6);
            assertTrue(health.attackMultiplier() >= 1 && health.attackMultiplier() <= 2);
        }
        assertEquals(5000, BossBalance.difficulty(5000, 1500, 40000, 1, 1, true, .05, 2).effectiveHealth());
        assertEquals(10000, BossBalance.difficulty(5000, 1500, 40000, 1, 2, true, .05, 2).effectiveHealth());
    }

    @Test void scalingCanBeDisabledAndMalformedValuesCannotCreateAnImmortalBoss() {
        var fixed = BossBalance.difficulty(5000, 1500, 40000, 150, 2, false, .05, 2);
        assertEquals(10000, fixed.effectiveHealth());
        var malformed = BossBalance.difficulty(Double.NaN, Double.POSITIVE_INFINITY, -10, 0,
                Double.NaN, true, Double.NaN, Double.NaN);
        assertTrue(Double.isFinite(malformed.effectiveHealth()));
        assertTrue(malformed.incomingMultiplier() > 0 && malformed.incomingMultiplier() <= 1);
    }

    @Test void commandsRecognizeAllModesAndAliases() {
        assertEquals(EventMode.FREDDIE, EventMode.parse("freddie"));
        assertEquals(EventMode.FREDDIE, EventMode.parse("FREDDIE-THE-FROG"));
        assertEquals(EventMode.FREDDIE, EventMode.parse("frog"));
        assertEquals(EventMode.APRIL_FOOLS, EventMode.parse("april_fools"));
        assertEquals(EventMode.NORMAL, EventMode.parse("normal"));
        assertFalse(EventMode.NORMAL.isExplosiveBoss());
        assertTrue(EventMode.FREDDIE.isExplosiveBoss());
        assertTrue(EventMode.APRIL_FOOLS.isExplosiveBoss());
    }
}
