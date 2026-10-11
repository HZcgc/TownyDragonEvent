package net.townysmp.dragonevent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        write(template, "data/paper/level_overrides.dat", "level settings");
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
        assertFalse(Files.exists(runtime.resolve("data/paper/level_overrides.dat")));
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

    @Test void unmarkedLegacyRuntimeIsPreservedAndNewSiblingSelected() throws Exception {
        Path old=temporaryDirectory.resolve("earth/dimensions/minecraft/dragonevent_end");
        write(old,"region/player-build.mca","valuable arena");
        Path selected=WorldFiles.selectRuntime(temporaryDirectory,old);
        assertEquals(old.resolveSibling("dragonevent_end_tr1"),selected);
        assertThrows(java.io.IOException.class,()->WorldFiles.delete(temporaryDirectory,old));
        assertEquals("valuable arena",Files.readString(old.resolve("region/player-build.mca")));
    }
    @Test void onlyMarkedCloneIsRemoved() throws Exception {
        Path source=temporaryDirectory.resolve("template"),runtime=temporaryDirectory.resolve("runtime");
        write(source,"region/arena.mca","arena");WorldFiles.copy(temporaryDirectory,source,runtime);
        WorldFiles.delete(temporaryDirectory,runtime);WorldFiles.delete(temporaryDirectory,runtime);
        assertFalse(Files.exists(runtime));assertTrue(Files.exists(source.resolve("region/arena.mca")));
    }
    @Test void markerMustHaveExactContents() throws Exception {
        Path runtime=temporaryDirectory.resolve("runtime");write(runtime,".townysmp-dragon-runtime","unrelated");
        assertFalse(WorldFiles.isOwnedRuntime(temporaryDirectory,runtime));
        assertThrows(java.io.IOException.class,()->WorldFiles.delete(temporaryDirectory,runtime));
    }
    @Test void traversalAndWorldContainerCannotBeDeleted() {
        assertThrows(java.io.IOException.class,()->WorldFiles.delete(temporaryDirectory,temporaryDirectory));
        assertThrows(java.io.IOException.class,()->WorldFiles.delete(temporaryDirectory,temporaryDirectory.resolve("../other-world")));
    }
    @Test void templateAndRuntimeCannotBeSame() throws Exception {
        Path template=temporaryDirectory.resolve("template");Files.createDirectories(template);
        assertThrows(java.io.IOException.class,()->WorldFiles.copy(temporaryDirectory,template,template));
    }
    @Test void linkedAncestorCannotRedirectDelete() throws Exception {
        Path real=temporaryDirectory.resolve("real");Files.createDirectories(real);
        Path link=temporaryDirectory.resolve("earth");Files.createSymbolicLink(link,real);
        assertThrows(java.io.IOException.class,()->WorldFiles.delete(temporaryDirectory,link.resolve("dimensions/minecraft/runtime")));
    }
    @Test void linkedTemplateFileIsNotCopied() throws Exception {
        Path template=temporaryDirectory.resolve("template");Files.createDirectories(template);
        Path valuable=temporaryDirectory.resolve("private-data");Files.writeString(valuable,"never copy");
        Files.createSymbolicLink(template.resolve("secret.dat"),valuable);
        assertThrows(java.io.IOException.class,()->WorldFiles.copy(temporaryDirectory,template,temporaryDirectory.resolve("runtime")));
        assertEquals("never copy",Files.readString(valuable));
    }
    @Test void managedSiblingCanBeReused() throws Exception {
        Path old=temporaryDirectory.resolve("runtime");write(old,"arena.dat","legacy");
        Path template=temporaryDirectory.resolve("template");write(template,"arena.dat","new");
        Path next=WorldFiles.selectRuntime(temporaryDirectory,old);WorldFiles.copy(temporaryDirectory,template,next);
        assertEquals(next,WorldFiles.selectRuntime(temporaryDirectory,old));
    }
}
