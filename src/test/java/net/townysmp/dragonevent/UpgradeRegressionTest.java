package net.townysmp.dragonevent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reproduces an upgrade of existing files using the actual packaged resources. */
final class UpgradeRegressionTest {
    @TempDir Path directory;

    private TownyDragonEventPlugin plugin(YamlConfiguration config) {
        TownyDragonEventPlugin plugin = mock(TownyDragonEventPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DragonUpgradeTest"));
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource(anyString())).thenAnswer(invocation ->
                getClass().getResourceAsStream("/" + invocation.getArgument(0, String.class)));
        return plugin;
    }

    private void migrate(TownyDragonEventPlugin plugin) throws Exception {
        var method = TownyDragonEventPlugin.class.getDeclaredMethod("migrateConfig");
        method.setAccessible(true);
        method.invoke(plugin);
    }

    @Test void freddieNamesAndTitlesWorkOnFirstLoadOfAnOldMessagesFile() throws Exception {
        Files.writeString(directory.resolve("messages.yml"), "prefix: '<gold>Custom Town </gold>'\n");
        Messages messages = new Messages(plugin(new YamlConfiguration()));
        var plain = PlainTextComponentSerializer.plainText();
        assertEquals("Freddie the Frog", plain.serialize(messages.getRaw("freddie-name", Map.of())));
        assertTrue(plain.serialize(messages.getRaw("dragon-name-freddie", Map.of())).endsWith("Freddie the Frog"));
        assertEquals("FREDDIE THE FROG", plain.serialize(messages.getRaw("freddie-fight-title", Map.of())));
        assertFalse(plain.serialize(messages.getRaw("fight-start-freddie", Map.of("health", "10000"))).contains("Missing message"));
        assertTrue(plain.serialize(messages.get("freddie-name")).startsWith("Custom Town "));
        assertEquals("<green><bold>Freddie the Frog</bold></green>",
                YamlConfiguration.loadConfiguration(directory.resolve("messages.yml").toFile()).getString("freddie-name"));
    }

    @Test void existingCustomNamesAndPrefixRemainIntact() throws Exception {
        Files.writeString(directory.resolve("messages.yml"), "prefix: '<gold>Towny </gold>'\nfreddie-name: '<green>Custom Freddie</green>'\n");
        Messages messages = new Messages(plugin(new YamlConfiguration()));
        assertEquals("Custom Freddie", PlainTextComponentSerializer.plainText().serialize(messages.getRaw("freddie-name", Map.of())));
        assertEquals("Towny Custom Freddie", PlainTextComponentSerializer.plainText().serialize(messages.get("freddie-name")));
    }

    @Test void missingPrefixUsesTheBundledPrefixImmediately() throws Exception {
        Files.writeString(directory.resolve("messages.yml"), "joined: '<green>Welcome</green>'\n");
        Messages messages = new Messages(plugin(new YamlConfiguration()));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(messages.get("joined")).startsWith("TOWNYREBORN"));
    }

    @Test void legacyTwentyMinuteFilesUpgradeIncludingVersionFour() throws Exception {
        for (int version : new int[]{0, 3, 4}) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("config-version", version);
            config.set("event.fight-time-limit-seconds", 1200);
            config.set("world.portal-location.x", 71);
            config.set("event.dragon-health", 7850.0);
            migrate(plugin(config));
            assertEquals(1800, config.getInt("event.fight-time-limit-seconds"));
            assertEquals(5, config.getInt("config-version"));
            assertEquals(71, config.getInt("world.portal-location.x"));
            assertEquals(7850.0, config.getDouble("event.dragon-health"));
        }
    }

    @Test void aMissingTimeLimitIsWrittenAsThirtyMinutes() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", 4);
        migrate(plugin(config));
        assertEquals(1800, config.getInt("event.fight-time-limit-seconds"));
    }

    @Test void customTimerValuesAreNotOverwrittenAndMigrationRunsOnce() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", 4);
        config.set("event.fight-time-limit-seconds", 2400);
        TownyDragonEventPlugin plugin = plugin(config);
        migrate(plugin);
        assertEquals(2400, config.getInt("event.fight-time-limit-seconds"));
        config.set("event.fight-time-limit-seconds", 1200);
        migrate(plugin);
        assertEquals(1200, config.getInt("event.fight-time-limit-seconds"));
    }
}
