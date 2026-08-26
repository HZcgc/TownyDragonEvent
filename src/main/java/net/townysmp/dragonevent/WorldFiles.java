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
    private static final Set<String> SKIP = Set.of("uid.dat", "session.lock", OWNERSHIP_MARKER);

    private WorldFiles() {}

    static void copy(Path worldContainer, Path source, Path target) throws IOException {
        requireDirectChild(worldContainer, source, "template");
        requireDirectChild(worldContainer, target, "runtime");
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IOException("Template and runtime world must be different folders");
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
                if (!SKIP.contains(file.getFileName().toString())) {
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void delete(Path worldContainer, Path target) throws IOException {
        requireDirectChild(worldContainer, target, "runtime");
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
        requireDirectChild(worldContainer, target, "runtime");
        return Files.isDirectory(target) && !Files.isSymbolicLink(target)
                && Files.isRegularFile(target.resolve(OWNERSHIP_MARKER));
    }

    private static void requireDirectChild(Path worldContainer, Path folder, String label) throws IOException {
        Path root = worldContainer.toAbsolutePath().normalize();
        Path candidate = folder.toAbsolutePath().normalize();
        if (candidate.equals(root) || candidate.getParent() == null || !candidate.getParent().equals(root)) {
            throw new IOException("Unsafe " + label + " world path outside the world container: " + candidate);
        }
    }
}
