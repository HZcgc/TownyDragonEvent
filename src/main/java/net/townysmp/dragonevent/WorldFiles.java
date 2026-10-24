package net.townysmp.dragonevent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.HashSet;
import java.util.Set;

final class WorldFiles {
    private static final String OWNERSHIP_MARKER = ".townysmp-dragon-runtime";
    private static final Set<Path> SKIP;

    private WorldFiles() {
    }

    static void copy(Path worldContainer, final Path source, final Path target) throws IOException {
        WorldFiles.requireManagedWorldFolder(worldContainer, source, "template");
        WorldFiles.requireManagedWorldFolder(worldContainer, target, "runtime");
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new IOException("Template and runtime world must be different folders");
        }
        if (!source.toAbsolutePath().normalize().getParent().equals(target.toAbsolutePath().normalize().getParent())) {
            throw new IOException("Template and runtime world must use the same Paper world namespace");
        }
        if (!Files.isDirectory(source, new LinkOption[0])) {
            throw new IOException("Template world does not exist: " + String.valueOf(source));
        }
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Template world must not be a symbolic link: " + String.valueOf(source));
        }
        if (Files.exists(target, new LinkOption[0])) {
            if (!WorldFiles.isOwnedRuntime(worldContainer, target)) {
                throw new IOException("Runtime target already exists but is not owned by TownyDragonEvent: " + String.valueOf(target));
            }
            WorldFiles.delete(worldContainer, target);
        }
        Files.createDirectories(target, new FileAttribute[0]);
        Files.writeString(target.resolve(OWNERSHIP_MARKER), (CharSequence)"TownyDragonEvent disposable runtime world\n", new OpenOption[0]);
        Files.walkFileTree(source, (FileVisitor<? super Path>)new SimpleFileVisitor<Path>(){

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)), new FileAttribute[0]);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    throw new IOException("Template contents must not contain symbolic links: " + String.valueOf(file));
                }
                Path relative = source.relativize(file);
                if (!SKIP.contains(relative)) {
                    Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void delete(Path worldContainer, Path target) throws IOException {
        WorldFiles.requireManagedWorldFolder(worldContainer, target, "runtime");
        if (!Files.exists(target, new LinkOption[0])) {
            return;
        }
        if (!WorldFiles.isOwnedRuntime(worldContainer, target)) {
            throw new IOException("Refusing to delete a runtime folder without the ownership marker: " + String.valueOf(target));
        }
        Files.walkFileTree(target, (FileVisitor<? super Path>)new SimpleFileVisitor<Path>(){

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static boolean isOwnedRuntime(Path worldContainer, Path target) throws IOException {
        WorldFiles.requireManagedWorldFolder(worldContainer, target, "runtime");
        return Files.isDirectory(target, new LinkOption[0]) && !Files.isSymbolicLink(target) && Files.isRegularFile(target.resolve(OWNERSHIP_MARKER), LinkOption.NOFOLLOW_LINKS) && Files.readString(target.resolve(OWNERSHIP_MARKER)).equals("TownyDragonEvent disposable runtime world\n");
    }

    private static void requireManagedWorldFolder(Path worldContainer, Path folder, String label) throws IOException {
        boolean paperWorldFolder;
        Path root = worldContainer.toAbsolutePath().normalize();
        Path candidate = folder.toAbsolutePath().normalize();
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new IOException("Unsafe " + label + " world path outside the world container: " + String.valueOf(candidate));
        }
        for (Path current = candidate; current != null && !current.equals(root); current = current.getParent()) {
            if (!Files.isSymbolicLink(current)) continue;
            throw new IOException("Refusing a symbolic link in the " + label + " world path: " + String.valueOf(current));
        }
        Path relative = root.relativize(candidate);
        boolean legacyWorldFolder = relative.getNameCount() == 1;
        boolean bl = paperWorldFolder = relative.getNameCount() == 4 && relative.getName(1).toString().equals("dimensions") && WorldFiles.isSafeSegment(relative.getName(0)) && WorldFiles.isSafeSegment(relative.getName(2)) && WorldFiles.isSafeSegment(relative.getName(3));
        if (!legacyWorldFolder && !paperWorldFolder) {
            throw new IOException("Unsafe " + label + " world path; expected a direct world folder or <level-name>/dimensions/<namespace>/<key>: " + String.valueOf(candidate));
        }
    }

    static Path selectRuntime(Path container, Path configured) throws IOException {
        WorldFiles.requireManagedWorldFolder(container, configured, "runtime");
        if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS) || WorldFiles.isOwnedRuntime(container, configured)) {
            return configured;
        }
        for (int i = 1; i <= 100; ++i) {
            Path sibling = configured.resolveSibling(String.valueOf(configured.getFileName()) + "_tr" + i);
            WorldFiles.requireManagedWorldFolder(container, sibling, "runtime");
            if (Files.exists(sibling, LinkOption.NOFOLLOW_LINKS) && !WorldFiles.isOwnedRuntime(container, sibling)) continue;
            return sibling;
        }
        throw new IOException("No safe free runtime folder available next to " + String.valueOf(configured));
    }

    private static boolean isSafeSegment(Path segment) {
        return segment.toString().matches("[A-Za-z0-9._-]+");
    }

    static {
        Set<Path> set = Set.of(Path.of("uid.dat", new String[0]), Path.of("session.lock", new String[0]), Path.of(OWNERSHIP_MARKER, new String[0]), Path.of("data", "paper", "metadata.dat"), Path.of("data", "paper", "level_overrides.dat"), Path.of("data", "weather.dat"), Path.of("data", "world_clocks.dat"), Path.of("data", "chunk_tickets.dat"), Path.of("data", "raids.dat"));
        set = new HashSet<Path>(set);
        set.add(Path.of("data", "minecraft", "weather.dat"));
        set.add(Path.of("data", "minecraft", "world_clocks.dat"));
        set.add(Path.of("data", "minecraft", "chunk_tickets.dat"));
        set.add(Path.of("data", "minecraft", "raids.dat"));
        SKIP = Set.copyOf(set);
    }
}

