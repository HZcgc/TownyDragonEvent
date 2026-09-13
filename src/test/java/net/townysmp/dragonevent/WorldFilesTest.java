package net.townysmp.dragonevent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldFilesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void paperCloneGetsFreshIdentityAndRuntimeSavedData() throws Exception {
        Path namespace = temporaryDirectory.resolve("world/dimensions/minecraft");
        Path template = namespace.resolve("dragonevent_template");
        Path runtime = namespace.resolve("dragonevent_end");

        write(template, "uid.dat", "template uuid");
        write(template, "session.lock", "lock");
        write(template, "data/paper/metadata.dat", "paper uuid");
        write(template, "data/weather.dat", "weather");
        write(template, "data/world_clocks.dat", "clock");
        write(template, "data/chunk_tickets.dat", "tickets");
        write(template, "data/raids.dat", "raids");
        write(template, "data/paper/pdc.dat", "persistent data");
        write(template, "data/end_dragon_fight.dat", "dragon state");
        write(template, "region/r.0.0.mca", "arena chunks");

        WorldFiles.copy(temporaryDirectory, template, runtime);

        assertTrue(WorldFiles.isOwnedRuntime(temporaryDirectory, runtime));
        assertFalse(Files.exists(runtime.resolve("uid.dat")));
        assertFalse(Files.exists(runtime.resolve("session.lock")));
        assertFalse(Files.exists(runtime.resolve("data/paper/metadata.dat")));
        assertFalse(Files.exists(runtime.resolve("data/weather.dat")));
        assertFalse(Files.exists(runtime.resolve("data/world_clocks.dat")));
        assertFalse(Files.exists(runtime.resolve("data/chunk_tickets.dat")));
        assertFalse(Files.exists(runtime.resolve("data/raids.dat")));
        assertEquals("persistent data", Files.readString(runtime.resolve("data/paper/pdc.dat")));
        assertEquals("dragon state", Files.readString(runtime.resolve("data/end_dragon_fight.dat")));
        assertEquals("arena chunks", Files.readString(runtime.resolve("region/r.0.0.mca")));
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
