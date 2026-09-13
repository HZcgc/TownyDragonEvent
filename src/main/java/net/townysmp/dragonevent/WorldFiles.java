package net.townysmp.dragonevent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

final class WorldFiles {
    private static final String OWNERSHIP_MARKER = ".townysmp-dragon-runtime";
    private static final Set<Path> SKIP = Set.of(
            Path.of("uid.dat"),
            Path.of("session.lock"),
            Path.of(OWNERSHIP_MARKER),
            // Paper 26.1+ stores the Bukkit world UUID here. Copying it makes
            // Paper reject the runtime clone as a duplicate of the template.
            Path.of("data", "paper", "metadata.dat"),
            // Per-dimension level settings are runtime state as well. A stale
            // or partially written copy produces a ZLIB error while Paper is
            // loading the clone, so let Paper create a fresh file instead.
            Path.of("data", "paper", "level_overrides.dat"),
            // These files contain volatile server state rather than arena
            // contents. Paper safely recreates them for every runtime clone.
            Path.of("data", "weather.dat"),
            Path.of("data", "world_clocks.dat"),
            Path.of("data", "chunk_tickets.dat"),
            Path.of("data", "raids.dat")
    );

    private WorldFiles() {}

    static void copy(Path worldContainer, Path source, Path target) throws IOException {
        requireManagedWorldFolder(worldContainer, source, "template");
        requireManagedWorldFolder(worldContainer, target, "runtime");
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IOException("Template and runtime world must be different folders");
        }
        if (!source.toAbsolutePath().normalize().getParent()
                .equals(target.toAbsolutePath().normalize().getParent())) {
            throw new IOException("Template and runtime world must use the same Paper world namespace");
        }
        if (!Files.isDirectory(source)) throw new IOException("Template world does not exist: " + source);
        if (Files.isSymbolicLink(source)) throw new IOException("Template world must not be a symbolic link: " + source);
        if (Files.exists(target)) {
            if (!isOwnedRuntime(worldContainer, target)) {
                throw new IOException("Runtime target already exists but is not owned by TownyDragonEvent: " + target);
            }
            delete(worldContainer, target);
        }
        Files.createDirectories(target);
        Files.writeString(target.resolve(OWNERSHIP_MARKER), "TownyDragonEvent disposable runtime world\n");
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (!SKIP.contains(relative)) {
                    Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void delete(Path worldContainer, Path target) throws IOException {
        requireManagedWorldFolder(worldContainer, target, "runtime");
        if (!Files.exists(target)) return;
        if (!isOwnedRuntime(worldContainer, target)) {
            throw new IOException("Refusing to delete a runtime folder without the ownership marker: " + target);
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static boolean isOwnedRuntime(Path worldContainer, Path target) throws IOException {
        requireManagedWorldFolder(worldContainer, target, "runtime");
        return Files.isDirectory(target) && !Files.isSymbolicLink(target)
                && Files.isRegularFile(target.resolve(OWNERSHIP_MARKER));
    }

    private static void requireManagedWorldFolder(Path worldContainer, Path folder, String label) throws IOException {
        Path root = worldContainer.toAbsolutePath().normalize();
        Path candidate = folder.toAbsolutePath().normalize();
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new IOException("Unsafe " + label + " world path outside the world container: " + candidate);
        }

        Path relative = root.relativize(candidate);
        boolean legacyWorldFolder = relative.getNameCount() == 1;
        boolean paperWorldFolder = relative.getNameCount() == 4
                && relative.getName(1).toString().equals("dimensions")
                && isSafeSegment(relative.getName(0))
                && isSafeSegment(relative.getName(2))
                && isSafeSegment(relative.getName(3));
        if (!legacyWorldFolder && !paperWorldFolder) {
            throw new IOException("Unsafe " + label + " world path; expected a direct world folder or "
                    + "<level-name>/dimensions/<namespace>/<key>: " + candidate);
        }
    }

    private static boolean isSafeSegment(Path segment) {
        return segment.toString().matches("[A-Za-z0-9._-]+");
    }
}
