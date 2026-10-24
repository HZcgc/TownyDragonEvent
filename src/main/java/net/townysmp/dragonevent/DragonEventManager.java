package net.townysmp.dragonevent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.townysmp.dragonevent.EventDisplays;
import net.townysmp.dragonevent.EventMode;
import net.townysmp.dragonevent.EventState;
import net.townysmp.dragonevent.Messages;
import net.townysmp.dragonevent.StatsManager;
import net.townysmp.dragonevent.TownyDragonEventPlugin;
import net.townysmp.dragonevent.WorldFiles;
import net.townysmp.dragonevent.api.DragonResultEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Frog;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

final class DragonEventManager
implements Listener {
    private static final int[][] CRYSTAL_OFFSETS = new int[][]{{3, 0}, {0, 3}, {-3, 0}, {0, -3}};
    private final TownyDragonEventPlugin plugin;
    private final Messages messages;
    private final StatsManager stats;
    private final Set<UUID> participants = new HashSet<UUID>();
    private final Set<UUID> lateParticipants = new HashSet<UUID>();
    private final Set<UUID> spectators = new HashSet<UUID>();
    private final Set<UUID> departedParticipants = new HashSet<UUID>();
    private final Map<UUID, Double> damage = new HashMap<UUID, Double>();
    private final Map<UUID, Integer> eventDeaths = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> crystalsDestroyed = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> explosionsTriggered = new HashMap<UUID, Integer>();
    private final Map<UUID, UUID> explosiveOwners = new HashMap<UUID, UUID>();
    private final Map<String, PendingExplosion> pendingBlockExplosions = new HashMap<String, PendingExplosion>();
    private final Set<UUID> countedCrystals = new HashSet<UUID>();
    private final Deque<UUID> primedTnt = new ArrayDeque<UUID>();
    private final Deque<UUID> aprilProjectiles = new ArrayDeque<UUID>();
    private final Map<UUID, GameMode> originalGameModes = new HashMap<UUID, GameMode>();
    private final Map<UUID, Boolean> originalAllowFlight = new HashMap<UUID, Boolean>();
    private final Map<UUID, Boolean> originalFlying = new HashMap<UUID, Boolean>();
    private final Map<UUID, Long> voidRescueCooldown = new HashMap<UUID, Long>();
    private final Map<UUID, BukkitTask> disconnectGraceTasks = new HashMap<UUID, BukkitTask>();
    private final Map<UUID, CompletableFuture<Boolean>> activeTeleports = new ConcurrentHashMap<UUID, CompletableFuture<Boolean>>();
    private final List<Location> exitPortalBlocks = new ArrayList<Location>();
    private CompletableFuture<Void> worldIoChain = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> prepareFuture = CompletableFuture.completedFuture(null);
    private EventState state = EventState.IDLE;
    private World eventWorld;
    private String activeRuntimeName;
    private Path activeRuntimePath;
    private String resultEventId;
    private final Set<String> preservedRuntimeNames = new HashSet<String>();
    private final EventDisplays displays;
    private BukkitTask displayTask;
    private EnderDragon dragon;
    private LivingEntity aprilBoss;
    private EventMode eventMode = EventMode.NORMAL;
    private BukkitTask ticker;
    private BukkitTask finishTask;
    private BukkitTask resetTask;
    private BukkitTask fightTimeoutTask;
    private BukkitTask fightClockTask;
    private BukkitTask closingCountdownTask;
    private BukkitTask spectatorGuardTask;
    private BukkitTask aprilVisualTask;
    private BukkitTask victoryFireworkTask;
    private int secondsLeft;
    private int fightSecondsLeft;
    private int closingSecondsLeft;
    private int aprilBarrageTicks;
    private int aprilAmbientSoundTicks;
    private long lastAprilHurtSoundTick = -1L;
    private double effectiveDragonHealth;
    private double incomingDamageMultiplier = 1.0;
    private double dragonAttackMultiplier = 1.0;
    private Location lastDragonLocation;
    private int dragonStationarySeconds;
    private boolean spawningScaledProjectile;
    private boolean statisticsRecorded;
    private boolean enraged;
    private boolean shuttingDown;
    private boolean resetDeleteQueued;
    private boolean recoveryBlocked;
    private long lifecycleGeneration;
    private int difficultyFighterCount;

    DragonEventManager(TownyDragonEventPlugin plugin, Messages messages, StatsManager stats) {
        this.plugin = plugin;
        this.messages = messages;
        this.stats = stats;
        this.displays = new EventDisplays(plugin);
    }

    EventState state() {
        return this.state;
    }

    int playerCount() {
        return this.participants.size();
    }

    int secondsRemaining() {
        return Math.max(0, this.secondsLeft);
    }

    String timeRemaining() {
        return DragonEventManager.formatTime(this.secondsRemaining());
    }

    boolean isRegistered(UUID uuid) {
        return this.participants.contains(uuid);
    }

    double currentDamage(UUID uuid) {
        return this.damage.getOrDefault(uuid, 0.0);
    }

    int currentDeaths(UUID uuid) {
        return this.eventDeaths.getOrDefault(uuid, 0);
    }

    int currentCrystals(UUID uuid) {
        return this.crystalsDestroyed.getOrDefault(uuid, 0);
    }

    int currentExplosions(UUID uuid) {
        return this.explosionsTriggered.getOrDefault(uuid, 0);
    }

    boolean isSpectator(UUID uuid) {
        return this.spectators.contains(uuid);
    }

    boolean hasDeparted(UUID uuid) {
        return this.departedParticipants.contains(uuid);
    }

    int fightSecondsRemaining() {
        return Math.max(0, this.fightSecondsLeft);
    }

    String fightTimeRemaining() {
        return DragonEventManager.formatTime(this.fightSecondsRemaining());
    }

    int closingSecondsRemaining() {
        return Math.max(0, this.closingSecondsLeft);
    }

    String closingTimeRemaining() {
        return DragonEventManager.formatTime(this.closingSecondsRemaining());
    }

    EventMode eventMode() {
        return this.eventMode;
    }

    int fighterCount() {
        return this.activeFighterCount();
    }

    int spectatorCount() {
        return this.activeSpectatorCount();
    }

    void setExitLocation(Player player) {
        Location location = player.getLocation();
        this.plugin.getConfig().set("world.exit-location.world", location.getWorld().getName());
        this.plugin.getConfig().set("world.exit-location.x", location.getX());
        this.plugin.getConfig().set("world.exit-location.y", location.getY());
        this.plugin.getConfig().set("world.exit-location.z", location.getZ());
        this.plugin.getConfig().set("world.exit-location.yaw", Float.valueOf(location.getYaw()));
        this.plugin.getConfig().set("world.exit-location.pitch", Float.valueOf(location.getPitch()));
        this.plugin.saveConfig();
        this.messages.send(player, "exit-location-set");
    }

    void setArenaSpawn(Player player) {
        if (!this.isTemplateWorld(player)) {
            return;
        }
        Location location = player.getLocation();
        this.plugin.getConfig().set("world.join-location.x", location.getX());
        this.plugin.getConfig().set("world.join-location.y", location.getY());
        this.plugin.getConfig().set("world.join-location.z", location.getZ());
        this.plugin.getConfig().set("world.join-location.yaw", Float.valueOf(location.getYaw()));
        this.plugin.getConfig().set("world.join-location.pitch", Float.valueOf(location.getPitch()));
        this.plugin.saveConfig();
        this.messages.send(player, "arena-spawn-set", this.locationReplacements(location));
    }

    void setPortalLocation(Player player) {
        if (!this.isTemplateWorld(player)) {
            return;
        }
        Location location = this.detectPortalCenter(player);
        this.plugin.getConfig().set("world.portal-location.set", true);
        this.plugin.getConfig().set("world.portal-location.x", location.getBlockX());
        this.plugin.getConfig().set("world.portal-location.y", location.getBlockY());
        this.plugin.getConfig().set("world.portal-location.z", location.getBlockZ());
        this.plugin.saveConfig();
        this.messages.send(player, "portal-location-set", this.locationReplacements(location));
        if (Math.abs(location.getBlockX()) > 16 || Math.abs(location.getBlockZ()) > 16) {
            this.messages.send(player, "portal-origin-warning");
        }
    }

    private boolean isTemplateWorld(Player player) {
        String templateWorld = this.resolveTemplateWorldName();
        if (player.getWorld().getName().equals(templateWorld)) {
            return true;
        }
        this.messages.send(player, "template-world-required", Map.of("world", templateWorld));
        return false;
    }

    private String resolveTemplateWorldName() {
        String configuredTemplate = this.plugin.getConfig().getString("world.template-folder", "").trim();
        if (!configuredTemplate.isEmpty()) {
            return configuredTemplate;
        }
        String automaticName = this.plugin.getConfig().getString("world.auto-template-name", "dragonevent_template").trim();
        if (!automaticName.isEmpty()) {
            try {
                Path automaticFolder = this.safeWorldPath(automaticName);
                if (Bukkit.getWorld(automaticName) != null || Files.isDirectory(automaticFolder, new LinkOption[0])) {
                    return automaticName;
                }
            }
            catch (IllegalArgumentException exception) {
                this.plugin.getLogger().warning("Could not resolve automatic Dragon template world: " + exception.getMessage());
            }
        }
        return this.plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end").trim();
    }

    private boolean usesVanillaTemplate() {
        return this.resolveTemplateWorldName().equals(this.plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end").trim());
    }

    int currentRank(UUID uuid) {
        if (this.spectators.contains(uuid) || this.departedParticipants.contains(uuid) || !this.damage.containsKey(uuid) || this.damage.getOrDefault(uuid, 0.0) <= 0.0) {
            return 0;
        }
        List<UUID> ranking = this.damage.entrySet().stream().filter(entry -> (Double)entry.getValue() > 0.0 && !this.spectators.contains(entry.getKey()) && !this.departedParticipants.contains(entry.getKey())).sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).map(Map.Entry::getKey).toList();
        int index = ranking.indexOf(uuid);
        return index < 0 ? 0 : index + 1;
    }

    String runtimeWorldName() {
        return this.plugin.getConfig().getString("world.runtime-name", "dragonevent_end").trim();
    }

    private Path worldContainerPath() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    private Path safeWorldPath(String worldName) {
        if (worldName == null || worldName.isBlank() || worldName.equals(".") || worldName.equals("..") || !worldName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Unsafe world folder name: " + worldName);
        }
        World loadedWorld = Bukkit.getWorld(worldName);
        if (loadedWorld != null) {
            Path loadedFolder = loadedWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (!loadedFolder.startsWith(this.worldContainerPath())) {
                throw new IllegalArgumentException("Loaded world folder is outside the server world container: " + String.valueOf(loadedFolder));
            }
            return loadedFolder;
        }
        Path root = this.worldContainerPath();
        Path namespaceDirectory = this.minecraftNamespaceDirectory();
        Path candidate = namespaceDirectory.resolve(worldName).toAbsolutePath().normalize();
        if (!candidate.startsWith(root) || candidate.getParent() == null || !candidate.getParent().equals(namespaceDirectory)) {
            throw new IllegalArgumentException("Could not safely resolve Paper world folder for: " + worldName);
        }
        return candidate;
    }

    private Path minecraftNamespaceDirectory() {
        Path root = this.worldContainerPath();
        for (World world : Bukkit.getWorlds()) {
            Path dimensionsDirectory;
            Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            Path namespaceDirectory = folder.getParent();
            Path path = dimensionsDirectory = namespaceDirectory == null ? null : namespaceDirectory.getParent();
            if (namespaceDirectory == null || dimensionsDirectory == null || !namespaceDirectory.getFileName().toString().equals("minecraft") || !dimensionsDirectory.getFileName().toString().equals("dimensions") || !namespaceDirectory.startsWith(root)) continue;
            return namespaceDirectory;
        }
        throw new IllegalArgumentException("Could not locate Paper's minecraft world namespace below " + String.valueOf(root));
    }

    private synchronized CompletableFuture<Void> queueWorldIo(WorldIoOperation operation) {
        CompletableFuture<Void> next = worldIoChain.handle((ignored, previousError) -> null)
                .thenRunAsync(() -> {
                    try { operation.run(); }
                    catch (IOException exception) { throw new CompletionException(exception); }
                });
        worldIoChain = next.handle((ignored, error) -> null);
        return next;
    }

    private void runOnServerThread(Runnable action) {
        if (this.shuttingDown || !this.plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask((Plugin)this.plugin, action);
    }

    synchronized boolean start(Integer customSeconds, EventMode requestedMode) {
        boolean reloadSource;
        Path runtime;
        Path template;
        if (this.state != EventState.IDLE) {
            return false;
        }
        Path container = this.worldContainerPath();
        String configuredTemplate = this.plugin.getConfig().getString("world.template-folder", "").trim();
        String sourceFolder = this.resolveTemplateWorldName();
        String runtimeName = this.runtimeWorldName();
        try {
            template = this.safeWorldPath(sourceFolder);
            runtime = this.safeWorldPath(runtimeName);
        }
        catch (IllegalArgumentException exception) {
            this.plugin.getLogger().severe(exception.getMessage());
            return false;
        }
        if (sourceFolder.equals(runtimeName) || template.equals(runtime)) {
            this.plugin.getLogger().severe("The event source world must differ from the runtime world.");
            return false;
        }
        try {
            Path selected = WorldFiles.selectRuntime(container, runtime);
            if (!selected.equals(runtime)) {
                this.preservedRuntimeNames.add(runtimeName);
                this.plugin.getLogger().warning("Preserving unmarked legacy runtime: " + String.valueOf(runtime) + ". Using a separate event clone: " + String.valueOf(selected));
                runtime = selected;
                runtimeName = selected.getFileName().toString();
            }
        }
        catch (IOException exception) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not choose a safe event runtime", exception);
            return false;
        }
        if (Bukkit.getWorld(runtimeName) != null) {
            this.plugin.getLogger().severe("The runtime world " + runtimeName + " is still loaded. Use /dragon stop and check the console.");
            return false;
        }
        World loadedSource = Bukkit.getWorld(sourceFolder);
        if (loadedSource != null) {
            template = loadedSource.getWorldFolder().toPath().toAbsolutePath().normalize();
        }
        if (loadedSource != null && loadedSource.getEnvironment() != World.Environment.THE_END) {
            this.plugin.getLogger().severe("The Dragon template world " + sourceFolder + " is not an End world.");
            return false;
        }
        this.eventMode = requestedMode != null ? requestedMode : BossBalance.chooseMode(
                ThreadLocalRandom.current().nextDouble(),
                plugin.getConfig().getDouble("event.mode-chances.april-fools", 0.05),
                plugin.getConfig().getDouble("event.mode-chances.freddie", 0.05));
        plugin.getLogger().info("Selected Dragon Event mode: " + eventMode.commandName());
        this.activeRuntimeName = runtimeName;
        this.activeRuntimePath = runtime;
        this.resultEventId = UUID.randomUUID().toString();
        try {
            this.saveRuntimeJournal();
        }
        catch (IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not checkpoint the event runtime", e);
            this.resetFailedPreparation();
            return false;
        }
        this.state = EventState.PREPARING;
        StaffAudit.transition(this, "dragon_events_scheduled", false, "scheduled");
        long operation = ++this.lifecycleGeneration;
        this.secondsLeft = customSeconds == null ? this.plugin.getConfig().getInt("event.countdown-seconds", 10800) : Math.max(10, customSeconds);
        Bukkit.broadcast(this.messages.get("preparing"));
        if (configuredTemplate.isEmpty() && sourceFolder.equals(this.plugin.getConfig().getString("world.auto-template-name", "dragonevent_template").trim())) {
            this.plugin.getLogger().info("Using automatically detected Dragon template world: " + sourceFolder);
        }
        boolean bl = reloadSource = loadedSource != null;
        if (loadedSource != null) {
            if (!loadedSource.getPlayers().isEmpty()) {
                this.plugin.getLogger().warning("Cannot clone source world " + sourceFolder + " while players are inside it.");
                this.resetFailedPreparation();
                return false;
            }
            loadedSource.save();
            if (!Bukkit.unloadWorld(loadedSource, true)) {
                this.plugin.getLogger().severe("Could not safely unload source world " + sourceFolder + " for cloning.");
                this.resetFailedPreparation();
                return false;
            }
        }
        Path finalTemplate = template;
        Path finalRuntime = runtime;
        this.prepareFuture = this.queueWorldIo(() -> WorldFiles.copy(container, finalTemplate, finalRuntime));
        this.prepareFuture.whenComplete((ignored, error) -> this.runOnServerThread(() -> {
            if (reloadSource && Bukkit.getWorld(sourceFolder) == null) {
                try {
                    new WorldCreator(sourceFolder).environment(World.Environment.THE_END).createWorld();
                }
                catch (RuntimeException e) {
                    this.plugin.getLogger().log(Level.SEVERE, "Could not reload the source world; its files were preserved", e);
                }
            }
            if (operation != this.lifecycleGeneration || this.state != EventState.PREPARING) {
                return;
            }
            if (error != null) {
                this.plugin.getLogger().log(Level.SEVERE, "Could not prepare the event world", (Throwable)error);
                this.evacuateAndReset();
                return;
            }
            this.loadWorldAndStartCountdown();
        }));
        return true;
    }

    private void resetFailedPreparation() {
        this.state = EventState.IDLE;
        this.eventMode = EventMode.NORMAL;
        this.activeRuntimeName = null;
        this.activeRuntimePath = null;
        this.deleteRuntimeJournal();
        this.secondsLeft = 0;
    }

    private void loadWorldAndStartCountdown() {
        boolean openedLobby;
        if (this.state != EventState.PREPARING) {
            return;
        }
        String runtimeName = this.activeRuntimeName == null ? this.runtimeWorldName() : this.activeRuntimeName;
        try {
            this.eventWorld = new WorldCreator(runtimeName).environment(World.Environment.THE_END).generateStructures(false).createWorld();
        }
        catch (RuntimeException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not load the prepared Dragon clone", e);
            this.eventWorld = null;
            this.evacuateAndReset();
            return;
        }
        if (this.eventWorld == null) {
            this.plugin.getLogger().severe("Paper could not load event world " + runtimeName);
            this.evacuateAndReset();
            return;
        }
        Path actualFolder = this.eventWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (this.eventWorld.getEnvironment() != World.Environment.THE_END || !actualFolder.equals(this.activeRuntimePath)) {
            this.plugin.getLogger().severe("Paper loaded a different runtime folder than the prepared clone. Preserving that folder: " + String.valueOf(actualFolder));
            this.preservedRuntimeNames.add(runtimeName);
            this.eventWorld = null;
            this.evacuateAndReset();
            return;
        }
        try {
            if (!WorldFiles.isOwnedRuntime(this.worldContainerPath(), actualFolder)) {
                throw new IOException("The loaded event clone lost its ownership marker");
            }
        }
        catch (IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Runtime ownership check failed", e);
            this.evacuateAndReset();
            return;
        }
        this.eventWorld.setAutoSave(false);
        this.eventWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        this.eventWorld.setGameRule(GameRule.LOCATOR_BAR, this.plugin.getConfig().getBoolean("world.locator-bar-enabled", false));
        this.eventWorld.setGameRule(GameRule.MOB_GRIEFING, this.plugin.getConfig().getBoolean("event.arena-destruction.dragon-griefing", true));
        this.eventWorld.setGameRule(GameRule.KEEP_INVENTORY, this.plugin.getConfig().getBoolean("event.death.keep-inventory", true));
        this.eventWorld.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, this.plugin.getConfig().getBoolean("event.death.immediate-respawn", true));
        this.eventWorld.setGameRule(GameRule.SHOW_DEATH_MESSAGES, true);
        this.configureWorldBorder();
        this.startSpectatorGuard();
        this.removeEventEntities();
        this.closeExitPortal();
        int joinSeconds = this.plugin.getConfig().getInt("event.join-seconds", 300);
        this.state = this.secondsLeft <= joinSeconds ? EventState.JOINING : EventState.SCHEDULED;
        boolean bl = openedLobby = this.state == EventState.JOINING;
        if (openedLobby) {
            this.messages.broadcast("lobby-open", this.playerCountReplacement(), "lobby");
        }
        if (!openedLobby || this.secondsLeft != joinSeconds) {
            this.announceCountdown();
        }
        --this.secondsLeft;
        this.ticker = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, this::eventTick, 20L, 20L);
        this.displayTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, this::updateDisplays, 1L, 20L);
    }

    private void eventTick() {
        if (this.state != EventState.SCHEDULED && this.state != EventState.JOINING) {
            return;
        }
        boolean openedLobby = false;
        if (this.state == EventState.SCHEDULED && this.secondsLeft <= this.plugin.getConfig().getInt("event.join-seconds", 300)) {
            this.state = EventState.JOINING;
            this.messages.broadcast("lobby-open", this.playerCountReplacement(), "lobby");
            this.teleportRegisteredPlayers();
            openedLobby = true;
        }
        if (this.secondsLeft <= 0) {
            this.beginRespawn();
            return;
        }
        if (!openedLobby) {
            this.announceCountdown();
        }
        --this.secondsLeft;
    }

    private void announceCountdown() {
        if (this.secondsLeft <= 0) {
            return;
        }
        if (this.plugin.getConfig().getIntegerList("event.announcement-seconds").contains(this.secondsLeft)) {
            this.messages.broadcast("announcement", Map.of("time", DragonEventManager.formatTime(this.secondsLeft), "players", Integer.toString(this.playerCount())), "announcement");
        } else if (this.secondsLeft == this.plugin.getConfig().getInt("event.last-chance-seconds", 180)) {
            this.messages.broadcast("last-chance", Map.of("time", DragonEventManager.formatTime(this.secondsLeft), "players", Integer.toString(this.playerCount())), "last-chance");
        } else if (this.plugin.getConfig().getIntegerList("event.final-countdown-seconds").contains(this.secondsLeft)) {
            this.messages.broadcast("countdown", Map.of("time", DragonEventManager.formatTime(this.secondsLeft)), "countdown");
        }
    }

    boolean join(Player player) {
        boolean accepting;
        boolean bl = accepting = this.state == EventState.SCHEDULED || this.state == EventState.JOINING || this.state == EventState.RESPAWNING || this.state == EventState.ACTIVE;
        if (!accepting || this.eventWorld == null) {
            this.messages.send(player, "not-open");
            return false;
        }
        if (this.participants.contains(player.getUniqueId())) {
            if (!(this.departedParticipants.contains(player.getUniqueId()) || this.state != EventState.JOINING && this.state != EventState.RESPAWNING && this.state != EventState.ACTIVE || player.getWorld().equals(this.eventWorld))) {
                this.teleportToEvent(player);
                this.messages.send(player, "joined");
                return true;
            }
            this.messages.send(player, "already-joined");
            return false;
        }
        this.cancelDisconnectGrace(player.getUniqueId());
        this.participants.add(player.getUniqueId());
        if (this.state == EventState.RESPAWNING || this.state == EventState.ACTIVE || this.state == EventState.JOINING && this.eventWorld != null && player.getWorld().equals(this.eventWorld)) {
            this.lateParticipants.add(player.getUniqueId());
        }
        this.damage.put(player.getUniqueId(), 0.0);
        this.eventDeaths.put(player.getUniqueId(), 0);
        this.crystalsDestroyed.put(player.getUniqueId(), 0);
        this.explosionsTriggered.put(player.getUniqueId(), 0);
        if (this.state == EventState.SCHEDULED) {
            this.messages.send(player, "registered");
        } else {
            this.teleportToEvent(player);
            this.messages.send(player, "joined");
        }
        return true;
    }

    boolean leave(Player player) {
        UUID uuid = player.getUniqueId();
        if (!this.participants.contains(uuid)) {
            return false;
        }
        this.cancelDisconnectGrace(uuid);
        if (this.state == EventState.RESPAWNING || this.state == EventState.ACTIVE) {
            this.departedParticipants.add(uuid);
            this.restoreGameMode(player);
            this.runFallback(player);
            this.messages.send(player, "left-active");
            Bukkit.getScheduler().runTask((Plugin)this.plugin, this::checkForBattleLoss);
            return true;
        }
        this.participants.remove(uuid);
        this.lateParticipants.remove(uuid);
        this.spectators.remove(uuid);
        this.damage.remove(uuid);
        this.eventDeaths.remove(uuid);
        this.crystalsDestroyed.remove(uuid);
        this.explosionsTriggered.remove(uuid);
        this.restoreGameMode(player);
        this.runFallback(player);
        this.messages.send(player, "left");
        return true;
    }

    private void teleportRegisteredPlayers() {
        for (UUID uuid : this.participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            this.teleportToEvent(player);
        }
    }

    private void teleportToEvent(Player player) {
        if (this.eventWorld == null || !player.isOnline() || this.departedParticipants.contains(player.getUniqueId())) {
            return;
        }
        this.prepareParticipantForArena(player);
        this.hideAprilDragonFrom(player);
        if (player.getWorld().equals(this.eventWorld)) {
            this.hideAprilDragonFrom(player);
            if (this.state == EventState.ACTIVE) {
                this.rescaleDifficultyForLateFighters();
            }
            return;
        }
        this.trackedTeleport(player, this.safeJoinLocation()).whenComplete((success, error) -> this.runOnServerThread(() -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                this.restoreGameMode(player);
                if (player.isOnline()) {
                    this.messages.send(player, "teleport-failed");
                }
                return;
            }
            if (!this.participants.contains(player.getUniqueId()) || this.departedParticipants.contains(player.getUniqueId()) || this.state == EventState.RESETTING || this.state == EventState.IDLE || this.state == EventState.FINISHING) {
                this.returnPlayer(player);
                return;
            }
            this.hideAprilDragonFrom(player);
            if (this.state == EventState.ACTIVE) {
                this.rescaleDifficultyForLateFighters();
            }
        }));
    }

    private void prepareParticipantForArena(Player player) {
        UUID uuid = player.getUniqueId();
        this.originalGameModes.putIfAbsent(uuid, player.getGameMode());
        this.originalAllowFlight.putIfAbsent(uuid, player.getAllowFlight());
        this.originalFlying.putIfAbsent(uuid, player.isFlying());
        player.addScoreboardTag("townysmp_dragon_participant");
        if (this.spectators.contains(uuid)) {
            player.addScoreboardTag("townysmp_dragon_spectator");
            player.setGameMode(GameMode.SPECTATOR);
            player.setAllowFlight(true);
            player.setFlying(true);
            return;
        }
        if (this.plugin.getConfig().getBoolean("event.player-state.force-survival", true)) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        if (this.plugin.getConfig().getBoolean("event.player-state.disable-flight", true)) {
            if (player.isFlying()) {
                player.setFlying(false);
            }
            player.setAllowFlight(false);
        }
    }

    private void beginRespawn() {
        Location portal;
        this.cancelTicker();
        int minimum = this.plugin.getConfig().getInt("event.minimum-players", 1);
        if (this.activeFighterCount() < minimum) {
            Bukkit.broadcast(this.messages.get("not-enough"));
            this.finish(false);
            return;
        }
        DragonBattle battle = this.eventWorld.getEnderDragonBattle();
        if (battle == null) {
            this.plugin.getLogger().severe("The event world has no Ender Dragon battle state.");
            this.finish(false);
            return;
        }
        battle.setPreviouslyKilled(true);
        boolean configuredPortal = this.plugin.getConfig().getBoolean("world.portal-location.set", false);
        if (configuredPortal) {
            portal = this.portalLocation(battle);
            if (portal == null || !this.forceBattlePortalLocation(battle, portal)) {
                this.plugin.getLogger().severe("Could not apply the configured Dragon portal center to Paper's DragonBattle.");
                this.finish(false);
                return;
            }
        } else {
            if (battle.getEndPortalLocation() == null) {
                battle.generateEndPortal(false);
            }
            portal = battle.getEndPortalLocation();
        }
        if (portal == null) {
            this.plugin.getLogger().severe("No Dragon portal center is available. Use /dragon setportal in the template map.");
            this.finish(false);
            return;
        }
        this.state = EventState.RESPAWNING;
        this.messages.broadcast("respawn-start", Map.of(), "respawn");
        Location center = portal.clone().add(0.5, 1.0, 0.5);
        ArrayList<EnderCrystal> respawnCrystals = new ArrayList<EnderCrystal>();
        for (int[] offset : CRYSTAL_OFFSETS) {
            EnderCrystal crystal = this.eventWorld.spawn(center.clone().add(offset[0], 0.0, offset[1]), EnderCrystal.class);
            crystal.setShowingBottom(false);
            crystal.addScoreboardTag("townysmp_dragon_respawn");
            respawnCrystals.add(crystal);
        }
        Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            if (!battle.initiateRespawn(respawnCrystals)) {
                this.plugin.getLogger().severe("Paper rejected the Dragon respawn sequence. Verify /dragon setportal and the template world.");
                this.finish(false);
            }
        }, 2L);
        this.ticker = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, this::watchRespawn, 20L, 20L);
    }

    private boolean forceBattlePortalLocation(DragonBattle battle, Location portal) {
        try {
            Field handleField = this.findField(battle.getClass(), "handle");
            handleField.setAccessible(true);
            Object handle = handleField.get(battle);
            Field locationField = this.findField(handle.getClass(), "exitPortalLocation", "portalLocation");
            locationField.setAccessible(true);
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object blockPos = blockPosClass.getConstructor(Integer.TYPE, Integer.TYPE, Integer.TYPE).newInstance(portal.getBlockX(), portal.getBlockY(), portal.getBlockZ());
            locationField.set(handle, blockPos);
            Location applied = battle.getEndPortalLocation();
            return applied != null && applied.getBlockX() == portal.getBlockX() && applied.getBlockY() == portal.getBlockY() && applied.getBlockZ() == portal.getBlockZ();
        }
        catch (ReflectiveOperationException | RuntimeException exception) {
            this.plugin.getLogger().log(Level.SEVERE, "Paper 26.2 Dragon portal location hook failed", exception);
            return false;
        }
    }

    private Field findField(Class<?> type, String ... names) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    return current.getDeclaredField(name);
                }
                catch (NoSuchFieldException noSuchFieldException) {
                }
            }
        }
        throw new NoSuchFieldException(String.join((CharSequence)"/", names));
    }

    private void watchRespawn() {
        if (this.state != EventState.RESPAWNING) {
            return;
        }
        this.sealExitPortal();
        Iterator<EnderDragon> iterator = this.eventWorld.getEntitiesByClass(EnderDragon.class).iterator();
        if (iterator.hasNext()) {
            EnderDragon found = iterator.next();
            this.activateDragon(found);
            return;
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity;
        if (this.eventWorld == null || !event.getLocation().getWorld().equals(this.eventWorld)) {
            return;
        }
        if (this.state == EventState.RESPAWNING && (entity = event.getEntity()) instanceof EnderDragon) {
            EnderDragon spawned = (EnderDragon)entity;
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.activateDragon(spawned));
        }
        if (this.state == EventState.ACTIVE && (entity = event.getEntity()) instanceof TNTPrimed) {
            TNTPrimed tnt = (TNTPrimed)entity;
            Player owner = this.resolvePlayer(tnt);
            if (owner != null && this.participants.contains(owner.getUniqueId())) {
                this.explosiveOwners.put(tnt.getUniqueId(), owner.getUniqueId());
                tnt.addScoreboardTag("townysmp_player_tnt");
                tnt.setYield((float)this.boundedExplosionPower("event.arena-destruction.explosion-power.player-tnt", 4.0));
            }
            this.registerLimitedEntity(tnt, this.primedTnt, this.plugin.getConfig().getInt("event.arena-destruction.limits.maximum-primed-tnt", 50));
        }
    }

    private void activateDragon(EnderDragon spawned) {
        if (this.state != EventState.RESPAWNING) {
            return;
        }
        this.cancelTicker();
        this.dragon = spawned;
        this.difficultyFighterCount = Math.max(1, this.activeFighterCount());
        double health = this.configureDifficulty(this.difficultyFighterCount);
        AttributeInstance maxHealth = this.dragon.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
        }
        this.dragon.setHealth(health);
        this.enraged = false;
        this.state = EventState.ACTIVE;
        boolean aprilFools = this.eventMode.isExplosiveBoss();
        String nameKey = eventMode == EventMode.FREDDIE ? "dragon-name-freddie" : aprilFools ? "dragon-name-april" : "dragon-name";
        this.dragon.customName(this.messages.getRaw(nameKey, Map.of("health", String.format(Locale.US, "%.0f", this.effectiveDragonHealth))));
        if (aprilFools) {
            this.startAprilFoolsVisual();
        }
        this.eventWorld.setGameRule(GameRule.DO_MOB_SPAWNING, true);
        this.state = EventState.ACTIVE;
        this.lastDragonLocation = this.dragon.getLocation().clone();
        this.dragonStationarySeconds = 0;
        this.startFightClock();
        StaffAudit.transition(this, "dragon_fights_started", false, "fight_started");
        this.messages.broadcast(eventMode == EventMode.FREDDIE ? "fight-start-freddie" : aprilFools ? "fight-start-april" : "fight-start", Map.of("health", String.format(Locale.US, "%.0f", this.effectiveDragonHealth), "fighters", Integer.toString(this.activeFighterCount())), eventMode == EventMode.FREDDIE ? "freddie-fight" : aprilFools ? "april-fight" : "fight");
        this.checkForBattleLoss();
    }

    private double configureDifficulty(int fighterCount) {
        BossBalance.Difficulty difficulty = BossBalance.difficulty(
                plugin.getConfig().getDouble("event.dragon-health", 5000.0),
                plugin.getConfig().getDouble("event.difficulty-scaling.health-per-additional-fighter", 1500.0),
                plugin.getConfig().getDouble("event.difficulty-scaling.maximum-effective-health", 40000.0),
                fighterCount, modeHealthMultiplier(),
                plugin.getConfig().getBoolean("event.difficulty-scaling.enabled", true),
                plugin.getConfig().getDouble("event.difficulty-scaling.attack-multiplier-per-additional-fighter", 0.05),
                plugin.getConfig().getDouble("event.difficulty-scaling.maximum-attack-multiplier", 2.0));
        effectiveDragonHealth = difficulty.effectiveHealth();
        incomingDamageMultiplier = difficulty.incomingMultiplier();
        dragonAttackMultiplier = difficulty.attackMultiplier();
        return difficulty.physicalHealth();
    }

    private double modeHealthMultiplier() {
        return eventMode.isExplosiveBoss()
                ? BossBalance.finite(jokeDouble("april-fools.health-multiplier", 2.0), 2.0, 0.05, 20.0)
                : 1.0;
    }

    private String jokePath(String path) {
        return eventMode == EventMode.FREDDIE && path.startsWith("april-fools.")
                ? "freddie." + path.substring("april-fools.".length()) : path;
    }

    private double jokeDouble(String path, double fallback) {
        return plugin.getConfig().getDouble(jokePath(path), plugin.getConfig().getDouble(path, fallback));
    }

    private int jokeInt(String path, int fallback) {
        return plugin.getConfig().getInt(jokePath(path), plugin.getConfig().getInt(path, fallback));
    }

    private boolean jokeBoolean(String path, boolean fallback) {
        return plugin.getConfig().getBoolean(jokePath(path), plugin.getConfig().getBoolean(path, fallback));
    }

    private boolean jokeVoiceEnabled() {
        return eventMode == EventMode.FREDDIE
                ? plugin.getConfig().getBoolean("freddie.sounds.voice-enabled", true)
                : plugin.getConfig().getBoolean("april-fools.sounds.creeper-voice-enabled", false);
    }

    private void resetJokeAppearance() {
        if (aprilBoss instanceof Creeper creeper) creeper.setIgnited(false);
        if (aprilBoss != null) {
            aprilBoss.setFireTicks(0);
            aprilBoss.setVisualFire(false);
        }
    }

    private void rescaleDifficultyForLateFighters() {
        if (this.state != EventState.ACTIVE || this.dragon == null || !this.dragon.isValid()) {
            return;
        }
        int fighters = Math.max(1, this.activeFighterCount());
        if (fighters <= this.difficultyFighterCount) {
            return;
        }
        AttributeInstance maxHealth = this.dragon.getAttribute(Attribute.MAX_HEALTH);
        double oldMaximum = maxHealth == null ? Math.max(1.0, this.dragon.getHealth()) : Math.max(1.0, maxHealth.getValue());
        double healthRatio = Math.max(0.0, Math.min(1.0, this.dragon.getHealth() / oldMaximum));
        this.difficultyFighterCount = fighters;
        double newMaximum = this.configureDifficulty(fighters);
        if (maxHealth != null) {
            maxHealth.setBaseValue(newMaximum);
        }
        this.dragon.setHealth(Math.max(0.01, Math.min(newMaximum, newMaximum * healthRatio)));
        this.messages.broadcast("difficulty-updated", Map.of("health", String.format(Locale.US, "%.0f", this.effectiveDragonHealth), "fighters", Integer.toString(fighters)), "difficulty");
    }

    private void startAprilFoolsVisual() {
        this.cancelAprilVisual();
        this.aprilBarrageTicks = 0;
        this.aprilAmbientSoundTicks = 0;
        this.lastAprilHurtSoundTick = -1L;
        this.dragon.setInvisible(true);
        this.hideAprilDragonFromEveryone();
        this.spawnAprilCreeper();
        this.aprilVisualTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            if (!this.eventMode.isExplosiveBoss() || this.state != EventState.ACTIVE || this.dragon == null || !this.dragon.isValid()) {
                this.cancelAprilVisual();
                return;
            }
            if (this.aprilBoss == null || !this.aprilBoss.isValid() || this.aprilBoss.isDead()) {
                this.spawnAprilCreeper();
            }
            if (this.aprilBoss == null || !this.aprilBoss.isValid()) {
                return;
            }
            this.aprilBoss.teleport(this.aprilBossLocation());
            this.aprilBoss.setVelocity(new Vector());
            this.resetJokeAppearance();
            this.aprilBoss.setFireTicks(0);
            this.aprilBoss.setVisualFire(false);
            AttributeInstance maxHealth = this.aprilBoss.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null && this.aprilBoss.getHealth() < maxHealth.getValue()) {
                this.aprilBoss.setHealth(maxHealth.getValue());
            }
            this.tickAprilFoolsEffects();
        }, 1L, 1L);
    }

    private void spawnAprilCreeper() {
        if (!eventMode.isExplosiveBoss() || state != EventState.ACTIVE || eventWorld == null
                || dragon == null || !dragon.isValid()) return;
        if (aprilBoss != null && aprilBoss.isValid() && !aprilBoss.isDead()) return;
        Location spawn = aprilBossLocation();
        if (eventMode == EventMode.FREDDIE) {
            aprilBoss = eventWorld.spawn(spawn, Frog.class, frog -> {
                configureJokeProxy(frog);
                frog.setVariant(Frog.Variant.COLD);
                frog.addScoreboardTag("townyreborn_freddie_boss");
                frog.customName(messages.getRaw("freddie-name", Map.of()));
                AttributeInstance scale = frog.getAttribute(Attribute.SCALE);
                if (scale != null) scale.setBaseValue(BossBalance.finite(
                        plugin.getConfig().getDouble("freddie.frog-scale", 8.0), 8.0, 1.0, 16.0));
            });
        } else {
            aprilBoss = eventWorld.spawn(spawn, Creeper.class, creeper -> {
                configureJokeProxy(creeper);
                creeper.setPowered(true);
                creeper.setExplosionRadius(0);
                creeper.setIgnited(false);
                creeper.customName(messages.getRaw("april-creeper-name", Map.of()));
                AttributeInstance scale = creeper.getAttribute(Attribute.SCALE);
                if (scale != null) scale.setBaseValue(BossBalance.finite(
                        plugin.getConfig().getDouble("april-fools.creeper-scale", 6.0), 6.0, 1.0, 16.0));
            });
        }
    }

    private void configureJokeProxy(LivingEntity boss) {
        boss.setAI(false);
        boss.setGravity(false);
        boss.setSilent(true);
        boss.setPersistent(false);
        boss.setRemoveWhenFarAway(false);
        boss.setCollidable(false);
        boss.setFireTicks(0);
        boss.setVisualFire(false);
        boss.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
        boss.addScoreboardTag("townysmp_april_creeper");
        boss.setCustomNameVisible(true);
    }

    private void tickAprilFoolsEffects() {
        int ambientInterval;
        if (this.aprilBoss == null || !this.aprilBoss.isValid() || this.eventWorld == null) {
            return;
        }
        if (this.jokeVoiceEnabled() && this.jokeBoolean("april-fools.sounds.enabled", true) && ++this.aprilAmbientSoundTicks >= (ambientInterval = Math.max(20, this.jokeInt("april-fools.sounds.ambient.interval-ticks", 80)))) {
            this.aprilAmbientSoundTicks = 0;
            this.playAprilSound("april-fools.sounds.ambient", "minecraft:entity.creeper.hurt", 1.8f, 0.55f);
        }
        if (this.aprilProjectilesPausedAtPortal()) {
            this.aprilBarrageTicks = 0;
            return;
        }
        if (!this.jokeBoolean("april-fools.projectiles.automatic-barrage.enabled", true)) {
            return;
        }
        int barrageInterval = Math.max(10, this.jokeInt("april-fools.projectiles.automatic-barrage.interval-ticks", 50));
        if (enraged) barrageInterval = Math.min(barrageInterval,
                Math.max(10, plugin.getConfig().getInt("event.enrage.special-barrage-interval-ticks", 15)));
        if (++this.aprilBarrageTicks >= barrageInterval) {
            this.aprilBarrageTicks = 0;
            this.launchAprilBarrage();
        }
    }

    private void launchAprilBarrage() {
        if (this.eventWorld == null || this.aprilBoss == null || !this.aprilBoss.isValid() || this.aprilProjectilesPausedAtPortal()) {
            return;
        }
        List<Player> targets = this.participants.stream().filter(uuid -> !this.spectators.contains(uuid) && !this.departedParticipants.contains(uuid)).map(Bukkit::getPlayer).filter(player -> player != null && player.isOnline() && player.getWorld().equals(this.eventWorld)).toList();
        if (targets.isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Player target = targets.get(random.nextInt(targets.size()));
        Location source = this.aprilBoss.getLocation().clone().add(0.0, -1.0, 0.0);
        Vector direction = target.getEyeLocation().toVector().subtract(source.toVector());
        if (direction.lengthSquared() < 0.01) {
            direction = this.dragon.getLocation().getDirection();
        }
        direction.normalize();
        int amount = Math.max(1, Math.min(12, this.jokeInt("april-fools.projectiles.automatic-barrage.projectiles", 4)));
        double spread = Math.max(0.0, this.jokeDouble("april-fools.projectiles.automatic-barrage.spread-degrees", 18.0));
        this.playAprilSound("april-fools.sounds.attack", "minecraft:entity.creeper.primed", 2.4f, 0.65f);
        for (int index = 0; index < amount; ++index) {
            Vector shot = this.spreadDirection(direction, index, amount, spread);
            shot.setY(shot.getY() + random.nextDouble(-0.08, 0.1));
            if (shot.lengthSquared() < 0.01) {
                shot = direction.clone();
            }
            this.launchAprilProjectile(source, shot.normalize());
        }
    }

    private void playAprilSound(String path, String fallbackSound, float fallbackVolume, float fallbackPitch) {
        Location source;
        path = jokePath(path);
        if (this.eventWorld == null || !this.jokeVoiceEnabled() || !this.jokeBoolean("april-fools.sounds.enabled", true)) {
            return;
        }
        String sound = this.plugin.getConfig().getString(path + ".sound", fallbackSound);
        if (sound == null || sound.isBlank()) {
            return;
        }
        float volume = (float)Math.max(0.0, this.plugin.getConfig().getDouble(path + ".volume", fallbackVolume));
        float pitch = (float)Math.max(0.01, Math.min(2.0, this.plugin.getConfig().getDouble(path + ".pitch", fallbackPitch)));
        boolean global = this.jokeBoolean("april-fools.sounds.global-to-arena", true);
        Location location = source = this.aprilBoss != null && this.aprilBoss.isValid() ? this.aprilBoss.getLocation() : this.safeJoinLocation();
        if (!global) {
            this.eventWorld.playSound(source, sound, volume, pitch);
            return;
        }
        for (Player player : this.eventWorld.getPlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private void hideAprilDragonFrom(Player player) {
        if (!this.eventMode.isExplosiveBoss() || this.dragon == null || !this.dragon.isValid() || player == null || !player.isOnline()) {
            return;
        }
        player.hideEntity(this.plugin, this.dragon);
    }

    private void hideAprilDragonFromEveryone() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.hideAprilDragonFrom(player);
        }
    }

    private Location aprilBossLocation() {
        Location location = this.dragon.getLocation().clone().add(0.0, this.jokeDouble("april-fools.vertical-offset", -4.0), 0.0);
        location.setPitch(0.0f);
        return location;
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onSummoningCrystalDamage(EntityDamageEvent event) {
        EntityDamageByEntityEvent byEntity;
        Player player;
        EnderCrystal crystal;
        Entity entity;
        if (this.state != EventState.RESPAWNING || !((entity = event.getEntity()) instanceof EnderCrystal) || !(crystal = (EnderCrystal)entity).getScoreboardTags().contains("townysmp_dragon_respawn")) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent && (player = this.resolvePlayer((byEntity = (EntityDamageByEntityEvent)event).getDamager())) != null) {
            this.messages.send(player, "crystal-protected");
        }
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onScaledDragonDamage(EntityDamageEvent event) {
        if (this.state != EventState.ACTIVE || event.getEntity() != this.dragon) {
            return;
        }
        event.setDamage(event.getDamage() * this.incomingDamageMultiplier);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onAprilProjectileHitsCarrier(EntityDamageByEntityEvent event) {
        if (!this.eventMode.isExplosiveBoss() || this.state != EventState.ACTIVE || event.getEntity() != this.dragon || !this.isAprilProjectile(event.getDamager())) {
            return;
        }
        Vector flightVelocity = this.dragon.getVelocity().clone();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            if (this.eventMode.isExplosiveBoss() && this.state == EventState.ACTIVE && this.dragon != null && this.dragon.isValid()) {
                this.dragon.setVelocity(flightVelocity);
            }
        });
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onScaledBossAttack(EntityDamageByEntityEvent event) {
        Player player;
        Entity entity;
        if (!(this.state == EventState.ACTIVE && (entity = event.getEntity()) instanceof Player && this.participants.contains((player = (Player)entity).getUniqueId()) && this.isBossDamage(event.getDamager()))) {
            return;
        }
        double finaleMultiplier = enraged ? BossBalance.finite(
                plugin.getConfig().getDouble("event.enrage.attack-multiplier", 1.2), 1.2, 1.0, 3.0) : 1.0;
        event.setDamage(event.getDamage() * this.dragonAttackMultiplier * finaleMultiplier);
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onCrystalDestroyed(EntityDamageByEntityEvent event) {
        block5: {
            block4: {
                Entity entity;
                if (this.state != EventState.ACTIVE || !((entity = event.getEntity()) instanceof EnderCrystal)) break block4;
                EnderCrystal crystal = (EnderCrystal)entity;
                if (this.eventWorld != null && crystal.getWorld().equals(this.eventWorld) && this.countedCrystals.add(crystal.getUniqueId())) break block5;
            }
            return;
        }
        Player player = this.resolvePlayer(event.getDamager());
        if (player != null && this.participants.contains(player.getUniqueId())) {
            this.crystalsDestroyed.merge(player.getUniqueId(), 1, Integer::sum);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (this.state != EventState.ACTIVE || event.getEntity() != this.dragon) {
            return;
        }
        Player attacker = this.resolvePlayer(event.getDamager());
        if (attacker == null || !this.participants.contains(attacker.getUniqueId()) || this.spectators.contains(attacker.getUniqueId()) || this.departedParticipants.contains(attacker.getUniqueId())) {
            return;
        }
        double physicalDamage = Math.min(Math.max(0.0, event.getFinalDamage()), Math.max(0.0, this.dragon.getHealth()));
        double effectiveDamage = physicalDamage / Math.max(1.0E-6, this.incomingDamageMultiplier);
        this.damage.merge(attacker.getUniqueId(), effectiveDamage, Double::sum);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onAprilCreeperDamage(EntityDamageEvent event) {
        Player player;
        if (!this.eventMode.isExplosiveBoss() || this.state != EventState.ACTIVE || this.aprilBoss == null || event.getEntity() != this.aprilBoss) {
            return;
        }
        boolean previouslyCancelled = event.isCancelled();
        double forwardedDamage = Math.max(0.0, event.getFinalDamage());
        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent byEntity = (EntityDamageByEntityEvent)event;
            player = this.resolvePlayer(byEntity.getDamager());
        } else {
            player = null;
        }
        Player attacker = player;
        event.setCancelled(true);
        this.resetJokeAppearance();
        this.aprilBoss.setFireTicks(0);
        this.aprilBoss.setVisualFire(false);
        AttributeInstance maxHealth = this.aprilBoss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && this.aprilBoss.getHealth() < maxHealth.getValue()) {
            this.aprilBoss.setHealth(maxHealth.getValue());
        }
        long now = this.eventWorld == null ? 0L : this.eventWorld.getGameTime();
        int soundCooldown = Math.max(1, this.jokeInt("april-fools.sounds.hurt.cooldown-ticks", 6));
        if (this.lastAprilHurtSoundTick < 0L || now - this.lastAprilHurtSoundTick >= (long)soundCooldown) {
            this.lastAprilHurtSoundTick = now;
            this.playAprilSound("april-fools.sounds.hurt", "minecraft:entity.creeper.hurt", 1.6f, 0.75f);
        }
        if (previouslyCancelled || attacker == null || !this.participants.contains(attacker.getUniqueId())
                || this.spectators.contains(attacker.getUniqueId()) || this.departedParticipants.contains(attacker.getUniqueId()) || this.dragon == null || !this.dragon.isValid() || forwardedDamage <= 0.0) {
            return;
        }
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            if (this.state == EventState.ACTIVE && this.dragon != null && this.dragon.isValid()) {
                this.dragon.damage(forwardedDamage, attacker);
            }
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onAprilCreeperCombust(EntityCombustEvent event) {
        if (!this.eventMode.isExplosiveBoss() || this.state != EventState.ACTIVE || this.aprilBoss == null || event.getEntity() != this.aprilBoss) {
            return;
        }
        event.setCancelled(true);
        this.resetJokeAppearance();
        this.aprilBoss.setFireTicks(0);
        this.aprilBoss.setVisualFire(false);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onAprilCreeperPrime(ExplosionPrimeEvent event) {
        if (!this.eventMode.isExplosiveBoss() || this.state != EventState.ACTIVE || this.aprilBoss == null || event.getEntity() != this.aprilBoss) {
            return;
        }
        event.setCancelled(true);
        this.resetJokeAppearance();
        if (this.aprilBoss instanceof Creeper creeper) creeper.setExplosionRadius(0);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onDragonProjectile(ProjectileLaunchEvent event) {
        DragonFireball fireball;
        Projectile projectile;
        if (this.spawningScaledProjectile || this.state != EventState.ACTIVE || !((projectile = event.getEntity()) instanceof DragonFireball) || (fireball = (DragonFireball)projectile).getShooter() != this.dragon) {
            return;
        }
        if (this.eventMode.isExplosiveBoss() && this.aprilProjectilesPausedAtPortal()) {
            event.setCancelled(true);
            return;
        }
        Location source = fireball.getLocation().clone();
        Vector direction = fireball.getVelocity().clone();
        if (direction.lengthSquared() < 0.01) {
            direction = this.dragon.getLocation().getDirection();
        }
        direction.normalize();
        Vector finalDirection = direction;
        double originalSpeed = Math.max(0.8, fireball.getVelocity().length());
        int volley = this.projectileVolleySize();
        if (this.eventMode.isExplosiveBoss()) {
            int multiplier = Math.max(1, Math.min(8, this.jokeInt("april-fools.projectiles.dragon-shot-multiplier", 3)));
            int maximum = Math.max(1, Math.min(24, this.jokeInt("april-fools.projectiles.maximum-per-dragon-shot", 9)));
            volley = Math.min(maximum, volley * multiplier);
        }
        int finalVolley = volley;
        if (this.eventMode.isExplosiveBoss()) {
            event.setCancelled(true);
        }
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            int index;
            int n = index = this.eventMode.isExplosiveBoss() ? 0 : 1;
            while (index < finalVolley) {
                Vector spread;
                Vector vector = spread = this.eventMode.isExplosiveBoss() ? this.spreadDirection(finalDirection, index, finalVolley) : this.additionalSpreadDirection(finalDirection, index);
                if (this.eventMode.isExplosiveBoss()) {
                    this.launchAprilProjectile(source, spread);
                } else {
                    this.launchAdditionalDragonFireball(source, spread, originalSpeed);
                }
                ++index;
            }
        });
    }

    private int projectileVolleySize() {
        if (!this.plugin.getConfig().getBoolean("event.difficulty-scaling.enabled", true)) {
            return 1;
        }
        int every = Math.max(1, this.plugin.getConfig().getInt("event.difficulty-scaling.projectiles.additional-every-fighters", 8));
        int maximum = Math.max(1, this.plugin.getConfig().getInt("event.difficulty-scaling.projectiles.maximum-per-volley", 3));
        int finaleExtra = enraged ? Math.max(0, plugin.getConfig().getInt("event.enrage.additional-projectiles", 1)) : 0;
        return Math.min(maximum, 1 + Math.max(0, this.activeFighterCount() - 1) / every + finaleExtra);
    }

    private Vector spreadDirection(Vector direction, int index, int total) {
        double spreadDegrees = this.plugin.getConfig().getDouble("event.difficulty-scaling.projectiles.spread-degrees", 10.0);
        return this.spreadDirection(direction, index, total, spreadDegrees);
    }

    private Vector spreadDirection(Vector direction, int index, int total, double spreadDegrees) {
        if (total <= 1) {
            return direction.clone();
        }
        double spread = Math.toRadians(Math.max(0.0, spreadDegrees));
        double offset = ((double)index - (double)(total - 1) / 2.0) * spread;
        return direction.clone().rotateAroundY(offset).normalize();
    }

    private Vector additionalSpreadDirection(Vector direction, int index) {
        double spread = Math.toRadians(Math.max(0.0, this.plugin.getConfig().getDouble("event.difficulty-scaling.projectiles.spread-degrees", 10.0)));
        int step = (index + 1) / 2;
        double offset = (index % 2 == 1 ? -1.0 : 1.0) * (double)step * spread;
        return direction.clone().rotateAroundY(offset).normalize();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void launchAdditionalDragonFireball(Location source, Vector direction, double speed) {
        if (this.state != EventState.ACTIVE || this.eventWorld == null || this.dragon == null || !this.dragon.isValid()) {
            return;
        }
        this.spawningScaledProjectile = true;
        try {
            DragonFireball extra = this.eventWorld.spawn(source, DragonFireball.class);
            extra.addScoreboardTag("townysmp_scaled_dragon_projectile");
            extra.setShooter(this.dragon);
            extra.setDirection(direction);
            extra.setVelocity(direction.clone().multiply(speed));
        }
        finally {
            this.spawningScaledProjectile = false;
        }
    }

    private void launchAprilProjectile(Location source, Vector direction) {
        if (!this.eventMode.isExplosiveBoss() || this.state != EventState.ACTIVE || this.eventWorld == null || this.aprilProjectilesPausedAtPortal()) {
            return;
        }
        double speed = Math.max(0.2, this.jokeDouble("april-fools.projectiles.speed", 1.1));
        double tntChance = Math.max(0.0, Math.min(1.0, this.jokeDouble("april-fools.projectiles.tnt-chance", 0.5)));
        if (ThreadLocalRandom.current().nextDouble() < tntChance) {
            TNTPrimed tnt = this.eventWorld.spawn(source, TNTPrimed.class);
            tnt.setFuseTicks(Math.max(10, this.jokeInt("april-fools.projectiles.tnt-fuse-ticks", 45)));
            tnt.setYield((float)this.boundedExplosionPower("event.arena-destruction.explosion-power.april-tnt", 5.0));
            tnt.setVelocity(direction.clone().multiply(speed));
            tnt.addScoreboardTag("townysmp_april_projectile");
            this.registerLimitedEntity(tnt, this.primedTnt, this.plugin.getConfig().getInt("event.arena-destruction.limits.maximum-primed-tnt", 50));
            this.registerLimitedEntity(tnt, this.aprilProjectiles, this.plugin.getConfig().getInt("event.arena-destruction.limits.maximum-april-projectiles", 20));
            this.eventWorld.playSound(source, "minecraft:entity.creeper.primed", 1.0f, 0.8f);
            return;
        }
        LargeFireball ghastFireball = this.eventWorld.spawn(source, LargeFireball.class);
        ghastFireball.setShooter(this.aprilBoss != null ? this.aprilBoss : this.dragon);
        ghastFireball.setDirection(direction);
        ghastFireball.setVelocity(direction.clone().multiply(speed));
        ghastFireball.setYield((float)this.boundedExplosionPower("event.arena-destruction.explosion-power.april-fireball", this.jokeDouble("april-fools.projectiles.fireball-yield", 2.0)));
        ghastFireball.setIsIncendiary(false);
        ghastFireball.addScoreboardTag("townysmp_april_projectile");
        this.registerLimitedEntity(ghastFireball, this.aprilProjectiles, this.plugin.getConfig().getInt("event.arena-destruction.limits.maximum-april-projectiles", 20));
        this.eventWorld.playSound(source, "minecraft:entity.ghast.shoot", 1.0f, 0.8f);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onArenaEntityExplosion(EntityExplodeEvent event) {
        if (this.eventWorld == null || !event.getLocation().getWorld().equals(this.eventWorld)) {
            return;
        }
        Player owner = this.resolvePlayer(event.getEntity());
        if (this.state == EventState.ACTIVE && owner != null && this.participants.contains(owner.getUniqueId())) {
            this.explosionsTriggered.merge(owner.getUniqueId(), 1, Integer::sum);
        }
        this.primedTnt.remove(event.getEntity().getUniqueId());
        this.aprilProjectiles.remove(event.getEntity().getUniqueId());
        this.explosiveOwners.remove(event.getEntity().getUniqueId());
        if (event.getEntity().getScoreboardTags().contains("townysmp_april_finale")) {
            event.blockList().clear();
            event.setYield(0.0f);
            return;
        }
        if (!this.isArenaDestructionAllowed(event.getLocation().getWorld())) {
            event.blockList().clear();
            event.setYield(0.0f);
            return;
        }
        if (this.plugin.getConfig().getBoolean("event.arena-destruction.force-explosions", true)) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onArenaBlockExplosion(BlockExplodeEvent event) {
        if (this.eventWorld == null || !event.getBlock().getWorld().equals(this.eventWorld)) {
            return;
        }
        PendingExplosion pending = this.pendingBlockExplosions.remove(this.blockKey(event.getBlock()));
        if (this.state == EventState.ACTIVE && pending != null && pending.expiresAt() >= System.currentTimeMillis() && this.participants.contains(pending.owner())) {
            this.explosionsTriggered.merge(pending.owner(), 1, Integer::sum);
        }
        if (this.isArenaDestructionAllowed(event.getBlock().getWorld()) && this.plugin.getConfig().getBoolean("event.arena-destruction.force-explosions", true)) {
            event.setCancelled(false);
        } else if (!this.isArenaDestructionAllowed(event.getBlock().getWorld())) {
            event.blockList().clear();
            event.setYield(0.0f);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onPlayerPrimesBlockExplosion(PlayerInteractEvent event) {
        RespawnAnchor anchor;
        BlockData blockData;
        boolean chargedAnchor;
        if (this.state != EventState.ACTIVE || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || this.eventWorld == null || !event.getClickedBlock().getWorld().equals(this.eventWorld) || !this.participants.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        Block block = event.getClickedBlock();
        boolean bed = block.getType().name().endsWith("_BED");
        boolean bl = chargedAnchor = block.getType() == Material.RESPAWN_ANCHOR && (blockData = block.getBlockData()) instanceof RespawnAnchor && (anchor = (RespawnAnchor)blockData).getCharges() > 0;
        if (bed || chargedAnchor) {
            this.pendingBlockExplosions.put(this.blockKey(block), new PendingExplosion(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 2000L));
        }
    }

    private boolean isArenaDestructionAllowed(World world) {
        return this.state == EventState.ACTIVE && this.eventWorld != null && world.equals(this.eventWorld) && this.plugin.getConfig().getBoolean("event.arena-destruction.enabled", true);
    }

    private double boundedExplosionPower(String path, double fallback) {
        return Math.max(0.0, Math.min(20.0, this.plugin.getConfig().getDouble(path, fallback)));
    }

    private void registerLimitedEntity(Entity entity, Deque<UUID> queue, int configuredMaximum) {
        UUID uuid = entity.getUniqueId();
        queue.remove(uuid);
        queue.removeIf(id -> {
            Entity existing = Bukkit.getEntity(id);
            return existing == null || !existing.isValid();
        });
        queue.addLast(uuid);
        int maximum = Math.max(0, configuredMaximum);
        if (maximum == 0) {
            return;
        }
        while (queue.size() > maximum) {
            UUID oldest = queue.removeFirst();
            Entity limited = Bukkit.getEntity(oldest);
            if (limited != null && limited.isValid()) {
                limited.remove();
            }
            this.explosiveOwners.remove(oldest);
            this.primedTnt.remove(oldest);
            this.aprilProjectiles.remove(oldest);
        }
    }

    private String blockKey(Block block) {
        return String.valueOf(block.getWorld().getUID()) + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void startFightClock() {
        this.cancelFightTasks();
        this.fightSecondsLeft = Math.max(60, this.plugin.getConfig().getInt("event.fight-time-limit-seconds", 1800));
        this.fightClockTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            if (this.state != EventState.ACTIVE) {
                return;
            }
            if (this.fightSecondsLeft == 600 || this.fightSecondsLeft == 300 || this.fightSecondsLeft == 60) {
                Bukkit.broadcast(this.messages.get("fight-time-warning", Map.of("time", DragonEventManager.formatTime(this.fightSecondsLeft))));
            }
            this.updateBossPhase();
            this.sendFightActionBar();
            this.runDragonWatchdog();
            --this.fightSecondsLeft;
        }, 20L, 20L);
        this.fightTimeoutTask = Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            if (this.state != EventState.ACTIVE) {
                return;
            }
            this.finishDefeat("fight-timeout", Map.of("limit", DragonEventManager.formatTime(this.plugin.getConfig().getInt("event.fight-time-limit-seconds", 1800))));
        }, (long)this.fightSecondsLeft * 20L);
    }

    private void sendFightActionBar() {
        if (this.eventWorld == null || !this.plugin.getConfig().getBoolean("event.fight-actionbar.enabled", true)) {
            return;
        }
        List<UUID> ranking = damage.entrySet().stream()
                .filter(e -> e.getValue() > 0 && !spectators.contains(e.getKey()) && !departedParticipants.contains(e.getKey()))
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).map(Map.Entry::getKey).toList();
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int index = 0; index < ranking.size(); index++) ranks.put(ranking.get(index), index + 1);
        Map<String, String> common = Map.of(
                "fighters", Integer.toString(activeFighterCount()), "spectators", Integer.toString(activeSpectatorCount()),
                "time", formatTime(fightSecondsLeft), "boss", bossLabel(),
                "hp_percent", Integer.toString((int)Math.ceil(bossHealthFraction() * 100)),
                "phase", enraged ? "ENRAGED · " : "");
        for (Player player : this.eventWorld.getPlayers()) {
            Map<String, String> replacements = new HashMap<>(common);
            int rank = ranks.getOrDefault(player.getUniqueId(), 0);
            replacements.put("rank", rank == 0 ? "-" : Integer.toString(rank));
            replacements.put("damage", String.format(Locale.US, "%.0f", currentDamage(player.getUniqueId())));
            player.sendActionBar(this.messages.getRaw("fight-actionbar", replacements));
        }
    }

    private String bossLabel() {
        return switch (eventMode) {
            case NORMAL -> "Dragon";
            case APRIL_FOOLS -> "Creeper";
            case FREDDIE -> "Freddie";
        };
    }

    private double bossHealthFraction() {
        if (dragon == null || !dragon.isValid() || effectiveDragonHealth <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, dragon.getHealth() / Math.min(1024.0, effectiveDragonHealth)));
    }

    private void updateBossPhase() {
        if (enraged || state != EventState.ACTIVE || eventWorld == null || dragon == null
                || !dragon.isValid() || dragon.getHealth() <= 0
                || !plugin.getConfig().getBoolean("event.enrage.enabled", true)) return;
        double threshold = BossBalance.finite(plugin.getConfig().getDouble("event.enrage.health-threshold", .25), .25, .01, 1.0);
        if (bossHealthFraction() > threshold) return;
        enraged = true;
        Map<String, String> replacements = Map.of("boss", bossLabel());
        var title = net.kyori.adventure.title.Title.title(messages.getRaw("boss-enraged-title", replacements),
                messages.getRaw("boss-enraged-subtitle", replacements),
                net.kyori.adventure.title.Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400)));
        for (Player player : eventWorld.getPlayers()) {
            messages.send(player, "boss-enraged", replacements);
            player.showTitle(title);
            player.playSound(player.getLocation(), eventMode == EventMode.FREDDIE ? "minecraft:entity.frog.ambient"
                    : "minecraft:entity.ender_dragon.growl", 0.7f, 0.7f);
        }
    }

    private int activeFighterCount() {
        int fighters = 0;
        for (UUID uuid : this.participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (this.spectators.contains(uuid) || this.departedParticipants.contains(uuid) || player == null || !player.isOnline() || this.eventWorld == null || !player.getWorld().equals(this.eventWorld)) continue;
            ++fighters;
        }
        return fighters;
    }

    private int activeSpectatorCount() {
        int count = 0;
        for (UUID uuid : this.spectators) {
            Player player = Bukkit.getPlayer(uuid);
            if (this.departedParticipants.contains(uuid) || player == null || !player.isOnline() || this.eventWorld == null || !player.getWorld().equals(this.eventWorld)) continue;
            ++count;
        }
        return count;
    }

    private void runDragonWatchdog() {
        Location center;
        if (!this.plugin.getConfig().getBoolean("event.dragon-watchdog.enabled", true) || this.dragon == null || !this.dragon.isValid() || this.eventWorld == null) {
            return;
        }
        Location current = this.dragon.getLocation();
        double minimumY = this.plugin.getConfig().getDouble("event.dragon-watchdog.minimum-y", -20.0);
        double maximumY = this.plugin.getConfig().getDouble("event.dragon-watchdog.maximum-y", 300.0);
        if (this.eventMode.isExplosiveBoss() && (center = this.portalLocation(this.eventWorld.getEnderDragonBattle())) != null) {
            double aprilMaximum = Math.max(30.0, this.jokeDouble("april-fools.carrier.maximum-height-above-portal", 65.0));
            maximumY = Math.min(maximumY, center.getY() + aprilMaximum);
        }
        boolean outside = current.getY() < minimumY || current.getY() > maximumY || this.plugin.getConfig().getBoolean("world.border.enabled", true) && !this.eventWorld.getWorldBorder().isInside(current);
        double movement = Math.max(0.25, this.plugin.getConfig().getDouble("event.dragon-watchdog.minimum-movement-blocks", 2.0));
        this.dragonStationarySeconds = this.lastDragonLocation != null && this.lastDragonLocation.getWorld() == current.getWorld() && this.lastDragonLocation.distanceSquared(current) < movement * movement && !this.dragonMayHoverAtPortal() ? ++this.dragonStationarySeconds : 0;
        this.lastDragonLocation = current.clone();
        int maximumStationary = Math.max(10, this.plugin.getConfig().getInt("event.dragon-watchdog.maximum-stationary-seconds", 25));
        if (!outside && this.dragonStationarySeconds < maximumStationary) {
            return;
        }
        Location rescue = this.dragonRescueLocation();
        this.dragon.teleport(rescue);
        this.dragon.setVelocity(new Vector());
        this.dragon.setPhase(this.eventMode.isExplosiveBoss() ? EnderDragon.Phase.LAND_ON_PORTAL : EnderDragon.Phase.CIRCLING);
        this.dragonStationarySeconds = 0;
        this.lastDragonLocation = rescue.clone();
        for (Player player : this.eventWorld.getPlayers()) {
            this.messages.send(player, "dragon-watchdog-rescue");
        }
    }

    private boolean dragonMayHoverAtPortal() {
        String phase = this.dragon.getPhase().name();
        return phase.equals("LAND_ON_PORTAL") || phase.equals("BREATH_ATTACK") || phase.equals("SEARCH_FOR_BREATH_ATTACK_TARGET") || phase.equals("ROAR_BEFORE_ATTACK") || phase.equals("DYING");
    }

    private boolean aprilProjectilesPausedAtPortal() {
        double deltaZ;
        if (!this.eventMode.isExplosiveBoss() || this.dragon == null || !this.dragon.isValid() || this.eventWorld == null || !this.jokeBoolean("april-fools.projectiles.portal-cease-fire.enabled", true)) {
            return false;
        }
        String phase = this.dragon.getPhase().name();
        if (phase.equals("FLY_TO_PORTAL") || phase.equals("LAND_ON_PORTAL") || phase.equals("BREATH_ATTACK") || phase.equals("SEARCH_FOR_BREATH_ATTACK_TARGET") || phase.equals("ROAR_BEFORE_ATTACK") || phase.equals("DYING")) {
            return true;
        }
        Location center = this.portalLocation(this.eventWorld.getEnderDragonBattle());
        if (center == null) {
            return false;
        }
        Location current = this.dragon.getLocation();
        double radius = Math.max(4.0, Math.min(48.0, this.jokeDouble("april-fools.projectiles.portal-cease-fire.radius", 18.0)));
        double maximumHeight = Math.max(4.0, Math.min(64.0, this.jokeDouble("april-fools.projectiles.portal-cease-fire.maximum-height-above-portal", 16.0)));
        double deltaX = current.getX() - center.getX();
        return deltaX * deltaX + (deltaZ = current.getZ() - center.getZ()) * deltaZ <= radius * radius && current.getY() >= center.getY() - 8.0 && current.getY() <= center.getY() + maximumHeight;
    }

    private Location dragonRescueLocation() {
        DragonBattle battle = this.eventWorld.getEnderDragonBattle();
        Location center = this.portalLocation(battle);
        if (center == null) {
            center = this.joinLocation();
        }
        double offset = this.eventMode.isExplosiveBoss() ? this.jokeDouble("april-fools.carrier.rescue-height-above-portal", 45.0) : this.plugin.getConfig().getDouble("event.dragon-watchdog.rescue-height-offset", 70.0);
        double y = Math.max((double)this.eventWorld.getMinHeight() + 10.0, Math.min((double)this.eventWorld.getMaxHeight() - 10.0, center.getY() + offset));
        return new Location(this.eventWorld, center.getX() + 0.5, y, center.getZ() + 0.5, 0.0f, 0.0f);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getScoreboardTags().contains("townysmp_april_creeper")) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            if (this.eventMode.isExplosiveBoss() && this.state == EventState.ACTIVE) {
                Bukkit.getScheduler().runTask((Plugin)this.plugin, this::spawnAprilCreeper);
            }
            return;
        }
        if (this.state == EventState.ACTIVE && event.getEntity() == this.dragon) {
            Location finaleLocation = this.aprilBoss != null && this.aprilBoss.isValid() ? this.aprilBoss.getLocation().clone() : this.dragon.getLocation().clone();
            this.cancelAprilVisual();
            this.rebuildSafeExitPortal();
            if (this.eventMode.isExplosiveBoss()) {
                this.startAprilFoolsFinale(finaleLocation);
            }
            this.finish(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onVictoryFireworkDamage(EntityDamageByEntityEvent event) {
        Creeper creeper;
        Firework firework;
        Entity entity = event.getDamager();
        if (entity instanceof Firework && (firework = (Firework)entity).getScoreboardTags().contains("townysmp_dragon_victory") || (entity = event.getDamager()) instanceof Creeper && (creeper = (Creeper)entity).getScoreboardTags().contains("townysmp_april_finale")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onEggDrop(ItemSpawnEvent event) {
        if (this.eventWorld != null && event.getLocation().getWorld().equals(this.eventWorld) && event.getEntity().getItemStack().getType() == Material.DRAGON_EGG) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (this.eventWorld == null || !player.getWorld().equals(this.eventWorld) || !this.participants.contains(player.getUniqueId())) {
            return;
        }
        if (this.plugin.getConfig().getBoolean("event.death.keep-inventory", true)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        if (this.state != EventState.RESPAWNING && this.state != EventState.ACTIVE) {
            return;
        }
        int deaths = this.eventDeaths.merge(player.getUniqueId(), 1, Integer::sum);
        int maximum = this.plugin.getConfig().getInt("event.death.spectator-after-deaths", 5);
        if (maximum > 0 && deaths >= maximum) {
            this.spectators.add(player.getUniqueId());
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (this.eventWorld == null || !player.getWorld().equals(this.eventWorld) || !this.participants.contains(player.getUniqueId()) || this.state == EventState.IDLE || this.state == EventState.RESETTING) {
            return;
        }
        if (this.state == EventState.FINISHING) {
            Location exit = this.exitLocation();
            if (exit != null) {
                event.setRespawnLocation(exit);
            }
            this.departedParticipants.add(player.getUniqueId());
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.restoreGameMode(player));
            return;
        }
        event.setRespawnLocation(this.safeJoinLocation());
        int protection = Math.max(0, this.plugin.getConfig().getInt("event.death.respawn-protection-seconds", 5));
        int slowFalling = Math.max(0, this.plugin.getConfig().getInt("event.death.slow-falling-seconds", 8));
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            if (this.spectators.contains(player.getUniqueId())) {
                this.originalGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
                this.originalAllowFlight.putIfAbsent(player.getUniqueId(), player.getAllowFlight());
                this.originalFlying.putIfAbsent(player.getUniqueId(), player.isFlying());
                player.addScoreboardTag("townysmp_dragon_spectator");
                player.setGameMode(GameMode.SPECTATOR);
                player.setAllowFlight(true);
                player.setFlying(true);
                this.trackedTeleport(player, this.spectatorLocation());
                this.messages.send(player, "eliminated", Map.of("deaths", Integer.toString(this.eventDeaths.getOrDefault(player.getUniqueId(), 0))));
                this.checkForBattleLoss();
                return;
            }
            if (protection > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, protection * 20, 4, false, false, true));
            }
            if (slowFalling > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, slowFalling * 20, 0, false, false, true));
            }
            this.runRespawnCommands(player);
            int maximum = Math.max(1, this.plugin.getConfig().getInt("event.death.spectator-after-deaths", 5));
            int remaining = Math.max(0, maximum - this.eventDeaths.getOrDefault(player.getUniqueId(), 0));
            this.messages.send(player, "respawned", Map.of("seconds", Integer.toString(protection), "remaining", Integer.toString(remaining), "maximum", Integer.toString(maximum)));
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onVoidFall(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location destination = event.getTo();
        if (!this.plugin.getConfig().getBoolean("event.void-rescue.enabled", true) || this.eventWorld == null || !player.getWorld().equals(this.eventWorld) || !this.participants.contains(player.getUniqueId()) || this.spectators.contains(player.getUniqueId()) || destination == null || destination.getY() > this.plugin.getConfig().getDouble("event.void-rescue.trigger-y", -10.0)) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = this.plugin.getConfig().getLong("event.void-rescue.cooldown-seconds", 10L) * 1000L;
        if (now - this.voidRescueCooldown.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
            return;
        }
        this.voidRescueCooldown.put(player.getUniqueId(), now);
        player.teleport(this.safeJoinLocation());
        int slowFalling = this.plugin.getConfig().getInt("event.void-rescue.slow-falling-seconds", 8);
        if (slowFalling > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, slowFalling * 20, 0, false, false, true));
        }
        this.messages.send(player, "void-rescued");
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onSpectatorCrossesBorder(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location destination = event.getTo();
        if (destination == null || this.eventWorld == null || !player.getWorld().equals(this.eventWorld) || !this.spectators.contains(player.getUniqueId()) || !this.plugin.getConfig().getBoolean("world.border.enabled", true) || this.spectatorLocationIsSafe(destination)) {
            return;
        }
        player.setAllowFlight(true);
        player.setFlying(true);
        event.setTo(this.spectatorLocation());
        this.messages.send(player, "spectator-boundary");
    }

    private void runRespawnCommands(Player player) {
        for (String command : this.plugin.getConfig().getStringList("event.death.respawn-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
    }

    private void checkForBattleLoss() {
        if (state != EventState.ACTIVE) return;
        boolean activeCombatant = participants.stream().filter(uuid -> !spectators.contains(uuid)).anyMatch(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            return !departedParticipants.contains(uuid) && (disconnectGraceTasks.containsKey(uuid)
                    || player != null && player.isOnline()
                    && eventWorld != null && player.getWorld().equals(eventWorld));
        });
        if (activeCombatant) return;
        boolean anyoneRemaining = participants.stream().anyMatch(uuid -> !departedParticipants.contains(uuid));
        boolean remainingAreSpectators = anyoneRemaining && participants.stream()
                .filter(uuid -> !departedParticipants.contains(uuid)).allMatch(spectators::contains);
        finishDefeat(remainingAreSpectators ? "team-wipe" : "all-fighters-left", Map.of());
    }

    private void finish(boolean victory) {
        if (this.state == EventState.FINISHING || this.state == EventState.RESETTING || this.state == EventState.IDLE) {
            return;
        }
        this.cancelTicker();
        this.cancelFightTasks();
        this.state = EventState.FINISHING;
        StaffAudit.transition(this, "dragon_events_ended", victory, victory ? "victory" : "cancelled");
        if (victory) {
            this.distributeRewards();
            int delay = Math.max(5, this.plugin.getConfig().getInt("event.finish-delay-seconds", 120));
            this.messages.broadcast("victory", Map.of("time", DragonEventManager.formatTime(delay)), "victory");
            this.startVictoryFireworks();
            this.startClosingCountdown(delay);
        } else {
            this.finishTask = Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, this::evacuateAndReset, 20L);
        }
    }

    private void finishDefeat(String messageKey, Map<String, String> replacements) {
        if (this.state != EventState.ACTIVE) {
            return;
        }
        this.cancelTicker();
        this.cancelFightTasks();
        this.state = EventState.FINISHING;
        StaffAudit.transition(this, "dragon_events_ended", false, messageKey);
        this.cancelAprilVisual();
        if (this.dragon != null && this.dragon.isValid()) {
            this.dragon.setInvulnerable(true);
            this.dragon.setAI(false);
            this.dragon.setSilent(true);
            this.dragon.setVelocity(new Vector());
            this.dragon.setPhase(EnderDragon.Phase.HOVER);
        }
        this.recordDefeatStatistics();
        this.rebuildSafeExitPortal();
        int delay = Math.max(5, this.plugin.getConfig().getInt("event.finish-delay-seconds", 120));
        HashMap<String, String> values = new HashMap<String, String>(replacements);
        values.put("time", DragonEventManager.formatTime(delay));
        this.messages.broadcast(messageKey, values, "defeat");
        this.startClosingCountdown(delay);
    }

    private void startClosingCountdown(int seconds) {
        this.cancelClosingCountdown();
        this.closingSecondsLeft = Math.max(1, seconds);
        this.sendClosingActionBar();
        this.closingCountdownTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            --this.closingSecondsLeft;
            if (this.closingSecondsLeft <= 0) {
                this.cancelClosingCountdown();
                return;
            }
            this.sendClosingActionBar();
        }, 20L, 20L);
        this.finishTask = Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, this::evacuateAndReset, (long)this.closingSecondsLeft * 20L);
    }

    private void sendClosingActionBar() {
        if (this.eventWorld == null) {
            return;
        }
        Map<String, String> replacements = Map.of("time", DragonEventManager.formatTime(this.closingSecondsLeft));
        for (Player player : this.eventWorld.getPlayers()) {
            player.sendActionBar(this.messages.getRaw("closing-actionbar", replacements));
        }
    }

    private void startVictoryFireworks() {
        this.cancelVictoryFireworks();
        if (this.eventWorld == null || !this.plugin.getConfig().getBoolean("victory-fireworks.enabled", true)) {
            return;
        }
        int waves = Math.max(1, this.plugin.getConfig().getInt("victory-fireworks.waves", 4));
        int interval = Math.max(2, this.plugin.getConfig().getInt("victory-fireworks.interval-ticks", 12));
        int[] remaining = new int[]{waves};
        this.victoryFireworkTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            block3: {
                block2: {
                    if (this.state != EventState.FINISHING || this.eventWorld == null) break block2;
                    int n = remaining[0];
                    remaining[0] = n - 1;
                    if (n > 0) break block3;
                }
                this.cancelVictoryFireworks();
                return;
            }
            this.launchVictoryFireworkWave();
        }, 0L, (long)interval);
    }

    private void launchVictoryFireworkWave() {
        DragonBattle battle = this.eventWorld.getEnderDragonBattle();
        Location center = this.portalLocation(battle);
        if (center == null) {
            center = this.joinLocation();
        }
        int amount = Math.max(1, this.plugin.getConfig().getInt("victory-fireworks.fireworks-per-wave", 5));
        int power = Math.max(0, Math.min(2, this.plugin.getConfig().getInt("victory-fireworks.power", 1)));
        Color[] colors = new Color[]{Color.fromRGB(255, 43, 214), Color.fromRGB(56, 255, 101), Color.fromRGB(255, 215, 0), Color.fromRGB(55, 220, 255)};
        FireworkEffect.Type[] types = new FireworkEffect.Type[]{FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST};
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < amount; ++index) {
            double angle = random.nextDouble(Math.PI * 2);
            double radius = random.nextDouble(2.0, 10.0);
            Location launch = center.clone().add(Math.cos(angle) * radius, 2.0, Math.sin(angle) * radius);
            Firework firework = this.eventWorld.spawn(launch, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            Color primary = colors[random.nextInt(colors.length)];
            Color fade = colors[random.nextInt(colors.length)];
            meta.addEffect(FireworkEffect.builder().with(types[random.nextInt(types.length)]).withColor(primary).withFade(fade).trail(true).flicker(true).build());
            meta.setPower(power);
            firework.setFireworkMeta(meta);
            firework.setVelocity(new Vector(random.nextDouble(-0.12, 0.12), 0.8, random.nextDouble(-0.12, 0.12)));
            firework.addScoreboardTag("townysmp_dragon_victory");
        }
    }

    private void startAprilFoolsFinale(Location center) {
        Color[] colors;
        if (this.eventWorld == null || !this.jokeBoolean("april-fools.finale.enabled", true)) {
            return;
        }
        this.eventWorld.playSound(center, eventMode == EventMode.FREDDIE ? "minecraft:entity.frog.ambient" : "minecraft:entity.creeper.primed", 1.5f, 0.55f);
        this.eventWorld.playSound(center, "minecraft:entity.generic.explode", 1.4f, 1.2f);
        for (Color color : colors = new Color[]{Color.fromRGB(255, 43, 214), Color.fromRGB(56, 255, 101), Color.fromRGB(255, 215, 0), Color.fromRGB(55, 220, 255)}) {
            this.eventWorld.spawnParticle(Particle.DUST, center, 45, 2.5, 2.5, 2.5, 0.08, new Particle.DustOptions(color, 1.6f));
        }
        this.eventWorld.spawnParticle(Particle.FIREWORK, center, 80, 3.0, 3.0, 3.0, 0.25);
        int minis = Math.max(0, Math.min(30, eventMode == EventMode.FREDDIE
                ? plugin.getConfig().getInt("freddie.finale.mini-frogs", 10)
                : this.jokeInt("april-fools.finale.mini-creepers", 10)));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < minis; ++index) {
            Location spawn = center.clone().add(random.nextDouble(-2.0, 2.0), random.nextDouble(0.5, 2.5), random.nextDouble(-2.0, 2.0));
            if (eventMode == EventMode.FREDDIE) {
                Frog mini = eventWorld.spawn(spawn, Frog.class, frog -> {
                    frog.setAI(false);
                    frog.setVariant(Frog.Variant.COLD);
                    frog.setPersistent(false);
                    frog.setInvulnerable(true);
                    frog.addScoreboardTag("townysmp_april_finale");
                });
                mini.setVelocity(new Vector(random.nextDouble(-0.7, 0.7), random.nextDouble(0.5, 1.1), random.nextDouble(-0.7, 0.7)));
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (mini.isValid()) mini.remove(); }, 100L);
                continue;
            }
            Creeper mini = this.eventWorld.spawn(spawn, Creeper.class, creeper -> {
                creeper.setAI(false);
                creeper.setPowered(random.nextBoolean());
                creeper.setExplosionRadius(0);
                creeper.setMaxFuseTicks(Math.max(10, this.jokeInt("april-fools.finale.mini-fuse-ticks", 40)));
                creeper.addScoreboardTag("townysmp_april_finale");
                AttributeInstance scale = creeper.getAttribute(Attribute.SCALE);
                if (scale != null) {
                    scale.setBaseValue(Math.max(0.2, Math.min(1.0, this.jokeDouble("april-fools.finale.mini-creeper-scale", 0.45))));
                }
            });
            mini.setVelocity(new Vector(random.nextDouble(-0.7, 0.7), random.nextDouble(0.5, 1.1), random.nextDouble(-0.7, 0.7)));
            mini.ignite();
            Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
                if (mini.isValid()) {
                    mini.remove();
                }
            }, 100L);
        }
        int finaleFireworks = Math.max(0, Math.min(20, this.jokeInt("april-fools.finale.fireworks", 6)));
        for (int index = 0; index < finaleFireworks; ++index) {
            Location launch = center.clone().add(random.nextDouble(-4.0, 4.0), random.nextDouble(0.0, 2.0), random.nextDouble(-4.0, 4.0));
            Firework firework = this.eventWorld.spawn(launch, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST).withColor(colors[random.nextInt(colors.length)]).withFade(colors[random.nextInt(colors.length)]).trail(true).flicker(true).build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
            firework.addScoreboardTag("townysmp_dragon_victory");
        }
    }

    void stop() {
        if (this.state == EventState.IDLE) {
            return;
        }
        if (this.state == EventState.RESETTING) {
            if (this.recoveryBlocked) {
                this.recoverAfterRestart();
            } else {
                this.attemptWorldUnload(this.lifecycleGeneration, 0);
            }
            return;
        }
        Bukkit.broadcast(this.messages.get("stopped"));
        this.cancelEventTasks();
        if (this.dragon != null && this.dragon.isValid()) {
            this.dragon.remove();
        }
        this.evacuateAndReset();
    }

    private void distributeRewards() {
        if (this.statisticsRecorded) {
            return;
        }
        this.statisticsRecorded = true;
        List<Map.Entry<UUID, Double>> ranking = this.damage.entrySet().stream().filter(entry -> (Double)entry.getValue() > 0.0 && !this.spectators.contains(entry.getKey()) && !this.departedParticipants.contains(entry.getKey())).sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).toList();
        String players = this.participantNames();
        double minimum = this.plugin.getConfig().getDouble("event.minimum-reward-damage", 25.0);
        Bukkit.broadcast(this.messages.get("top-header"));
        for (int i = 0; i < Math.min(3, ranking.size()); ++i) {
            String name = Bukkit.getOfflinePlayer((UUID)ranking.get(i).getKey()).getName();
            Bukkit.broadcast(this.messages.get("top-entry", Map.of("rank", Integer.toString(i + 1), "player", name == null ? ranking.get(i).getKey().toString() : name, "damage", this.formatDamage(ranking.get(i).getValue()))));
        }
        this.broadcastMvpSummary(ranking);
        for (UUID uuid : this.participants) {
            boolean rewardEligible;
            int rank = ranking.stream().map(Map.Entry::getKey).toList().indexOf(uuid) + 1;
            if (this.damage.getOrDefault(uuid, 0.0) <= 0.0) {
                rank = 0;
            }
            double dealt = this.damage.getOrDefault(uuid, 0.0);
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            StatsManager.RecordResult result = this.stats.record(uuid, name == null ? uuid.toString() : name, dealt, rank, this.eventDeaths.getOrDefault(uuid, 0), this.crystalsDestroyed.getOrDefault(uuid, 0), this.explosionsTriggered.getOrDefault(uuid, 0));
            Bukkit.getPluginManager().callEvent(new DragonResultEvent(this.resultEventId, uuid, name == null ? uuid.toString() : name, dealt, rank, this.eventDeaths.getOrDefault(uuid, 0), this.crystalsDestroyed.getOrDefault(uuid, 0)));
            StaffAudit.result(this, uuid, name == null ? uuid.toString() : name, dealt, rank, this.eventDeaths.getOrDefault(uuid, 0), this.crystalsDestroyed.getOrDefault(uuid, 0), this.explosionsTriggered.getOrDefault(uuid, 0), true);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                String resultKey = rank > 0 ? "personal-result" : "personal-result-unranked";
                this.messages.send(online, resultKey, Map.of("rank", Integer.toString(rank), "damage", this.formatDamage(dealt), "crystals", Integer.toString(this.crystalsDestroyed.getOrDefault(uuid, 0)), "explosions", Integer.toString(this.explosionsTriggered.getOrDefault(uuid, 0))));
                if (result.personalBest()) {
                    this.messages.send(online, "personal-best", Map.of("damage", this.formatDamage(dealt)));
                }
            }
            boolean bl = rewardEligible = !this.spectators.contains(uuid) && !this.departedParticipants.contains(uuid);
            if (dealt >= minimum && rewardEligible) {
                boolean rankReward;
                boolean bl2 = rankReward = rank >= 1 && rank <= 3 && !this.plugin.getConfig().getStringList("rewards.rank-" + rank).isEmpty();
                if (rankReward) {
                    this.runRewardCommands("rewards.rank-" + rank, uuid, rank, dealt, players);
                }
                boolean rankReplacesOthers = this.plugin.getConfig().getBoolean("rewards.policy.rank-replaces-other-rewards", true);
                if (!rankReward || !rankReplacesOthers) {
                    String participationPath = this.lateParticipants.contains(uuid) ? "rewards.late-participation" : "rewards.participation";
                    this.runRewardCommands(participationPath, uuid, rank, dealt, players);
                    this.runDamageTierCommands(uuid, rank, dealt, players);
                    if (this.eventDeaths.getOrDefault(uuid, 0) == 0) {
                        this.runRewardCommands("rewards.no-death", uuid, rank, dealt, players);
                    }
                }
            } else if (online != null && rewardEligible) {
                this.messages.send(online, "reward-ineligible", Map.of("minimum", this.formatDamage(minimum)));
            }
            if (ranking.isEmpty() || !ranking.get(0).getKey().equals(uuid) || !result.serverRecord()) continue;
            Bukkit.broadcast(this.messages.get("server-record", Map.of("player", name == null ? uuid.toString() : name, "damage", this.formatDamage(dealt))));
        }
        this.runEventEndCommands(players);
        this.stats.save();
    }

    private void recordDefeatStatistics() {
        if (this.statisticsRecorded) {
            return;
        }
        this.statisticsRecorded = true;
        for (UUID uuid : this.participants) {
            double dealt = this.damage.getOrDefault(uuid, 0.0);
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            StatsManager.RecordResult result = this.stats.record(uuid, name == null ? uuid.toString() : name, dealt, 0, this.eventDeaths.getOrDefault(uuid, 0), this.crystalsDestroyed.getOrDefault(uuid, 0), this.explosionsTriggered.getOrDefault(uuid, 0));
            StaffAudit.result(this, uuid, name == null ? uuid.toString() : name, dealt, 0, this.eventDeaths.getOrDefault(uuid, 0), this.crystalsDestroyed.getOrDefault(uuid, 0), this.explosionsTriggered.getOrDefault(uuid, 0), false);
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) continue;
            this.messages.send(online, "defeat-result", Map.of("damage", this.formatDamage(dealt), "deaths", Integer.toString(this.eventDeaths.getOrDefault(uuid, 0)), "crystals", Integer.toString(this.crystalsDestroyed.getOrDefault(uuid, 0)), "explosions", Integer.toString(this.explosionsTriggered.getOrDefault(uuid, 0))));
            if (!result.personalBest()) continue;
            this.messages.send(online, "personal-best", Map.of("damage", this.formatDamage(dealt)));
        }
        this.stats.save();
    }

    private void broadcastMvpSummary(List<Map.Entry<UUID, Double>> ranking) {
        int mostExplosions;
        List<UUID> fighters = this.participants.stream().filter(uuid -> this.damage.getOrDefault(uuid, 0.0) > 0.0).filter(uuid -> !this.departedParticipants.contains(uuid)).filter(uuid -> !this.spectators.contains(uuid)).toList();
        if (fighters.isEmpty()) {
            return;
        }
        Bukkit.broadcast(this.messages.get("mvp-header"));
        if (!ranking.isEmpty()) {
            Bukkit.broadcast(this.messages.get("mvp-damage", Map.of("players", this.displayNames(List.of(ranking.get(0).getKey())), "damage", this.formatDamage(ranking.get(0).getValue()))));
        }
        int fewestDeaths = fighters.stream().mapToInt(uuid -> this.eventDeaths.getOrDefault(uuid, 0)).min().orElse(0);
        List<UUID> survivors = fighters.stream().filter(uuid -> this.eventDeaths.getOrDefault(uuid, 0) == fewestDeaths).toList();
        Bukkit.broadcast(this.messages.get("mvp-survivor", Map.of("players", this.displayNames(survivors), "deaths", Integer.toString(fewestDeaths))));
        int mostCrystals = fighters.stream().mapToInt(uuid -> this.crystalsDestroyed.getOrDefault(uuid, 0)).max().orElse(0);
        if (mostCrystals > 0) {
            List<UUID> crystalMvp = fighters.stream().filter(uuid -> this.crystalsDestroyed.getOrDefault(uuid, 0) == mostCrystals).toList();
            Bukkit.broadcast(this.messages.get("mvp-crystals", Map.of("players", this.displayNames(crystalMvp), "crystals", Integer.toString(mostCrystals))));
        }
        if ((mostExplosions = fighters.stream().mapToInt(uuid -> this.explosionsTriggered.getOrDefault(uuid, 0)).max().orElse(0)) > 0) {
            List<UUID> explosionMvp = fighters.stream().filter(uuid -> this.explosionsTriggered.getOrDefault(uuid, 0) == mostExplosions).toList();
            Bukkit.broadcast(this.messages.get("mvp-explosions", Map.of("players", this.displayNames(explosionMvp), "explosions", Integer.toString(mostExplosions))));
        }
    }

    private String displayNames(List<UUID> uuids) {
        return uuids.stream().map(Bukkit::getOfflinePlayer).map(player -> player.getName() == null ? player.getUniqueId().toString() : player.getName()).sorted(String.CASE_INSENSITIVE_ORDER).reduce((left, right) -> left + ", " + right).orElse("-");
    }

    private void runDamageTierCommands(UUID uuid, int rank, double dealt, String players) {
        ConfigurationSection tiers = this.plugin.getConfig().getConfigurationSection("rewards.damage-tiers");
        if (tiers == null) {
            return;
        }
        List<Double> reached = tiers.getKeys(false).stream().map(key -> {
            try {
                return Double.parseDouble(key);
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }).filter(Objects::nonNull).sorted().filter(required -> dealt >= required).toList();
        if (reached.isEmpty()) {
            return;
        }
        if (this.plugin.getConfig().getBoolean("rewards.policy.highest-damage-tier-only", true)) {
            double highest = reached.get(reached.size() - 1);
            this.runRewardCommands("rewards.damage-tiers." + this.formatTierKey(highest), uuid, rank, dealt, players);
            return;
        }
        reached.forEach(required -> this.runRewardCommands("rewards.damage-tiers." + this.formatTierKey((double)required), uuid, rank, dealt, players));
    }

    private String formatTierKey(double value) {
        return value == Math.rint(value) ? Long.toString((long)value) : Double.toString(value);
    }

    private String formatDamage(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void runRewardCommands(String path, UUID uuid, int rank, double dealt, String players) {
        String name;
        Player player = Bukkit.getPlayer(uuid);
        String string = name = player != null ? player.getName() : Bukkit.getOfflinePlayer(uuid).getName();
        if (name == null) {
            return;
        }
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (String command : this.plugin.getConfig().getStringList(path)) {
            String expanded = this.applyCommandVariables(command, name, uuid, rank, dealt, players);
            boolean accepted = Bukkit.dispatchCommand(console, expanded);
            StaffAudit.reward(this, uuid, name, path, expanded, accepted);
        }
    }

    private void runEventEndCommands(String players) {
        for (String command : this.plugin.getConfig().getStringList("rewards.event-end")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{players}", players).replace("{player_count}", Integer.toString(this.participants.size())));
        }
    }

    private String applyCommandVariables(String command, String name, UUID uuid, int rank, double dealt, String players) {
        return command.replace("{player}", name).replace("{uuid}", uuid.toString()).replace("{rank}", Integer.toString(rank)).replace("{damage}", String.format(Locale.US, "%.2f", dealt)).replace("{crystals}", Integer.toString(this.crystalsDestroyed.getOrDefault(uuid, 0))).replace("{explosions}", Integer.toString(this.explosionsTriggered.getOrDefault(uuid, 0))).replace("{players}", players).replace("{player_count}", Integer.toString(this.participants.size()));
    }

    private String participantNames() {
        return this.participants.stream().map(Bukkit::getOfflinePlayer).map(player -> player.getName() == null ? player.getUniqueId().toString() : player.getName()).sorted(String.CASE_INSENSITIVE_ORDER).reduce((left, right) -> left + "," + right).orElse("");
    }

    private Map<String, String> playerCountReplacement() {
        return Map.of("players", Integer.toString(this.playerCount()));
    }

    private void evacuateAndReset() {
        this.cancelEventTasks();
        this.state = EventState.RESETTING;
        long operation = ++this.lifecycleGeneration;
        this.resetDeleteQueued = false;
        this.recoveryBlocked = false;
        this.cancelAllDisconnectGrace();
        ArrayList<CompletableFuture<Boolean>> evacuations = new ArrayList<CompletableFuture<Boolean>>();
        for (UUID uuid : new HashSet<UUID>(this.participants)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            this.restoreGameMode(player);
        }
        if (this.eventWorld != null) {
            for (Player player : new ArrayList<Player>(this.eventWorld.getPlayers())) {
                this.restoreGameMode(player);
                evacuations.add(this.runFallback(player));
            }
        }
        CompletableFuture.allOf((CompletableFuture[])evacuations.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> this.runOnServerThread(() -> this.attemptWorldUnload(operation, 0)));
        this.resetTask = Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> this.attemptWorldUnload(operation, 0), 20L);
    }

    private void attemptWorldUnload(long operation, int attempt) {
        if (operation != this.lifecycleGeneration || this.state != EventState.RESETTING || this.shuttingDown) {
            return;
        }
        if (this.eventWorld == null) {
            this.deleteRuntimeAndClear(operation);
            return;
        }
        if (!this.eventWorld.getPlayers().isEmpty()) {
            for (Player player : new ArrayList<Player>(this.eventWorld.getPlayers())) {
                this.restoreGameMode(player);
                this.runFallback(player);
            }
            this.scheduleUnloadRetry(operation, attempt, "players are still inside");
            return;
        }
        World worldToUnload = this.eventWorld;
        if (!Bukkit.unloadWorld(worldToUnload, false)) {
            this.scheduleUnloadRetry(operation, attempt, "Paper rejected the unload request");
            return;
        }
        this.eventWorld = null;
        this.dragon = null;
        this.deleteRuntimeAndClear(operation);
    }

    private void scheduleUnloadRetry(long operation, int attempt, String reason) {
        int maximum = Math.max(5, this.plugin.getConfig().getInt("world.reset.maximum-unload-attempts", 40));
        if (attempt >= maximum) {
            this.plugin.getLogger().severe("Runtime world reset paused because " + reason + ". The world was NOT deleted. Fix the cause and run /dragon stop again.");
            return;
        }
        long delay = Math.max(1L, this.plugin.getConfig().getLong("world.reset.retry-delay-ticks", 10L));
        if (this.resetTask != null) {
            this.resetTask.cancel();
        }
        this.resetTask = Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> this.attemptWorldUnload(operation, attempt + 1), delay);
    }

    private void deleteRuntimeAndClear(long operation) {
        Path runtime;
        if (this.resetDeleteQueued) {
            return;
        }
        this.resetDeleteQueued = true;
        if (this.resetTask != null) {
            this.resetTask.cancel();
        }
        this.resetTask = null;
        try {
            String runtimeName = this.activeRuntimeName == null ? this.runtimeWorldName() : this.activeRuntimeName;
            runtime = this.activeRuntimePath == null ? this.safeWorldPath(runtimeName) : this.activeRuntimePath;
        }
        catch (IllegalArgumentException exception) {
            this.resetDeleteQueued = false;
            this.plugin.getLogger().log(Level.SEVERE, "Refusing to delete an unsafe runtime world path", exception);
            return;
        }
        Path container = this.worldContainerPath();
        this.queueWorldIo(() -> {
            if (Files.exists(runtime, new LinkOption[0]) && !WorldFiles.isOwnedRuntime(container, runtime)) {
                this.plugin.getLogger().warning("Preserved an unmarked legacy runtime; no files deleted: " + String.valueOf(runtime));
                return;
            }
            WorldFiles.delete(container, runtime);
        }).whenComplete((ignored, error) -> this.runOnServerThread(() -> {
            if (operation != this.lifecycleGeneration || this.state != EventState.RESETTING) {
                return;
            }
            if (error != null) {
                this.resetDeleteQueued = false;
                this.plugin.getLogger().log(Level.SEVERE, "Could not delete runtime event world. The event remains in RESETTING state.", (Throwable)error);
                return;
            }
            this.clearSession();
        }));
    }

    private void clearSession() {
        this.cancelEventTasks();
        this.participants.clear();
        this.lateParticipants.clear();
        this.spectators.clear();
        this.departedParticipants.clear();
        this.exitPortalBlocks.clear();
        this.damage.clear();
        this.eventDeaths.clear();
        this.crystalsDestroyed.clear();
        this.explosionsTriggered.clear();
        this.explosiveOwners.clear();
        this.pendingBlockExplosions.clear();
        this.countedCrystals.clear();
        this.primedTnt.clear();
        this.aprilProjectiles.clear();
        this.originalGameModes.clear();
        this.originalAllowFlight.clear();
        this.originalFlying.clear();
        this.voidRescueCooldown.clear();
        this.cancelAllDisconnectGrace();
        this.activeTeleports.clear();
        this.aprilBoss = null;
        this.activeRuntimeName = null;
        this.activeRuntimePath = null;
        this.resultEventId = null;
        this.deleteRuntimeJournal();
        this.eventMode = EventMode.NORMAL;
        this.fightSecondsLeft = 0;
        this.closingSecondsLeft = 0;
        this.effectiveDragonHealth = 0.0;
        this.incomingDamageMultiplier = 1.0;
        this.dragonAttackMultiplier = 1.0;
        this.lastDragonLocation = null;
        this.dragonStationarySeconds = 0;
        this.aprilBarrageTicks = 0;
        this.aprilAmbientSoundTicks = 0;
        this.lastAprilHurtSoundTick = -1L;
        this.spawningScaledProjectile = false;
        this.statisticsRecorded = false;
        this.enraged = false;
        this.difficultyFighterCount = 0;
        this.resetDeleteQueued = false;
        this.secondsLeft = 0;
        this.prepareFuture = CompletableFuture.completedFuture(null);
        this.state = EventState.IDLE;
    }

    private Path runtimeJournal() {
        return this.plugin.getDataFolder().toPath().resolve("runtime-state.txt");
    }

    private void saveRuntimeJournal() throws IOException {
        Path journal = this.runtimeJournal();
        Path tmp = journal.resolveSibling(String.valueOf(journal.getFileName()) + ".tmp");
        Files.writeString(tmp, (CharSequence)(this.activeRuntimeName + "\n" + String.valueOf(this.worldContainerPath().relativize(this.activeRuntimePath)) + "\n"), new OpenOption[0]);
        try {
            Files.move(tmp, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, journal, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRuntimeJournal() {
        try {
            Files.deleteIfExists(this.runtimeJournal());
        }
        catch (IOException e) {
            this.plugin.getLogger().warning("Could not remove the runtime checkpoint: " + e.getMessage());
        }
    }

    void recoverAfterRestart() {
        this.shuttingDown = false;
        String runtimeName = this.runtimeWorldName();
        try {
            Path runtime = this.safeWorldPath(runtimeName);
            if (Files.isRegularFile(this.runtimeJournal(), new LinkOption[0])) {
                List<String> saved = Files.readAllLines(this.runtimeJournal());
                if (saved.size() != 2 || !saved.get(0).matches("[A-Za-z0-9._-]+")) {
                    throw new IOException("Invalid runtime checkpoint");
                }
                runtimeName = saved.get(0);
                runtime = this.worldContainerPath().resolve(saved.get(1)).normalize();
                WorldFiles.isOwnedRuntime(this.worldContainerPath(), runtime);
            }
            Path template = this.safeWorldPath(this.resolveTemplateWorldName());
            World loaded = Bukkit.getWorld(runtimeName);
            if (runtime.equals(template) || runtimeName.equals(this.resolveTemplateWorldName()) || loaded != null && loaded.getEnvironment() != World.Environment.THE_END) {
                throw new IOException("Runtime configuration refers to a template or non-End world");
            }
            this.activeRuntimeName = runtimeName;
            this.activeRuntimePath = runtime;
            this.eventWorld = loaded;
            this.recoveryBlocked = false;
            if (Files.exists(runtime, new LinkOption[0]) && !WorldFiles.isOwnedRuntime(this.worldContainerPath(), runtime)) {
                this.preservedRuntimeNames.add(runtimeName);
                this.plugin.getLogger().warning("Preserving an unmarked legacy Dragon folder: " + String.valueOf(runtime) + ". The next event will use a separate managed clone.");
            }
            this.evacuateAndReset();
        }
        catch (IOException | IllegalArgumentException e) {
            this.state = EventState.RESETTING;
            this.eventWorld = null;
            this.activeRuntimePath = null;
            this.activeRuntimeName = null;
            this.recoveryBlocked = true;
            this.plugin.getLogger().log(Level.SEVERE, "Dragon recovery paused; unsafe runtime configuration. No files changed.", e);
        }
    }

    private void updateDisplays() {
        if (this.eventWorld == null) {
            return;
        }
        List<String> live = this.damage.entrySet().stream().filter(e -> (Double)e.getValue() > 0.0 && !this.spectators.contains(e.getKey()) && !this.departedParticipants.contains(e.getKey())).sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).limit(5L).map(e -> Objects.toString(Bukkit.getOfflinePlayer((UUID)e.getKey()).getName(), ((UUID)e.getKey()).toString()) + " \u00b7 " + this.formatDamage((Double)e.getValue())).toList();
        this.displays.update(this.eventWorld, this.portalLocation(this.eventWorld.getEnderDragonBattle()), this.state, state == EventState.ACTIVE ? fightSecondsRemaining() : state == EventState.FINISHING ? closingSecondsRemaining() : Math.max(0, secondsLeft + 1),
                state == EventState.ACTIVE ? fighterCount() : playerCount(), live, live, live, live);
    }

    void shutdown() {
        this.shuttingDown = true;
        ++this.lifecycleGeneration;
        this.cancelEventTasks();
        this.cancelAllDisconnectGrace();
        Location exit = this.exitLocation();
        for (UUID uuid : new HashSet<UUID>(this.participants)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            this.restoreGameMode(player);
        }
        if (this.eventWorld != null) {
            for (Player player : new ArrayList<Player>(this.eventWorld.getPlayers())) {
                this.restoreGameMode(player);
                if (exit == null) continue;
                player.teleport(exit);
            }
            if (this.eventWorld.getPlayers().isEmpty() && !Bukkit.unloadWorld(this.eventWorld, false)) {
                this.plugin.getLogger().warning("Could not unload the Dragon runtime world during shutdown; it will be recovered on startup.");
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String managedRuntimeName = this.activeRuntimeName == null ? this.runtimeWorldName() : this.activeRuntimeName;
        this.cancelDisconnectGrace(uuid);
        if (this.eventMode.isExplosiveBoss() && this.state == EventState.ACTIVE) {
            Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> this.hideAprilDragonFrom(event.getPlayer()), 1L);
        }
        if ((event.getPlayer().getScoreboardTags().contains("townysmp_dragon_spectator") || event.getPlayer().getScoreboardTags().contains("townysmp_dragon_participant")) && (this.state == EventState.IDLE || this.state == EventState.RESETTING)) {
            event.getPlayer().removeScoreboardTag("townysmp_dragon_spectator");
            event.getPlayer().removeScoreboardTag("townysmp_dragon_participant");
            event.getPlayer().setGameMode(GameMode.SURVIVAL);
            event.getPlayer().setFlying(false);
            event.getPlayer().setAllowFlight(false);
        }
        if ((event.getPlayer().getWorld().getName().equals(managedRuntimeName) || this.preservedRuntimeNames.contains(event.getPlayer().getWorld().getName())) && (this.state == EventState.IDLE || this.state == EventState.RESETTING)) {
            this.runFallback(event.getPlayer());
        } else if (this.departedParticipants.contains(uuid)) {
            Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> this.returnPlayer(event.getPlayer()), 1L);
        } else if (this.participants.contains(uuid) && !this.departedParticipants.contains(uuid) && (this.state == EventState.JOINING || this.state == EventState.RESPAWNING || this.state == EventState.ACTIVE)) {
            Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> this.teleportToEvent(event.getPlayer()), 20L);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (this.state != EventState.ACTIVE && this.state != EventState.RESPAWNING || !this.participants.contains(uuid)) {
            return;
        }
        this.restoreGameMode(event.getPlayer());
        this.cancelDisconnectGrace(uuid);
        int grace = Math.max(0, this.plugin.getConfig().getInt("event.disconnect-grace-seconds", 60));
        if (grace == 0) {
            this.departedParticipants.add(uuid);
            Bukkit.getScheduler().runTask((Plugin)this.plugin, this::checkForBattleLoss);
            return;
        }
        this.disconnectGraceTasks.put(uuid, Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
            this.disconnectGraceTasks.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (this.state != EventState.ACTIVE && this.state != EventState.RESPAWNING || !this.participants.contains(uuid) || player != null && player.isOnline()) {
                return;
            }
            this.departedParticipants.add(uuid);
            this.checkForBattleLoss();
        }, (long)grace * 20L));
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onSpawnCommand(PlayerCommandPreprocessEvent event) {
        if (this.eventWorld == null || !event.getPlayer().getWorld().equals(this.eventWorld)) {
            return;
        }
        String command = event.getMessage().trim().split("\\s+", 2)[0];
        if (!command.equalsIgnoreCase("/spawn")) {
            return;
        }
        event.setCancelled(true);
        if (!this.leave(event.getPlayer())) {
            this.restoreGameMode(event.getPlayer());
            this.runFallback(event.getPlayer());
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onArenaBlockPlace(BlockPlaceEvent event) {
        if (this.isArenaBuildingBlocked(event.getPlayer())) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "block-building-disabled");
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onArenaBlockBreak(BlockBreakEvent event) {
        if (this.isArenaBuildingBlocked(event.getPlayer())) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "block-building-disabled");
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onUnauthorizedArenaEntry(PlayerTeleportEvent event) {
        Location destination = event.getTo();
        if (this.eventWorld == null || destination == null || destination.getWorld() == null || event.getFrom().getWorld().equals(this.eventWorld) || !destination.getWorld().equals(this.eventWorld) || this.participants.contains(event.getPlayer().getUniqueId()) || event.getPlayer().hasPermission("townysmp.dragon.admin")) {
            return;
        }
        event.setCancelled(true);
        this.messages.send(event.getPlayer(), "arena-private");
    }

    private boolean isArenaBuildingBlocked(Player player) {
        return this.eventWorld != null && player.getWorld().equals(this.eventWorld) && this.state != EventState.IDLE && this.state != EventState.RESETTING && !this.plugin.getConfig().getBoolean("event.block-building", true) && !player.hasPermission("townysmp.dragon.build");
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onPlayerLeavesArena(PlayerTeleportEvent event) {
        if (this.state != EventState.ACTIVE || this.eventWorld == null || !this.participants.contains(event.getPlayer().getUniqueId()) || !event.getFrom().getWorld().equals(this.eventWorld)) {
            return;
        }
        Location destination = event.getTo();
        if (destination != null && destination.getWorld() != null && destination.getWorld().equals(this.eventWorld)) {
            return;
        }
        this.departedParticipants.add(event.getPlayer().getUniqueId());
        this.restoreGameMode(event.getPlayer());
        Bukkit.getScheduler().runTask((Plugin)this.plugin, this::checkForBattleLoss);
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onEventEndPortalTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL || this.eventWorld == null || !event.getFrom().getWorld().equals(this.eventWorld)) {
            return;
        }
        event.setCancelled(true);
        if (this.state == EventState.FINISHING) {
            this.departedParticipants.add(event.getPlayer().getUniqueId());
            this.restoreGameMode(event.getPlayer());
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.runFallback(event.getPlayer()));
            return;
        }
        if (this.plugin.getConfig().getBoolean("event.lock-exit-until-victory", true)) {
            this.messages.send(event.getPlayer(), "exit-locked");
        }
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void onVanillaEndPortal(PlayerPortalEvent event) {
        if (this.eventWorld != null && event.getFrom().getWorld().equals(this.eventWorld) && this.state == EventState.FINISHING) {
            event.setCancelled(true);
            this.departedParticipants.add(event.getPlayer().getUniqueId());
            this.restoreGameMode(event.getPlayer());
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.runFallback(event.getPlayer()));
            return;
        }
        if (this.eventWorld != null && event.getFrom().getWorld().equals(this.eventWorld) && this.plugin.getConfig().getBoolean("event.lock-exit-until-victory", true) && (this.state == EventState.JOINING || this.state == EventState.RESPAWNING || this.state == EventState.ACTIVE)) {
            event.setCancelled(true);
            this.messages.send(event.getPlayer(), "exit-locked");
            return;
        }
        if (this.state != EventState.PREPARING || !this.plugin.getConfig().getBoolean("world.protect-vanilla-portal-during-copy", true) || !this.usesVanillaTemplate()) {
            return;
        }
        Location target = event.getTo();
        if (target == null || target.getWorld() == null || target.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        String vanillaEnd = this.plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end");
        if (!target.getWorld().getName().equals(vanillaEnd)) {
            return;
        }
        event.setCancelled(true);
        this.messages.send(event.getPlayer(), "end-temporarily-unavailable");
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void enforceArenaEndPortalLock(PlayerPortalEvent event) {
        if (this.eventWorld == null || !event.getFrom().getWorld().equals(this.eventWorld)) {
            return;
        }
        if (this.state == EventState.FINISHING || this.plugin.getConfig().getBoolean("event.lock-exit-until-victory", true) && (this.state == EventState.JOINING || this.state == EventState.RESPAWNING || this.state == EventState.ACTIVE)) {
            event.setCancelled(true);
        }
    }

    private CompletableFuture<Boolean> returnPlayer(Player player) {
        this.restoreGameMode(player);
        return this.runFallback(player);
    }

    private void restoreGameMode(Player player) {
        UUID uuid = player.getUniqueId();
        GameMode original = this.originalGameModes.remove(uuid);
        if (original != null) {
            player.setGameMode(original);
        }
        Boolean allowFlight = this.originalAllowFlight.remove(uuid);
        Boolean flying = this.originalFlying.remove(uuid);
        if (allowFlight != null) {
            player.setAllowFlight(allowFlight);
        }
        if (flying != null && player.getAllowFlight()) {
            player.setFlying(flying);
        }
        player.removeScoreboardTag("townysmp_dragon_spectator");
        player.removeScoreboardTag("townysmp_dragon_participant");
    }

    private CompletableFuture<Boolean> runFallback(Player player) {
        Location target = this.exitLocation();
        if (target == null) {
            this.plugin.getLogger().severe("No normal world is loaded and no Dragon exit location is configured.");
            return CompletableFuture.completedFuture(false);
        }
        return this.trackedTeleport(player, target);
    }

    private CompletableFuture<Boolean> trackedTeleport(Player player, Location target) {
        if (player == null || !player.isOnline() || target == null || target.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID uuid = player.getUniqueId();
        CompletableFuture<Boolean> existing = this.activeTeleports.get(uuid);
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        this.activeTeleports.put(uuid, result);
        try {
            player.teleportAsync(target.clone()).whenComplete((success, error) -> {
                this.activeTeleports.remove(uuid, result);
                if (error != null) {
                    this.plugin.getLogger().log(Level.WARNING, "Asynchronous Dragon Event teleport failed for " + player.getName(), (Throwable)error);
                    result.complete(false);
                } else {
                    result.complete(Boolean.TRUE.equals(success));
                }
            });
        }
        catch (RuntimeException exception) {
            this.activeTeleports.remove(uuid, result);
            this.plugin.getLogger().log(Level.WARNING, "Could not start Dragon Event teleport for " + player.getName(), exception);
            result.complete(false);
        }
        return result;
    }

    private Location exitLocation() {
        World target;
        String configuredWorld = this.plugin.getConfig().getString("world.exit-location.world", "").trim();
        World world2 = target = configuredWorld.isEmpty() ? null : Bukkit.getWorld(configuredWorld);
        if (target != null) {
            return new Location(target, this.plugin.getConfig().getDouble("world.exit-location.x"), this.plugin.getConfig().getDouble("world.exit-location.y"), this.plugin.getConfig().getDouble("world.exit-location.z"), (float)this.plugin.getConfig().getDouble("world.exit-location.yaw"), (float)this.plugin.getConfig().getDouble("world.exit-location.pitch"));
        }
        World fallback = Bukkit.getWorlds().stream().filter(world -> world.getEnvironment() == World.Environment.NORMAL).findFirst().orElseGet(() -> Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        return fallback == null ? null : fallback.getSpawnLocation().clone().add(0.5, 0.0, 0.5);
    }

    private Location portalLocation(DragonBattle battle) {
        if (this.eventWorld == null) {
            return null;
        }
        if (this.plugin.getConfig().getBoolean("world.portal-location.set", false)) {
            return new Location(this.eventWorld, this.plugin.getConfig().getInt("world.portal-location.x"), this.plugin.getConfig().getInt("world.portal-location.y"), this.plugin.getConfig().getInt("world.portal-location.z"));
        }
        return battle == null ? null : battle.getEndPortalLocation();
    }

    private Location detectPortalCenter(Player player) {
        Location origin = player.getLocation();
        World world = player.getWorld();
        long totalX = 0L;
        long totalZ = 0L;
        int portalY = Integer.MIN_VALUE;
        int count = 0;
        for (int x = origin.getBlockX() - 8; x <= origin.getBlockX() + 8; ++x) {
            for (int y = origin.getBlockY() - 8; y <= origin.getBlockY() + 4; ++y) {
                for (int z = origin.getBlockZ() - 8; z <= origin.getBlockZ() + 8; ++z) {
                    if (world.getBlockAt(x, y, z).getType() != Material.END_PORTAL) continue;
                    totalX += (long)x;
                    totalZ += (long)z;
                    portalY = Math.max(portalY, y);
                    ++count;
                }
            }
        }
        if (count > 0) {
            return new Location(world, Math.round((double)totalX / (double)count), portalY, Math.round((double)totalZ / (double)count));
        }
        Block pillarTop = origin.getBlock().getRelative(0, -1, 0);
        boolean vanillaPillar = pillarTop.getType() == Material.BEDROCK;
        for (int depth = 1; depth <= 3 && vanillaPillar; ++depth) {
            vanillaPillar = pillarTop.getRelative(0, -depth, 0).getType() == Material.BEDROCK;
        }
        if (vanillaPillar) {
            return pillarTop.getRelative(0, -3, 0).getLocation();
        }
        return pillarTop.getLocation();
    }

    private Map<String, String> locationReplacements(Location location) {
        return Map.of("world", location.getWorld() == null ? "-" : location.getWorld().getName(), "x", String.format(Locale.US, "%.1f", location.getX()), "y", String.format(Locale.US, "%.1f", location.getY()), "z", String.format(Locale.US, "%.1f", location.getZ()));
    }

    private Location joinLocation() {
        return new Location(this.eventWorld, this.plugin.getConfig().getDouble("world.join-location.x", 0.5), this.plugin.getConfig().getDouble("world.join-location.y", 65.0), this.plugin.getConfig().getDouble("world.join-location.z", 18.5), (float)this.plugin.getConfig().getDouble("world.join-location.yaw", 180.0), (float)this.plugin.getConfig().getDouble("world.join-location.pitch", 0.0));
    }

    private Location safeJoinLocation() {
        Material material;
        Location target = this.joinLocation();
        if (!this.plugin.getConfig().getBoolean("event.safe-respawn.enabled", true) || this.eventWorld == null) {
            return target;
        }
        Block floor = target.clone().add(0.0, -1.0, 0.0).getBlock();
        if (floor.getType().isSolid()) {
            return target;
        }
        int radius = Math.max(1, Math.min(6, this.plugin.getConfig().getInt("event.safe-respawn.platform-radius", 2)));
        try {
            material = Material.valueOf(this.plugin.getConfig().getString("event.safe-respawn.platform-material", "OBSIDIAN").toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception) {
            material = Material.OBSIDIAN;
        }
        int floorY = target.getBlockY() - 1;
        for (int x = -radius; x <= radius; ++x) {
            for (int z = -radius; z <= radius; ++z) {
                this.eventWorld.getBlockAt(target.getBlockX() + x, floorY, target.getBlockZ() + z).setType(material, false);
            }
        }
        this.eventWorld.getBlockAt(target.getBlockX(), target.getBlockY(), target.getBlockZ()).setType(Material.AIR, false);
        this.eventWorld.getBlockAt(target.getBlockX(), target.getBlockY() + 1, target.getBlockZ()).setType(Material.AIR, false);
        return target;
    }

    private Location spectatorLocation() {
        return new Location(this.eventWorld, this.plugin.getConfig().getDouble("world.spectator-location.x", 0.5), this.plugin.getConfig().getDouble("world.spectator-location.y", 100.0), this.plugin.getConfig().getDouble("world.spectator-location.z", 0.5), (float)this.plugin.getConfig().getDouble("world.spectator-location.yaw", 0.0), (float)this.plugin.getConfig().getDouble("world.spectator-location.pitch", 45.0));
    }

    private boolean spectatorLocationIsSafe(Location location) {
        if (this.eventWorld == null || location.getWorld() == null || !location.getWorld().equals(this.eventWorld)) {
            return false;
        }
        double minimumY = this.plugin.getConfig().getDouble("world.spectator-boundary.min-y", 0.0);
        double maximumY = this.plugin.getConfig().getDouble("world.spectator-boundary.max-y", 220.0);
        if (location.getY() < minimumY || location.getY() > maximumY) {
            return false;
        }
        return !this.plugin.getConfig().getBoolean("world.border.enabled", true) || this.eventWorld.getWorldBorder().isInside(location);
    }

    private void startSpectatorGuard() {
        this.cancelSpectatorGuard();
        long interval = Math.max(1L, this.plugin.getConfig().getLong("world.spectator-boundary.check-interval-ticks", 5L));
        this.spectatorGuardTask = Bukkit.getScheduler().runTaskTimer((Plugin)this.plugin, () -> {
            if (this.eventWorld == null) {
                return;
            }
            for (UUID uuid : new HashSet<UUID>(this.spectators)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline() || !player.getWorld().equals(this.eventWorld)) continue;
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                if (!player.getAllowFlight()) {
                    player.setAllowFlight(true);
                }
                if (!player.isFlying()) {
                    player.setFlying(true);
                }
                if (this.spectatorLocationIsSafe(player.getLocation())) continue;
                player.setVelocity(player.getVelocity().zero());
                this.trackedTeleport(player, this.spectatorLocation());
            }
        }, interval, interval);
    }

    private void configureWorldBorder() {
        if (this.eventWorld == null || !this.plugin.getConfig().getBoolean("world.border.enabled", true)) {
            return;
        }
        WorldBorder border = this.eventWorld.getWorldBorder();
        boolean centerOnPortal = this.plugin.getConfig().getBoolean("world.border.center-on-portal", true) && this.plugin.getConfig().getBoolean("world.portal-location.set", false);
        double centerX = centerOnPortal ? (double)this.plugin.getConfig().getInt("world.portal-location.x") + 0.5 : this.plugin.getConfig().getDouble("world.border.center-x", 0.0);
        double centerZ = centerOnPortal ? (double)this.plugin.getConfig().getInt("world.portal-location.z") + 0.5 : this.plugin.getConfig().getDouble("world.border.center-z", 0.0);
        border.setCenter(centerX, centerZ);
        border.setSize(Math.max(64.0, this.plugin.getConfig().getDouble("world.border.size", 512.0)));
        border.setWarningDistance(Math.max(0, this.plugin.getConfig().getInt("world.border.warning-distance", 16)));
    }

    private void removeEventEntities() {
        for (Entity entity : this.eventWorld.getEntities()) {
            if (!(entity instanceof EnderDragon) && !(entity instanceof EnderCrystal) && !(entity instanceof Item)) continue;
            entity.remove();
        }
    }

    private void closeExitPortal() {
        this.exitPortalBlocks.clear();
        this.sealExitPortal();
    }

    private void sealExitPortal() {
        if (!this.plugin.getConfig().getBoolean("event.lock-exit-until-victory", true) || this.eventWorld == null) {
            return;
        }
        DragonBattle battle = this.eventWorld.getEnderDragonBattle();
        if (battle == null) {
            return;
        }
        ArrayList<Location> centers = new ArrayList<Location>();
        Location configured = this.portalLocation(battle);
        Location vanilla = battle.getEndPortalLocation();
        if (configured != null) {
            centers.add(configured);
        }
        if (vanilla != null && centers.stream().noneMatch(location -> location.getBlockX() == vanilla.getBlockX() && location.getBlockY() == vanilla.getBlockY() && location.getBlockZ() == vanilla.getBlockZ())) {
            centers.add(vanilla);
        }
        for (Location center : centers) {
            for (int x = -4; x <= 4; ++x) {
                for (int y = -2; y <= 3; ++y) {
                    for (int z = -4; z <= 4; ++z) {
                        Block block = this.eventWorld.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
                        if (block.getType() != Material.END_PORTAL) continue;
                        Location location2 = block.getLocation();
                        if (!this.exitPortalBlocks.contains(location2)) {
                            this.exitPortalBlocks.add(location2);
                        }
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void openExitPortal() {
        if (this.plugin.getConfig().getBoolean("event.lock-exit-until-victory", true)) {
            for (Location location : this.exitPortalBlocks) {
                if (location.getWorld() == null) continue;
                location.getBlock().setType(Material.END_PORTAL, false);
            }
        }
    }

    private void rebuildSafeExitPortal() {
        if (this.eventWorld == null) {
            return;
        }
        DragonBattle battle = this.eventWorld.getEnderDragonBattle();
        Location center = this.portalLocation(battle);
        if (battle != null && center != null && this.plugin.getConfig().getBoolean("event.safe-exit.rebuild-vanilla-portal", true)) {
            try {
                if (this.plugin.getConfig().getBoolean("world.portal-location.set", false)) {
                    this.forceBattlePortalLocation(battle, center);
                }
                battle.generateEndPortal(true);
            }
            catch (RuntimeException exception) {
                this.plugin.getLogger().log(Level.WARNING, "Could not regenerate the safe Dragon exit fountain", exception);
            }
        }
        this.openExitPortal();
        if (center == null || !this.plugin.getConfig().getBoolean("event.safe-exit.platform.enabled", true)) {
            return;
        }
        int radius = Math.max(2, Math.min(12, this.plugin.getConfig().getInt("event.safe-exit.platform.radius", 9)));
        int floorY = center.getBlockY() - 1;
        for (int x = -radius; x <= radius; ++x) {
            for (int z = -radius; z <= radius; ++z) {
                Block floor = this.eventWorld.getBlockAt(center.getBlockX() + x, floorY, center.getBlockZ() + z);
                if (floor.getType().isSolid()) continue;
                floor.setType(Material.BEDROCK, false);
            }
        }
    }

    private Player resolvePlayer(Entity entity) {
        return this.resolvePlayer(entity, new HashSet<UUID>());
    }

    private Player resolvePlayer(Entity entity, Set<UUID> visited) {
        Player player;
        TNTPrimed tnt;
        Player player2;
        if (entity == null || !visited.add(entity.getUniqueId())) {
            return null;
        }
        if (entity instanceof Player) {
            Player player3 = (Player)entity;
            return player3;
        }
        UUID trackedOwner = this.explosiveOwners.get(entity.getUniqueId());
        if (trackedOwner != null && (player2 = Bukkit.getPlayer(trackedOwner)) != null) {
            return player2;
        }
        if (entity instanceof TNTPrimed && (tnt = (TNTPrimed)entity).getSource() != null && (player = this.resolvePlayer(tnt.getSource(), visited)) != null) {
            return player;
        }
        if (entity instanceof Projectile) {
            Projectile projectile = (Projectile)entity;
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player) {
                Player player4 = (Player)shooter;
                return player4;
            }
            if (shooter instanceof Entity) {
                Entity shooterEntity = (Entity)((Object)shooter);
                return this.resolvePlayer(shooterEntity, visited);
            }
        }
        return null;
    }

    private boolean isBossDamage(Entity damager) {
        if (damager == this.dragon || damager == this.aprilBoss) {
            return true;
        }
        if (damager.getScoreboardTags().contains("townysmp_april_projectile") || damager.getScoreboardTags().contains("townysmp_scaled_dragon_projectile")) {
            return true;
        }
        if (damager instanceof Projectile) {
            Projectile projectile = (Projectile)damager;
            return projectile.getShooter() == this.dragon || projectile.getShooter() == this.aprilBoss;
        }
        return false;
    }

    private boolean isAprilProjectile(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains("townysmp_april_projectile");
    }

    private void cancelTicker() {
        if (this.ticker != null) {
            this.ticker.cancel();
        }
        this.ticker = null;
    }

    private void cancelFinishTask() {
        if (this.finishTask != null) {
            this.finishTask.cancel();
        }
        this.finishTask = null;
    }

    private void cancelEventTasks() {
        if (this.displayTask != null) {
            this.displayTask.cancel();
        }
        this.displayTask = null;
        this.displays.clear();
        this.cancelTicker();
        this.cancelFinishTask();
        this.cancelFightTasks();
        this.cancelClosingCountdown();
        this.cancelSpectatorGuard();
        this.cancelAprilVisual();
        this.cancelVictoryFireworks();
        if (this.resetTask != null) {
            this.resetTask.cancel();
        }
        this.resetTask = null;
    }

    private void cancelFightTasks() {
        if (this.fightTimeoutTask != null) {
            this.fightTimeoutTask.cancel();
        }
        if (this.fightClockTask != null) {
            this.fightClockTask.cancel();
        }
        this.fightTimeoutTask = null;
        this.fightClockTask = null;
    }

    private void cancelClosingCountdown() {
        if (this.closingCountdownTask != null) {
            this.closingCountdownTask.cancel();
        }
        this.closingCountdownTask = null;
    }

    private void cancelSpectatorGuard() {
        if (this.spectatorGuardTask != null) {
            this.spectatorGuardTask.cancel();
        }
        this.spectatorGuardTask = null;
    }

    private void cancelAprilVisual() {
        if (this.aprilVisualTask != null) {
            this.aprilVisualTask.cancel();
        }
        this.aprilVisualTask = null;
        if (this.aprilBoss != null && this.aprilBoss.isValid()) {
            this.aprilBoss.remove();
        }
        this.aprilBoss = null;
    }

    private void cancelVictoryFireworks() {
        if (this.victoryFireworkTask != null) {
            this.victoryFireworkTask.cancel();
        }
        this.victoryFireworkTask = null;
    }

    private void cancelDisconnectGrace(UUID uuid) {
        BukkitTask task = this.disconnectGraceTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelAllDisconnectGrace() {
        for (BukkitTask task : this.disconnectGraceTasks.values()) {
            task.cancel();
        }
        this.disconnectGraceTasks.clear();
    }

    private static String formatTime(int seconds) {
        Duration duration = Duration.ofSeconds(Math.max(0, seconds));
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        long rest = duration.minusHours(hours).minusMinutes(minutes).toSeconds();
        StringBuilder result = new StringBuilder();
        if (hours > 0L) {
            result.append(hours).append("h");
        }
        if (minutes > 0L) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(minutes).append("m");
        }
        if (rest > 0L || result.isEmpty()) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(rest).append("s");
        }
        return result.toString();
    }





    @FunctionalInterface
    private static interface WorldIoOperation {
        public void run() throws IOException;
    }

    private record PendingExplosion(UUID owner, long expiresAt) {
    }
}

