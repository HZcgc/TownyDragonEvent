package net.townysmp.dragonevent;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameRule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.DragonFireball;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

final class DragonEventManager implements Listener {
    private static final int[][] CRYSTAL_OFFSETS = {{3, 0}, {0, 3}, {-3, 0}, {0, -3}};
    private record PendingExplosion(UUID owner, long expiresAt) {}
    @FunctionalInterface
    private interface WorldIoOperation { void run() throws IOException; }

    private final TownyDragonEventPlugin plugin;
    private final Messages messages;
    private final StatsManager stats;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> lateParticipants = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final Set<UUID> departedParticipants = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Map<UUID, Integer> eventDeaths = new HashMap<>();
    private final Map<UUID, Integer> crystalsDestroyed = new HashMap<>();
    private final Map<UUID, Integer> explosionsTriggered = new HashMap<>();
    private final Map<UUID, UUID> explosiveOwners = new HashMap<>();
    private final Map<String, PendingExplosion> pendingBlockExplosions = new HashMap<>();
    private final Set<UUID> countedCrystals = new HashSet<>();
    private final Deque<UUID> primedTnt = new ArrayDeque<>();
    private final Deque<UUID> aprilProjectiles = new ArrayDeque<>();
    private final Map<UUID, GameMode> originalGameModes = new HashMap<>();
    private final Map<UUID, Boolean> originalAllowFlight = new HashMap<>();
    private final Map<UUID, Boolean> originalFlying = new HashMap<>();
    private final Map<UUID, Long> voidRescueCooldown = new HashMap<>();
    private final Map<UUID, BukkitTask> disconnectGraceTasks = new HashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> activeTeleports = new ConcurrentHashMap<>();
    private final List<Location> exitPortalBlocks = new ArrayList<>();
    private CompletableFuture<Void> worldIoChain = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> prepareFuture = CompletableFuture.completedFuture(null);
    private EventState state = EventState.IDLE;
    private World eventWorld;
    private String activeRuntimeName;
    private EnderDragon dragon;
    private Creeper aprilCreeper;
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
    private boolean shuttingDown;
    private boolean resetDeleteQueued;
    private long lifecycleGeneration;
    private int difficultyFighterCount;

    DragonEventManager(TownyDragonEventPlugin plugin, Messages messages, StatsManager stats) {
        this.plugin = plugin;
        this.messages = messages;
        this.stats = stats;
    }

    EventState state() { return state; }
    int playerCount() { return participants.size(); }
    int secondsRemaining() { return Math.max(0, secondsLeft); }
    String timeRemaining() { return formatTime(secondsRemaining()); }
    boolean isRegistered(UUID uuid) { return participants.contains(uuid); }
    double currentDamage(UUID uuid) { return damage.getOrDefault(uuid, 0.0); }
    int currentDeaths(UUID uuid) { return eventDeaths.getOrDefault(uuid, 0); }
    int currentCrystals(UUID uuid) { return crystalsDestroyed.getOrDefault(uuid, 0); }
    int currentExplosions(UUID uuid) { return explosionsTriggered.getOrDefault(uuid, 0); }
    boolean isSpectator(UUID uuid) { return spectators.contains(uuid); }
    boolean hasDeparted(UUID uuid) { return departedParticipants.contains(uuid); }
    int fightSecondsRemaining() { return Math.max(0, fightSecondsLeft); }
    String fightTimeRemaining() { return formatTime(fightSecondsRemaining()); }
    int closingSecondsRemaining() { return Math.max(0, closingSecondsLeft); }
    String closingTimeRemaining() { return formatTime(closingSecondsRemaining()); }
    EventMode eventMode() { return eventMode; }
    int fighterCount() { return activeFighterCount(); }
    int spectatorCount() { return activeSpectatorCount(); }
    void setExitLocation(Player player) {
        Location location = player.getLocation();
        plugin.getConfig().set("world.exit-location.world", location.getWorld().getName());
        plugin.getConfig().set("world.exit-location.x", location.getX());
        plugin.getConfig().set("world.exit-location.y", location.getY());
        plugin.getConfig().set("world.exit-location.z", location.getZ());
        plugin.getConfig().set("world.exit-location.yaw", location.getYaw());
        plugin.getConfig().set("world.exit-location.pitch", location.getPitch());
        plugin.saveConfig();
        messages.send(player, "exit-location-set");
    }
    void setArenaSpawn(Player player) {
        if (!isTemplateWorld(player)) return;
        Location location = player.getLocation();
        plugin.getConfig().set("world.join-location.x", location.getX());
        plugin.getConfig().set("world.join-location.y", location.getY());
        plugin.getConfig().set("world.join-location.z", location.getZ());
        plugin.getConfig().set("world.join-location.yaw", location.getYaw());
        plugin.getConfig().set("world.join-location.pitch", location.getPitch());
        plugin.saveConfig();
        messages.send(player, "arena-spawn-set", locationReplacements(location));
    }
    void setPortalLocation(Player player) {
        if (!isTemplateWorld(player)) return;
        Location location = detectPortalCenter(player);
        plugin.getConfig().set("world.portal-location.set", true);
        plugin.getConfig().set("world.portal-location.x", location.getBlockX());
        plugin.getConfig().set("world.portal-location.y", location.getBlockY());
        plugin.getConfig().set("world.portal-location.z", location.getBlockZ());
        plugin.saveConfig();
        messages.send(player, "portal-location-set", locationReplacements(location));
        if (Math.abs(location.getBlockX()) > 16 || Math.abs(location.getBlockZ()) > 16) {
            messages.send(player, "portal-origin-warning");
        }
    }

    private boolean isTemplateWorld(Player player) {
        String templateWorld = resolveTemplateWorldName();
        if (player.getWorld().getName().equals(templateWorld)) return true;
        messages.send(player, "template-world-required", Map.of("world", templateWorld));
        return false;
    }

    private String resolveTemplateWorldName() {
        String configuredTemplate = plugin.getConfig().getString("world.template-folder", "").trim();
        if (!configuredTemplate.isEmpty()) return configuredTemplate;

        String automaticName = plugin.getConfig().getString("world.auto-template-name", "dragonevent_template").trim();
        if (!automaticName.isEmpty()) {
            Path automaticFolder = Bukkit.getWorldContainer().toPath().resolve(automaticName).normalize();
            if (Bukkit.getWorld(automaticName) != null || Files.isDirectory(automaticFolder)) return automaticName;
        }
        return plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end").trim();
    }

    private boolean usesVanillaTemplate() {
        return resolveTemplateWorldName().equals(
                plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end").trim());
    }
    int currentRank(UUID uuid) {
        if (spectators.contains(uuid) || departedParticipants.contains(uuid)
                || !damage.containsKey(uuid) || damage.getOrDefault(uuid, 0.0) <= 0) return 0;
        List<UUID> ranking = damage.entrySet().stream().filter(entry -> entry.getValue() > 0
                        && !spectators.contains(entry.getKey())
                        && !departedParticipants.contains(entry.getKey()))
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey).toList();
        int index = ranking.indexOf(uuid);
        return index < 0 ? 0 : index + 1;
    }
    String runtimeWorldName() {
        return plugin.getConfig().getString("world.runtime-name", "dragonevent_end").trim();
    }

    private Path worldContainerPath() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    private Path safeWorldPath(String worldName) {
        if (worldName == null || worldName.isBlank()
                || worldName.equals(".") || worldName.equals("..")
                || !worldName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Unsafe world folder name: " + worldName);
        }
        Path root = worldContainerPath();
        Path candidate = root.resolve(worldName).toAbsolutePath().normalize();
        if (candidate.equals(root) || candidate.getParent() == null || !candidate.getParent().equals(root)) {
            throw new IllegalArgumentException("World folder must be a direct child of " + root + ": " + worldName);
        }
        return candidate;
    }

    private synchronized CompletableFuture<Void> queueWorldIo(WorldIoOperation operation) {
        CompletableFuture<Void> next = worldIoChain.handle((ignored, previousError) -> null)
                .thenRunAsync(() -> {
                    try {
                        operation.run();
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                });
        worldIoChain = next.handle((ignored, error) -> null);
        return next;
    }

    private void runOnServerThread(Runnable action) {
        if (shuttingDown || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, action);
    }

    synchronized boolean start(Integer customSeconds, EventMode requestedMode) {
        if (state != EventState.IDLE) return false;
        Path container = worldContainerPath();
        String configuredTemplate = plugin.getConfig().getString("world.template-folder", "").trim();
        String sourceFolder = resolveTemplateWorldName();
        String runtimeName = runtimeWorldName();
        Path template;
        Path runtime;
        try {
            template = safeWorldPath(sourceFolder);
            runtime = safeWorldPath(runtimeName);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().severe(exception.getMessage());
            return false;
        }
        if (sourceFolder.equals(runtimeName) || template.equals(runtime)) {
            plugin.getLogger().severe("The event source world must differ from the runtime world.");
            return false;
        }
        try {
            if (Files.exists(runtime) && !WorldFiles.isOwnedRuntime(container, runtime)) {
                plugin.getLogger().severe("The runtime target already exists but is not owned by TownyDragonEvent: " + runtime);
                return false;
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not validate the runtime world folder", exception);
            return false;
        }
        if (Bukkit.getWorld(runtimeName) != null) {
            plugin.getLogger().severe("The runtime world " + runtimeName + " is still loaded. Use /dragon stop and check the console.");
            return false;
        }
        World loadedSource = Bukkit.getWorld(sourceFolder);
        if (loadedSource != null) template = loadedSource.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (loadedSource != null && loadedSource.getEnvironment() != World.Environment.THE_END) {
            plugin.getLogger().severe("The Dragon template world " + sourceFolder + " is not an End world.");
            return false;
        }
        eventMode = requestedMode == null ? EventMode.NORMAL : requestedMode;
        activeRuntimeName = runtimeName;
        state = EventState.PREPARING;
        long operation = ++lifecycleGeneration;
        secondsLeft = customSeconds == null
                ? plugin.getConfig().getInt("event.countdown-seconds", 10800)
                : Math.max(10, customSeconds);
        Bukkit.broadcast(messages.get("preparing"));
        if (configuredTemplate.isEmpty()
                && sourceFolder.equals(plugin.getConfig().getString("world.auto-template-name", "dragonevent_template").trim())) {
            plugin.getLogger().info("Using automatically detected Dragon template world: " + sourceFolder);
        }
        boolean reloadSource = loadedSource != null;
        if (loadedSource != null) {
            if (!loadedSource.getPlayers().isEmpty()) {
                plugin.getLogger().warning("Cannot clone source world " + sourceFolder + " while players are inside it.");
                resetFailedPreparation();
                return false;
            }
            loadedSource.save();
            if (!Bukkit.unloadWorld(loadedSource, true)) {
                plugin.getLogger().severe("Could not safely unload source world " + sourceFolder + " for cloning.");
                resetFailedPreparation();
                return false;
            }
        }
        Path finalTemplate = template;
        prepareFuture = queueWorldIo(() -> WorldFiles.copy(container, finalTemplate, runtime));
        prepareFuture.whenComplete((ignored, error) -> runOnServerThread(() -> {
            if (reloadSource && Bukkit.getWorld(sourceFolder) == null) {
                new WorldCreator(sourceFolder).environment(World.Environment.THE_END).createWorld();
            }
            if (operation != lifecycleGeneration || state != EventState.PREPARING) return;
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not prepare the event world", error);
                evacuateAndReset();
                return;
            }
            loadWorldAndStartCountdown();
        }));
        return true;
    }

    private void resetFailedPreparation() {
        state = EventState.IDLE;
        eventMode = EventMode.NORMAL;
        activeRuntimeName = null;
        secondsLeft = 0;
    }

    private void loadWorldAndStartCountdown() {
        if (state != EventState.PREPARING) return;
        String runtimeName = activeRuntimeName == null ? runtimeWorldName() : activeRuntimeName;
        eventWorld = new WorldCreator(runtimeName)
                .environment(World.Environment.THE_END)
                .generateStructures(false)
                .createWorld();
        if (eventWorld == null) {
            plugin.getLogger().severe("Paper could not load event world " + runtimeName);
            evacuateAndReset();
            return;
        }
        eventWorld.setAutoSave(false);
        eventWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        eventWorld.setGameRule(GameRule.LOCATOR_BAR,
                plugin.getConfig().getBoolean("world.locator-bar-enabled", false));
        eventWorld.setGameRule(GameRule.MOB_GRIEFING,
                plugin.getConfig().getBoolean("event.arena-destruction.dragon-griefing", true));
        eventWorld.setGameRule(GameRule.KEEP_INVENTORY, plugin.getConfig().getBoolean("event.death.keep-inventory", true));
        eventWorld.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, plugin.getConfig().getBoolean("event.death.immediate-respawn", true));
        eventWorld.setGameRule(GameRule.SHOW_DEATH_MESSAGES, true);
        configureWorldBorder();
        startSpectatorGuard();
        removeEventEntities();
        closeExitPortal();
        int joinSeconds = plugin.getConfig().getInt("event.join-seconds", 300);
        state = secondsLeft <= joinSeconds ? EventState.JOINING : EventState.SCHEDULED;
        boolean openedLobby = state == EventState.JOINING;
        if (openedLobby) {
            messages.broadcast("lobby-open", playerCountReplacement(), "lobby");
        }
        if (!openedLobby || secondsLeft != joinSeconds) announceCountdown();
        secondsLeft--;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::eventTick, 20L, 20L);
    }

    private void eventTick() {
        if (state != EventState.SCHEDULED && state != EventState.JOINING) return;
        boolean openedLobby = false;
        if (state == EventState.SCHEDULED
                && secondsLeft <= plugin.getConfig().getInt("event.join-seconds", 300)) {
            state = EventState.JOINING;
            messages.broadcast("lobby-open", playerCountReplacement(), "lobby");
            teleportRegisteredPlayers();
            openedLobby = true;
        }
        if (secondsLeft <= 0) {
            beginRespawn();
            return;
        }
        if (!openedLobby) announceCountdown();
        secondsLeft--;
    }

    private void announceCountdown() {
        if (secondsLeft <= 0) return;
        if (plugin.getConfig().getIntegerList("event.announcement-seconds").contains(secondsLeft)) {
            messages.broadcast("announcement", Map.of("time", formatTime(secondsLeft), "players", Integer.toString(playerCount())), "announcement");
        } else if (secondsLeft == plugin.getConfig().getInt("event.last-chance-seconds", 180)) {
            messages.broadcast("last-chance", Map.of("time", formatTime(secondsLeft), "players", Integer.toString(playerCount())), "last-chance");
        } else if (plugin.getConfig().getIntegerList("event.final-countdown-seconds").contains(secondsLeft)) {
            messages.broadcast("countdown", Map.of("time", formatTime(secondsLeft)), "countdown");
        }
    }

    boolean join(Player player) {
        boolean accepting = state == EventState.SCHEDULED || state == EventState.JOINING
                || state == EventState.RESPAWNING || state == EventState.ACTIVE;
        if (!accepting || eventWorld == null) {
            messages.send(player, "not-open");
            return false;
        }
        if (participants.contains(player.getUniqueId())) {
            if (!departedParticipants.contains(player.getUniqueId())
                    && (state == EventState.JOINING || state == EventState.RESPAWNING || state == EventState.ACTIVE)
                    && !player.getWorld().equals(eventWorld)) {
                teleportToEvent(player);
                messages.send(player, "joined");
                return true;
            }
            messages.send(player, "already-joined");
            return false;
        }
        cancelDisconnectGrace(player.getUniqueId());
        participants.add(player.getUniqueId());
        if (state == EventState.RESPAWNING || state == EventState.ACTIVE
                || (state == EventState.JOINING && eventWorld != null && player.getWorld().equals(eventWorld))) {
            lateParticipants.add(player.getUniqueId());
        }
        damage.put(player.getUniqueId(), 0.0);
        eventDeaths.put(player.getUniqueId(), 0);
        crystalsDestroyed.put(player.getUniqueId(), 0);
        explosionsTriggered.put(player.getUniqueId(), 0);
        if (state == EventState.SCHEDULED) {
            messages.send(player, "registered");
        } else {
            teleportToEvent(player);
            messages.send(player, "joined");
        }
        return true;
    }

    boolean leave(Player player) {
        UUID uuid = player.getUniqueId();
        if (!participants.contains(uuid)) return false;
        cancelDisconnectGrace(uuid);
        if (state == EventState.RESPAWNING || state == EventState.ACTIVE) {
            departedParticipants.add(uuid);
            restoreGameMode(player);
            runFallback(player);
            messages.send(player, "left-active");
            Bukkit.getScheduler().runTask(plugin, this::checkForBattleLoss);
            return true;
        }
        participants.remove(uuid);
        lateParticipants.remove(uuid);
        spectators.remove(uuid);
        damage.remove(uuid);
        eventDeaths.remove(uuid);
        crystalsDestroyed.remove(uuid);
        explosionsTriggered.remove(uuid);
        restoreGameMode(player);
        runFallback(player);
        messages.send(player, "left");
        return true;
    }

    private void teleportRegisteredPlayers() {
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) teleportToEvent(player);
        }
    }

    private void teleportToEvent(Player player) {
        if (eventWorld == null || !player.isOnline() || departedParticipants.contains(player.getUniqueId())) return;
        prepareParticipantForArena(player);
        hideAprilDragonFrom(player);
        if (player.getWorld().equals(eventWorld)) {
            hideAprilDragonFrom(player);
            if (state == EventState.ACTIVE) rescaleDifficultyForLateFighters();
            return;
        }
        trackedTeleport(player, safeJoinLocation()).whenComplete((success, error) -> runOnServerThread(() -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                restoreGameMode(player);
                if (player.isOnline()) messages.send(player, "teleport-failed");
                return;
            }
            if (!participants.contains(player.getUniqueId())
                    || departedParticipants.contains(player.getUniqueId())
                    || state == EventState.RESETTING || state == EventState.IDLE || state == EventState.FINISHING) {
                returnPlayer(player);
                return;
            }
            hideAprilDragonFrom(player);
            if (state == EventState.ACTIVE) rescaleDifficultyForLateFighters();
        }));
    }

    private void prepareParticipantForArena(Player player) {
        UUID uuid = player.getUniqueId();
        originalGameModes.putIfAbsent(uuid, player.getGameMode());
        originalAllowFlight.putIfAbsent(uuid, player.getAllowFlight());
        originalFlying.putIfAbsent(uuid, player.isFlying());
        player.addScoreboardTag("townysmp_dragon_participant");
        if (spectators.contains(uuid)) {
            player.addScoreboardTag("townysmp_dragon_spectator");
            player.setGameMode(GameMode.SPECTATOR);
            player.setAllowFlight(true);
            player.setFlying(true);
            return;
        }
        if (plugin.getConfig().getBoolean("event.player-state.force-survival", true)) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        if (plugin.getConfig().getBoolean("event.player-state.disable-flight", true)) {
            if (player.isFlying()) player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void beginRespawn() {
        cancelTicker();
        int minimum = plugin.getConfig().getInt("event.minimum-players", 1);
        if (activeFighterCount() < minimum) {
            Bukkit.broadcast(messages.get("not-enough"));
            finish(false);
            return;
        }
        DragonBattle battle = eventWorld.getEnderDragonBattle();
        if (battle == null) {
            plugin.getLogger().severe("The event world has no Ender Dragon battle state.");
            finish(false);
            return;
        }
        battle.setPreviouslyKilled(true);
        boolean configuredPortal = plugin.getConfig().getBoolean("world.portal-location.set", false);
        Location portal;
        if (configuredPortal) {
            portal = portalLocation(battle);
            if (portal == null || !forceBattlePortalLocation(battle, portal)) {
                plugin.getLogger().severe("Could not apply the configured Dragon portal center to Paper's DragonBattle.");
                finish(false);
                return;
            }
        } else {
            if (battle.getEndPortalLocation() == null) battle.generateEndPortal(false);
            portal = battle.getEndPortalLocation();
        }
        if (portal == null) {
            plugin.getLogger().severe("No Dragon portal center is available. Use /dragon setportal in the template map.");
            finish(false);
            return;
        }
        state = EventState.RESPAWNING;
        messages.broadcast("respawn-start", Map.of(), "respawn");
        Location center = portal.clone().add(0.5, 1.0, 0.5);
        List<EnderCrystal> respawnCrystals = new ArrayList<>();
        for (int[] offset : CRYSTAL_OFFSETS) {
            EnderCrystal crystal = eventWorld.spawn(center.clone().add(offset[0], 0, offset[1]), EnderCrystal.class);
            crystal.setShowingBottom(false);
            crystal.addScoreboardTag("townysmp_dragon_respawn");
            respawnCrystals.add(crystal);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!battle.initiateRespawn(respawnCrystals)) {
                plugin.getLogger().severe("Paper rejected the Dragon respawn sequence. Verify /dragon setportal and the template world.");
                finish(false);
            }
        }, 2L);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::watchRespawn, 20L, 20L);
    }

    /**
     * Paper does not expose a public setter for the DragonBattle portal center.
     * Without this, a custom fountain that differs slightly from the Vanilla
     * block pattern makes Minecraft generate a second fountain at the heightmap.
     * Paper 1.21.11 runs with Mojang mappings, so keep both known field names as
     * a guarded compatibility fallback and verify the value through Bukkit API.
     */
    private boolean forceBattlePortalLocation(DragonBattle battle, Location portal) {
        try {
            java.lang.reflect.Field handleField = findField(battle.getClass(), "handle");
            handleField.setAccessible(true);
            Object handle = handleField.get(battle);
            java.lang.reflect.Field locationField = findField(handle.getClass(), "exitPortalLocation", "portalLocation");
            locationField.setAccessible(true);
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object blockPos = blockPosClass.getConstructor(int.class, int.class, int.class).newInstance(
                    portal.getBlockX(), portal.getBlockY(), portal.getBlockZ());
            locationField.set(handle, blockPos);
            Location applied = battle.getEndPortalLocation();
            return applied != null
                    && applied.getBlockX() == portal.getBlockX()
                    && applied.getBlockY() == portal.getBlockY()
                    && applied.getBlockZ() == portal.getBlockZ();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Paper 1.21.11 Dragon portal location hook failed", exception);
            return false;
        }
    }

    private java.lang.reflect.Field findField(Class<?> type, String... names) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            for (String name : names) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // Continue with the next mapped name or superclass.
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException(String.join("/", names));
    }

    private void watchRespawn() {
        if (state != EventState.RESPAWNING) return;
        sealExitPortal();
        for (EnderDragon found : eventWorld.getEntitiesByClass(EnderDragon.class)) {
            activateDragon(found);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (eventWorld == null || !event.getLocation().getWorld().equals(eventWorld)) return;
        if (state == EventState.RESPAWNING && event.getEntity() instanceof EnderDragon spawned) {
            Bukkit.getScheduler().runTask(plugin, () -> activateDragon(spawned));
        }
        if (state == EventState.ACTIVE && event.getEntity() instanceof TNTPrimed tnt) {
            Player owner = resolvePlayer(tnt);
            if (owner != null && participants.contains(owner.getUniqueId())) {
                explosiveOwners.put(tnt.getUniqueId(), owner.getUniqueId());
                tnt.addScoreboardTag("townysmp_player_tnt");
                tnt.setYield((float) boundedExplosionPower(
                        "event.arena-destruction.explosion-power.player-tnt", 4.0));
            }
            registerLimitedEntity(tnt, primedTnt,
                    plugin.getConfig().getInt("event.arena-destruction.limits.maximum-primed-tnt", 50));
        }
    }

    private void activateDragon(EnderDragon spawned) {
        if (state != EventState.RESPAWNING) return;
        cancelTicker();
        dragon = spawned;
        difficultyFighterCount = Math.max(1, activeFighterCount());
        double health = configureDifficulty(difficultyFighterCount);
        AttributeInstance maxHealth = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) maxHealth.setBaseValue(health);
        dragon.setHealth(health);
        boolean aprilFools = eventMode == EventMode.APRIL_FOOLS;
        String nameKey = aprilFools ? "dragon-name-april" : "dragon-name";
        dragon.customName(messages.getRaw(nameKey, Map.of("health", String.format(Locale.US, "%.0f", effectiveDragonHealth))));
        if (aprilFools) startAprilFoolsVisual();
        eventWorld.setGameRule(GameRule.DO_MOB_SPAWNING, true);
        state = EventState.ACTIVE;
        lastDragonLocation = dragon.getLocation().clone();
        dragonStationarySeconds = 0;
        startFightClock();
        messages.broadcast(aprilFools ? "fight-start-april" : "fight-start",
                Map.of("health", String.format(Locale.US, "%.0f", effectiveDragonHealth),
                        "fighters", Integer.toString(activeFighterCount())), aprilFools ? "april-fight" : "fight");
        checkForBattleLoss();
    }

    private double configureDifficulty(int fighterCount) {
        double baseHealth = Math.max(1.0, plugin.getConfig().getDouble("event.dragon-health", 500.0));
        int fighters = Math.max(1, fighterCount);
        if (!plugin.getConfig().getBoolean("event.difficulty-scaling.enabled", true)) {
            effectiveDragonHealth = Math.max(1.0, baseHealth * modeHealthMultiplier());
            double physicalHealth = Math.min(1024.0, effectiveDragonHealth);
            incomingDamageMultiplier = Math.min(1.0, physicalHealth / effectiveDragonHealth);
            dragonAttackMultiplier = 1.0;
            return physicalHealth;
        }
        double healthPerFighter = Math.max(0.0,
                plugin.getConfig().getDouble("event.difficulty-scaling.health-per-additional-fighter", 250.0));
        double maximumEffective = Math.max(baseHealth,
                plugin.getConfig().getDouble("event.difficulty-scaling.maximum-effective-health", 6000.0));
        double normalModeHealth = Math.min(maximumEffective,
                baseHealth + Math.max(0, fighters - 1) * healthPerFighter);
        effectiveDragonHealth = Math.max(1.0, normalModeHealth * modeHealthMultiplier());
        double physicalHealth = Math.min(1024.0, effectiveDragonHealth);
        incomingDamageMultiplier = Math.min(1.0, physicalHealth / effectiveDragonHealth);
        double attackPerFighter = Math.max(0.0,
                plugin.getConfig().getDouble("event.difficulty-scaling.attack-multiplier-per-additional-fighter", 0.05));
        double maximumAttack = Math.max(1.0,
                plugin.getConfig().getDouble("event.difficulty-scaling.maximum-attack-multiplier", 2.0));
        dragonAttackMultiplier = Math.min(maximumAttack, 1.0 + Math.max(0, fighters - 1) * attackPerFighter);
        return physicalHealth;
    }

    private double modeHealthMultiplier() {
        if (eventMode != EventMode.APRIL_FOOLS) return 1.0;
        return Math.max(0.05, Math.min(1.0,
                plugin.getConfig().getDouble("april-fools.health-multiplier", 0.5)));
    }

    private void rescaleDifficultyForLateFighters() {
        if (state != EventState.ACTIVE || dragon == null || !dragon.isValid()) return;
        int fighters = Math.max(1, activeFighterCount());
        if (fighters <= difficultyFighterCount) return;
        AttributeInstance maxHealth = dragon.getAttribute(Attribute.MAX_HEALTH);
        double oldMaximum = maxHealth == null ? Math.max(1.0, dragon.getHealth()) : Math.max(1.0, maxHealth.getValue());
        double healthRatio = Math.max(0.0, Math.min(1.0, dragon.getHealth() / oldMaximum));
        difficultyFighterCount = fighters;
        double newMaximum = configureDifficulty(fighters);
        if (maxHealth != null) maxHealth.setBaseValue(newMaximum);
        dragon.setHealth(Math.max(0.01, Math.min(newMaximum, newMaximum * healthRatio)));
        messages.broadcast("difficulty-updated", Map.of(
                "health", String.format(Locale.US, "%.0f", effectiveDragonHealth),
                "fighters", Integer.toString(fighters)), "difficulty");
    }

    private void startAprilFoolsVisual() {
        cancelAprilVisual();
        aprilBarrageTicks = 0;
        aprilAmbientSoundTicks = 0;
        lastAprilHurtSoundTick = -1L;
        dragon.setInvisible(true);
        hideAprilDragonFromEveryone();
        spawnAprilCreeper();
        aprilVisualTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (eventMode != EventMode.APRIL_FOOLS || state != EventState.ACTIVE
                    || dragon == null || !dragon.isValid()) {
                cancelAprilVisual();
                return;
            }
            if (aprilCreeper == null || !aprilCreeper.isValid() || aprilCreeper.isDead()) {
                spawnAprilCreeper();
            }
            if (aprilCreeper == null || !aprilCreeper.isValid()) return;
            aprilCreeper.teleport(aprilCreeperLocation());
            aprilCreeper.setVelocity(new Vector());
            aprilCreeper.setIgnited(false);
            aprilCreeper.setFireTicks(0);
            aprilCreeper.setVisualFire(false);
            AttributeInstance maxHealth = aprilCreeper.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null && aprilCreeper.getHealth() < maxHealth.getValue()) {
                aprilCreeper.setHealth(maxHealth.getValue());
            }
            tickAprilFoolsEffects();
        }, 1L, 1L);
    }

    private void spawnAprilCreeper() {
        if (eventMode != EventMode.APRIL_FOOLS || state != EventState.ACTIVE
                || eventWorld == null || dragon == null || !dragon.isValid()) return;
        if (aprilCreeper != null && aprilCreeper.isValid() && !aprilCreeper.isDead()) return;
        Location spawn = aprilCreeperLocation();
        aprilCreeper = eventWorld.spawn(spawn, Creeper.class, creeper -> {
            creeper.setAI(false);
            creeper.setGravity(false);
            creeper.setSilent(true);
            creeper.setPersistent(true);
            creeper.setRemoveWhenFarAway(false);
            creeper.setCollidable(false);
            creeper.setPowered(true);
            creeper.setExplosionRadius(0);
            creeper.setIgnited(false);
            creeper.setFireTicks(0);
            creeper.setVisualFire(false);
            creeper.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
            creeper.addScoreboardTag("townysmp_april_creeper");
            creeper.customName(messages.getRaw("april-creeper-name", Map.of()));
            creeper.setCustomNameVisible(true);
            AttributeInstance scale = creeper.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(Math.max(1.0, Math.min(16.0,
                        plugin.getConfig().getDouble("april-fools.creeper-scale", 6.0))));
            }
        });
    }

    private void tickAprilFoolsEffects() {
        if (aprilCreeper == null || !aprilCreeper.isValid() || eventWorld == null) return;
        if (plugin.getConfig().getBoolean("april-fools.sounds.creeper-voice-enabled", false)
                && plugin.getConfig().getBoolean("april-fools.sounds.enabled", true)) {
            int ambientInterval = Math.max(20,
                    plugin.getConfig().getInt("april-fools.sounds.ambient.interval-ticks", 80));
            if (++aprilAmbientSoundTicks >= ambientInterval) {
                aprilAmbientSoundTicks = 0;
                playAprilSound("april-fools.sounds.ambient", "minecraft:entity.creeper.hurt", 1.8f, 0.55f);
            }
        }
        if (!plugin.getConfig().getBoolean("april-fools.projectiles.automatic-barrage.enabled", true)) return;
        int barrageInterval = Math.max(10,
                plugin.getConfig().getInt("april-fools.projectiles.automatic-barrage.interval-ticks", 50));
        if (++aprilBarrageTicks >= barrageInterval) {
            aprilBarrageTicks = 0;
            launchAprilBarrage();
        }
    }

    private void launchAprilBarrage() {
        if (eventWorld == null || aprilCreeper == null || !aprilCreeper.isValid()) return;
        List<Player> targets = participants.stream()
                .filter(uuid -> !spectators.contains(uuid) && !departedParticipants.contains(uuid))
                .map(Bukkit::getPlayer)
                .filter(player -> player != null && player.isOnline() && player.getWorld().equals(eventWorld))
                .toList();
        if (targets.isEmpty()) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Player target = targets.get(random.nextInt(targets.size()));
        Location source = aprilCreeper.getLocation().clone().add(0.0, -1.0, 0.0);
        Vector direction = target.getEyeLocation().toVector().subtract(source.toVector());
        if (direction.lengthSquared() < 0.01) direction = dragon.getLocation().getDirection();
        direction.normalize();
        int amount = Math.max(1, Math.min(12,
                plugin.getConfig().getInt("april-fools.projectiles.automatic-barrage.projectiles", 4)));
        double spread = Math.max(0.0,
                plugin.getConfig().getDouble("april-fools.projectiles.automatic-barrage.spread-degrees", 18.0));
        playAprilSound("april-fools.sounds.attack", "minecraft:entity.creeper.primed", 2.4f, 0.65f);
        for (int index = 0; index < amount; index++) {
            Vector shot = spreadDirection(direction, index, amount, spread);
            shot.setY(shot.getY() + random.nextDouble(-0.08, 0.10));
            if (shot.lengthSquared() < 0.01) shot = direction.clone();
            launchAprilProjectile(source, shot.normalize());
        }
    }

    private void playAprilSound(String path, String fallbackSound, float fallbackVolume, float fallbackPitch) {
        if (eventWorld == null
                || !plugin.getConfig().getBoolean("april-fools.sounds.creeper-voice-enabled", false)
                || !plugin.getConfig().getBoolean("april-fools.sounds.enabled", true)) return;
        String sound = plugin.getConfig().getString(path + ".sound", fallbackSound);
        if (sound == null || sound.isBlank()) return;
        float volume = (float) Math.max(0.0, plugin.getConfig().getDouble(path + ".volume", fallbackVolume));
        float pitch = (float) Math.max(0.01, Math.min(2.0,
                plugin.getConfig().getDouble(path + ".pitch", fallbackPitch)));
        boolean global = plugin.getConfig().getBoolean("april-fools.sounds.global-to-arena", true);
        Location source = aprilCreeper != null && aprilCreeper.isValid()
                ? aprilCreeper.getLocation() : safeJoinLocation();
        if (!global) {
            eventWorld.playSound(source, sound, volume, pitch);
            return;
        }
        for (Player player : eventWorld.getPlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    /**
     * Ender Dragons do not reliably respect Bukkit's invisible metadata on all
     * 1.21 clients. Hiding the carrier entity for each player keeps its flight
     * path and boss bar server-side while only the giant Creeper is rendered.
     */
    private void hideAprilDragonFrom(Player player) {
        if (eventMode != EventMode.APRIL_FOOLS || dragon == null || !dragon.isValid()
                || player == null || !player.isOnline()) return;
        player.hideEntity(plugin, dragon);
    }

    private void hideAprilDragonFromEveryone() {
        for (Player player : Bukkit.getOnlinePlayers()) hideAprilDragonFrom(player);
    }

    private Location aprilCreeperLocation() {
        Location location = dragon.getLocation().clone().add(0.0,
                plugin.getConfig().getDouble("april-fools.vertical-offset", -4.0), 0.0);
        location.setPitch(0.0f);
        return location;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSummoningCrystalDamage(EntityDamageEvent event) {
        if (state != EventState.RESPAWNING || !(event.getEntity() instanceof EnderCrystal crystal)
                || !crystal.getScoreboardTags().contains("townysmp_dragon_respawn")) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player player = resolvePlayer(byEntity.getDamager());
            if (player != null) messages.send(player, "crystal-protected");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onScaledDragonDamage(EntityDamageEvent event) {
        if (state != EventState.ACTIVE || event.getEntity() != dragon) return;
        event.setDamage(event.getDamage() * incomingDamageMultiplier);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAprilProjectileHitsCarrier(EntityDamageByEntityEvent event) {
        if (eventMode != EventMode.APRIL_FOOLS || state != EventState.ACTIVE
                || event.getEntity() != dragon || !isAprilProjectile(event.getDamager())) return;
        Vector flightVelocity = dragon.getVelocity().clone();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (eventMode == EventMode.APRIL_FOOLS && state == EventState.ACTIVE
                    && dragon != null && dragon.isValid()) {
                dragon.setVelocity(flightVelocity);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onScaledBossAttack(EntityDamageByEntityEvent event) {
        if (state != EventState.ACTIVE || !(event.getEntity() instanceof Player player)
                || !participants.contains(player.getUniqueId()) || !isBossDamage(event.getDamager())) return;
        event.setDamage(event.getDamage() * dragonAttackMultiplier);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDestroyed(EntityDamageByEntityEvent event) {
        if (state != EventState.ACTIVE || !(event.getEntity() instanceof EnderCrystal crystal)
                || eventWorld == null || !crystal.getWorld().equals(eventWorld)
                || !countedCrystals.add(crystal.getUniqueId())) return;
        Player player = resolvePlayer(event.getDamager());
        if (player != null && participants.contains(player.getUniqueId())) {
            crystalsDestroyed.merge(player.getUniqueId(), 1, Integer::sum);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (state != EventState.ACTIVE || event.getEntity() != dragon) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || !participants.contains(attacker.getUniqueId())
                || spectators.contains(attacker.getUniqueId())
                || departedParticipants.contains(attacker.getUniqueId())) return;
        double physicalDamage = Math.min(Math.max(0.0, event.getFinalDamage()), Math.max(0.0, dragon.getHealth()));
        double effectiveDamage = physicalDamage / Math.max(0.000001, incomingDamageMultiplier);
        damage.merge(attacker.getUniqueId(), effectiveDamage, Double::sum);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAprilCreeperDamage(EntityDamageEvent event) {
        if (eventMode != EventMode.APRIL_FOOLS || state != EventState.ACTIVE
                || aprilCreeper == null || event.getEntity() != aprilCreeper) return;
        boolean previouslyCancelled = event.isCancelled();
        double forwardedDamage = Math.max(0.0, event.getFinalDamage());
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity
                ? resolvePlayer(byEntity.getDamager()) : null;
        event.setCancelled(true);
        aprilCreeper.setIgnited(false);
        aprilCreeper.setFireTicks(0);
        aprilCreeper.setVisualFire(false);
        AttributeInstance maxHealth = aprilCreeper.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && aprilCreeper.getHealth() < maxHealth.getValue()) {
            aprilCreeper.setHealth(maxHealth.getValue());
        }
        long now = eventWorld == null ? 0L : eventWorld.getGameTime();
        int soundCooldown = Math.max(1,
                plugin.getConfig().getInt("april-fools.sounds.hurt.cooldown-ticks", 6));
        if (lastAprilHurtSoundTick < 0L || now - lastAprilHurtSoundTick >= soundCooldown) {
            lastAprilHurtSoundTick = now;
            playAprilSound("april-fools.sounds.hurt", "minecraft:entity.creeper.hurt", 1.6f, 0.75f);
        }
        if (previouslyCancelled || attacker == null || !participants.contains(attacker.getUniqueId())
                || dragon == null || !dragon.isValid() || forwardedDamage <= 0.0) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (state == EventState.ACTIVE && dragon != null && dragon.isValid()) {
                dragon.damage(forwardedDamage, attacker);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAprilCreeperCombust(EntityCombustEvent event) {
        if (eventMode != EventMode.APRIL_FOOLS || state != EventState.ACTIVE
                || aprilCreeper == null || event.getEntity() != aprilCreeper) return;
        event.setCancelled(true);
        aprilCreeper.setIgnited(false);
        aprilCreeper.setFireTicks(0);
        aprilCreeper.setVisualFire(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAprilCreeperPrime(ExplosionPrimeEvent event) {
        if (eventMode != EventMode.APRIL_FOOLS || state != EventState.ACTIVE
                || aprilCreeper == null || event.getEntity() != aprilCreeper) return;
        event.setCancelled(true);
        aprilCreeper.setIgnited(false);
        aprilCreeper.setExplosionRadius(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonProjectile(ProjectileLaunchEvent event) {
        if (spawningScaledProjectile || state != EventState.ACTIVE
                || !(event.getEntity() instanceof DragonFireball fireball) || fireball.getShooter() != dragon) return;
        Location source = fireball.getLocation().clone();
        Vector direction = fireball.getVelocity().clone();
        if (direction.lengthSquared() < 0.01) direction = dragon.getLocation().getDirection();
        direction.normalize();
        Vector finalDirection = direction;
        double originalSpeed = Math.max(0.8, fireball.getVelocity().length());
        int volley = projectileVolleySize();
        if (eventMode == EventMode.APRIL_FOOLS) {
            int multiplier = Math.max(1, Math.min(8,
                    plugin.getConfig().getInt("april-fools.projectiles.dragon-shot-multiplier", 3)));
            int maximum = Math.max(1, Math.min(24,
                    plugin.getConfig().getInt("april-fools.projectiles.maximum-per-dragon-shot", 9)));
            volley = Math.min(maximum, volley * multiplier);
        }
        int finalVolley = volley;
        if (eventMode == EventMode.APRIL_FOOLS) event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int index = eventMode == EventMode.APRIL_FOOLS ? 0 : 1; index < finalVolley; index++) {
                Vector spread = eventMode == EventMode.APRIL_FOOLS
                        ? spreadDirection(finalDirection, index, finalVolley)
                        : additionalSpreadDirection(finalDirection, index);
                if (eventMode == EventMode.APRIL_FOOLS) launchAprilProjectile(source, spread);
                else launchAdditionalDragonFireball(source, spread, originalSpeed);
            }
        });
    }

    private int projectileVolleySize() {
        if (!plugin.getConfig().getBoolean("event.difficulty-scaling.enabled", true)) return 1;
        int every = Math.max(1,
                plugin.getConfig().getInt("event.difficulty-scaling.projectiles.additional-every-fighters", 8));
        int maximum = Math.max(1,
                plugin.getConfig().getInt("event.difficulty-scaling.projectiles.maximum-per-volley", 3));
        return Math.min(maximum, 1 + Math.max(0, activeFighterCount() - 1) / every);
    }

    private Vector spreadDirection(Vector direction, int index, int total) {
        double spreadDegrees = plugin.getConfig().getDouble(
                "event.difficulty-scaling.projectiles.spread-degrees", 10.0);
        return spreadDirection(direction, index, total, spreadDegrees);
    }

    private Vector spreadDirection(Vector direction, int index, int total, double spreadDegrees) {
        if (total <= 1) return direction.clone();
        double spread = Math.toRadians(Math.max(0.0, spreadDegrees));
        double offset = (index - (total - 1) / 2.0) * spread;
        return direction.clone().rotateAroundY(offset).normalize();
    }

    private Vector additionalSpreadDirection(Vector direction, int index) {
        double spread = Math.toRadians(Math.max(0.0,
                plugin.getConfig().getDouble("event.difficulty-scaling.projectiles.spread-degrees", 10.0)));
        int step = (index + 1) / 2;
        double offset = (index % 2 == 1 ? -1.0 : 1.0) * step * spread;
        return direction.clone().rotateAroundY(offset).normalize();
    }

    private void launchAdditionalDragonFireball(Location source, Vector direction, double speed) {
        if (state != EventState.ACTIVE || eventWorld == null || dragon == null || !dragon.isValid()) return;
        spawningScaledProjectile = true;
        try {
            DragonFireball extra = eventWorld.spawn(source, DragonFireball.class);
            extra.addScoreboardTag("townysmp_scaled_dragon_projectile");
            extra.setShooter(dragon);
            extra.setDirection(direction);
            extra.setVelocity(direction.clone().multiply(speed));
        } finally {
            spawningScaledProjectile = false;
        }
    }

    private void launchAprilProjectile(Location source, Vector direction) {
        if (eventMode != EventMode.APRIL_FOOLS || state != EventState.ACTIVE || eventWorld == null) return;
        double speed = Math.max(0.2, plugin.getConfig().getDouble("april-fools.projectiles.speed", 1.1));
        double tntChance = Math.max(0.0, Math.min(1.0,
                plugin.getConfig().getDouble("april-fools.projectiles.tnt-chance", 0.5)));
        if (ThreadLocalRandom.current().nextDouble() < tntChance) {
            TNTPrimed tnt = eventWorld.spawn(source, TNTPrimed.class);
            tnt.setFuseTicks(Math.max(10, plugin.getConfig().getInt("april-fools.projectiles.tnt-fuse-ticks", 45)));
            tnt.setYield((float) boundedExplosionPower(
                    "event.arena-destruction.explosion-power.april-tnt", 5.0));
            tnt.setVelocity(direction.clone().multiply(speed));
            tnt.addScoreboardTag("townysmp_april_projectile");
            registerLimitedEntity(tnt, primedTnt,
                    plugin.getConfig().getInt("event.arena-destruction.limits.maximum-primed-tnt", 50));
            registerLimitedEntity(tnt, aprilProjectiles,
                    plugin.getConfig().getInt("event.arena-destruction.limits.maximum-april-projectiles", 20));
            eventWorld.playSound(source, "minecraft:entity.creeper.primed", 1.0f, 0.8f);
            return;
        }
        LargeFireball ghastFireball = eventWorld.spawn(source, LargeFireball.class);
        ghastFireball.setShooter(aprilCreeper != null ? aprilCreeper : dragon);
        ghastFireball.setDirection(direction);
        ghastFireball.setVelocity(direction.clone().multiply(speed));
        ghastFireball.setYield((float) boundedExplosionPower(
                "event.arena-destruction.explosion-power.april-fireball",
                plugin.getConfig().getDouble("april-fools.projectiles.fireball-yield", 2.0)));
        ghastFireball.setIsIncendiary(false);
        ghastFireball.addScoreboardTag("townysmp_april_projectile");
        registerLimitedEntity(ghastFireball, aprilProjectiles,
                plugin.getConfig().getInt("event.arena-destruction.limits.maximum-april-projectiles", 20));
        eventWorld.playSound(source, "minecraft:entity.ghast.shoot", 1.0f, 0.8f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArenaEntityExplosion(EntityExplodeEvent event) {
        if (eventWorld == null || !event.getLocation().getWorld().equals(eventWorld)) return;
        Player owner = resolvePlayer(event.getEntity());
        if (state == EventState.ACTIVE && owner != null && participants.contains(owner.getUniqueId())) {
            explosionsTriggered.merge(owner.getUniqueId(), 1, Integer::sum);
        }
        primedTnt.remove(event.getEntity().getUniqueId());
        aprilProjectiles.remove(event.getEntity().getUniqueId());
        explosiveOwners.remove(event.getEntity().getUniqueId());
        if (event.getEntity().getScoreboardTags().contains("townysmp_april_finale")) {
            event.blockList().clear();
            event.setYield(0.0f);
            return;
        }
        if (!isArenaDestructionAllowed(event.getLocation().getWorld())) {
            event.blockList().clear();
            event.setYield(0.0f);
            return;
        }
        if (plugin.getConfig().getBoolean("event.arena-destruction.force-explosions", true)) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArenaBlockExplosion(BlockExplodeEvent event) {
        if (eventWorld == null || !event.getBlock().getWorld().equals(eventWorld)) return;
        PendingExplosion pending = pendingBlockExplosions.remove(blockKey(event.getBlock()));
        if (state == EventState.ACTIVE && pending != null && pending.expiresAt() >= System.currentTimeMillis()
                && participants.contains(pending.owner())) {
            explosionsTriggered.merge(pending.owner(), 1, Integer::sum);
        }
        if (isArenaDestructionAllowed(event.getBlock().getWorld())
                && plugin.getConfig().getBoolean("event.arena-destruction.force-explosions", true)) {
            event.setCancelled(false);
        } else if (!isArenaDestructionAllowed(event.getBlock().getWorld())) {
            event.blockList().clear();
            event.setYield(0.0f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPrimesBlockExplosion(PlayerInteractEvent event) {
        if (state != EventState.ACTIVE || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || eventWorld == null
                || !event.getClickedBlock().getWorld().equals(eventWorld)
                || !participants.contains(event.getPlayer().getUniqueId())) return;
        Block block = event.getClickedBlock();
        boolean bed = block.getType().name().endsWith("_BED");
        boolean chargedAnchor = block.getType() == Material.RESPAWN_ANCHOR
                && block.getBlockData() instanceof RespawnAnchor anchor && anchor.getCharges() > 0;
        if (bed || chargedAnchor) {
            pendingBlockExplosions.put(blockKey(block),
                    new PendingExplosion(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 2000L));
        }
    }

    private boolean isArenaDestructionAllowed(World world) {
        return state == EventState.ACTIVE && eventWorld != null && world.equals(eventWorld)
                && plugin.getConfig().getBoolean("event.arena-destruction.enabled", true);
    }

    private double boundedExplosionPower(String path, double fallback) {
        return Math.max(0.0, Math.min(20.0, plugin.getConfig().getDouble(path, fallback)));
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
        if (maximum == 0) return;
        while (queue.size() > maximum) {
            UUID oldest = queue.removeFirst();
            Entity limited = Bukkit.getEntity(oldest);
            if (limited != null && limited.isValid()) limited.remove();
            explosiveOwners.remove(oldest);
            primedTnt.remove(oldest);
            aprilProjectiles.remove(oldest);
        }
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void startFightClock() {
        cancelFightTasks();
        fightSecondsLeft = Math.max(60, plugin.getConfig().getInt("event.fight-time-limit-seconds", 900));
        fightClockTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != EventState.ACTIVE) return;
            if (fightSecondsLeft == 600 || fightSecondsLeft == 300 || fightSecondsLeft == 60) {
                Bukkit.broadcast(messages.get("fight-time-warning", Map.of("time", formatTime(fightSecondsLeft))));
            }
            sendFightActionBar();
            runDragonWatchdog();
            fightSecondsLeft--;
        }, 20L, 20L);
        fightTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != EventState.ACTIVE) return;
            finishDefeat("fight-timeout", Map.of("limit", formatTime(
                    plugin.getConfig().getInt("event.fight-time-limit-seconds", 900))));
        }, fightSecondsLeft * 20L);
    }

    private void sendFightActionBar() {
        if (eventWorld == null || !plugin.getConfig().getBoolean("event.fight-actionbar.enabled", true)) return;
        Map<String, String> replacements = Map.of(
                "fighters", Integer.toString(activeFighterCount()),
                "spectators", Integer.toString(activeSpectatorCount()),
                "time", formatTime(fightSecondsLeft));
        for (Player player : eventWorld.getPlayers()) {
            player.sendActionBar(messages.getRaw("fight-actionbar", replacements));
        }
    }

    private int activeFighterCount() {
        int fighters = 0;
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (!spectators.contains(uuid) && !departedParticipants.contains(uuid)
                    && player != null && player.isOnline() && eventWorld != null && player.getWorld().equals(eventWorld)) {
                fighters++;
            }
        }
        return fighters;
    }

    private int activeSpectatorCount() {
        int count = 0;
        for (UUID uuid : spectators) {
            Player player = Bukkit.getPlayer(uuid);
            if (!departedParticipants.contains(uuid) && player != null && player.isOnline()
                    && eventWorld != null && player.getWorld().equals(eventWorld)) count++;
        }
        return count;
    }

    private void runDragonWatchdog() {
        if (!plugin.getConfig().getBoolean("event.dragon-watchdog.enabled", true)
                || dragon == null || !dragon.isValid() || eventWorld == null) return;
        Location current = dragon.getLocation();
        double minimumY = plugin.getConfig().getDouble("event.dragon-watchdog.minimum-y", -20.0);
        double maximumY = plugin.getConfig().getDouble("event.dragon-watchdog.maximum-y", 300.0);
        if (eventMode == EventMode.APRIL_FOOLS) {
            Location center = portalLocation(eventWorld.getEnderDragonBattle());
            if (center != null) {
                double aprilMaximum = Math.max(30.0,
                        plugin.getConfig().getDouble("april-fools.carrier.maximum-height-above-portal", 65.0));
                maximumY = Math.min(maximumY, center.getY() + aprilMaximum);
            }
        }
        boolean outside = current.getY() < minimumY || current.getY() > maximumY
                || (plugin.getConfig().getBoolean("world.border.enabled", true)
                && !eventWorld.getWorldBorder().isInside(current));
        double movement = Math.max(0.25,
                plugin.getConfig().getDouble("event.dragon-watchdog.minimum-movement-blocks", 2.0));
        if (lastDragonLocation != null && lastDragonLocation.getWorld() == current.getWorld()
                && lastDragonLocation.distanceSquared(current) < movement * movement
                && !dragonMayHoverAtPortal()) {
            dragonStationarySeconds++;
        } else {
            dragonStationarySeconds = 0;
        }
        lastDragonLocation = current.clone();
        int maximumStationary = Math.max(10,
                plugin.getConfig().getInt("event.dragon-watchdog.maximum-stationary-seconds", 25));
        if (!outside && dragonStationarySeconds < maximumStationary) return;
        Location rescue = dragonRescueLocation();
        dragon.teleport(rescue);
        dragon.setVelocity(new Vector());
        dragon.setPhase(eventMode == EventMode.APRIL_FOOLS
                ? EnderDragon.Phase.LAND_ON_PORTAL : EnderDragon.Phase.CIRCLING);
        dragonStationarySeconds = 0;
        lastDragonLocation = rescue.clone();
        for (Player player : eventWorld.getPlayers()) messages.send(player, "dragon-watchdog-rescue");
    }

    private boolean dragonMayHoverAtPortal() {
        String phase = dragon.getPhase().name();
        return phase.equals("LAND_ON_PORTAL") || phase.equals("BREATH_ATTACK")
                || phase.equals("SEARCH_FOR_BREATH_ATTACK_TARGET") || phase.equals("ROAR_BEFORE_ATTACK")
                || phase.equals("DYING");
    }

    private Location dragonRescueLocation() {
        DragonBattle battle = eventWorld.getEnderDragonBattle();
        Location center = portalLocation(battle);
        if (center == null) center = joinLocation();
        double offset = eventMode == EventMode.APRIL_FOOLS
                ? plugin.getConfig().getDouble("april-fools.carrier.rescue-height-above-portal", 45.0)
                : plugin.getConfig().getDouble("event.dragon-watchdog.rescue-height-offset", 70.0);
        double y = Math.max(eventWorld.getMinHeight() + 10.0,
                Math.min(eventWorld.getMaxHeight() - 10.0, center.getY() + offset));
        return new Location(eventWorld, center.getX() + 0.5, y, center.getZ() + 0.5, 0.0f, 0.0f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getScoreboardTags().contains("townysmp_april_creeper")) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            if (eventMode == EventMode.APRIL_FOOLS && state == EventState.ACTIVE) {
                Bukkit.getScheduler().runTask(plugin, this::spawnAprilCreeper);
            }
            return;
        }
        if (state == EventState.ACTIVE && event.getEntity() == dragon) {
            Location finaleLocation = aprilCreeper != null && aprilCreeper.isValid()
                    ? aprilCreeper.getLocation().clone() : dragon.getLocation().clone();
            cancelAprilVisual();
            rebuildSafeExitPortal();
            if (eventMode == EventMode.APRIL_FOOLS) startAprilFoolsFinale(finaleLocation);
            finish(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVictoryFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getScoreboardTags().contains("townysmp_dragon_victory")
                || event.getDamager() instanceof Creeper creeper
                && creeper.getScoreboardTags().contains("townysmp_april_finale")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggDrop(ItemSpawnEvent event) {
        if (eventWorld != null && event.getLocation().getWorld().equals(eventWorld)
                && event.getEntity().getItemStack().getType() == Material.DRAGON_EGG) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (eventWorld == null || !player.getWorld().equals(eventWorld)
                || !participants.contains(player.getUniqueId())) return;
        if (plugin.getConfig().getBoolean("event.death.keep-inventory", true)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        if (state != EventState.RESPAWNING && state != EventState.ACTIVE) return;
        int deaths = eventDeaths.merge(player.getUniqueId(), 1, Integer::sum);
        int maximum = plugin.getConfig().getInt("event.death.spectator-after-deaths", 5);
        if (maximum > 0 && deaths >= maximum) spectators.add(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (eventWorld == null || !player.getWorld().equals(eventWorld)
                || !participants.contains(player.getUniqueId())
                || state == EventState.IDLE || state == EventState.RESETTING) return;
        if (state == EventState.FINISHING) {
            Location exit = exitLocation();
            if (exit != null) event.setRespawnLocation(exit);
            departedParticipants.add(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> restoreGameMode(player));
            return;
        }
        event.setRespawnLocation(safeJoinLocation());
        int protection = Math.max(0, plugin.getConfig().getInt("event.death.respawn-protection-seconds", 5));
        int slowFalling = Math.max(0, plugin.getConfig().getInt("event.death.slow-falling-seconds", 8));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (spectators.contains(player.getUniqueId())) {
                originalGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
                originalAllowFlight.putIfAbsent(player.getUniqueId(), player.getAllowFlight());
                originalFlying.putIfAbsent(player.getUniqueId(), player.isFlying());
                player.addScoreboardTag("townysmp_dragon_spectator");
                player.setGameMode(GameMode.SPECTATOR);
                player.setAllowFlight(true);
                player.setFlying(true);
                trackedTeleport(player, spectatorLocation());
                messages.send(player, "eliminated", Map.of("deaths", Integer.toString(eventDeaths.getOrDefault(player.getUniqueId(), 0))));
                checkForBattleLoss();
                return;
            }
            if (protection > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, protection * 20, 4, false, false, true));
            }
            if (slowFalling > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, slowFalling * 20, 0, false, false, true));
            }
            runRespawnCommands(player);
            int maximum = Math.max(1, plugin.getConfig().getInt("event.death.spectator-after-deaths", 5));
            int remaining = Math.max(0, maximum - eventDeaths.getOrDefault(player.getUniqueId(), 0));
            messages.send(player, "respawned", Map.of(
                    "seconds", Integer.toString(protection),
                    "remaining", Integer.toString(remaining),
                    "maximum", Integer.toString(maximum)));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidFall(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location destination = event.getTo();
        if (!plugin.getConfig().getBoolean("event.void-rescue.enabled", true)
                || eventWorld == null || !player.getWorld().equals(eventWorld)
                || !participants.contains(player.getUniqueId()) || spectators.contains(player.getUniqueId())
                || destination == null
                || destination.getY() > plugin.getConfig().getDouble("event.void-rescue.trigger-y", -10.0)) return;
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("event.void-rescue.cooldown-seconds", 10) * 1000L;
        if (now - voidRescueCooldown.getOrDefault(player.getUniqueId(), 0L) < cooldown) return;
        voidRescueCooldown.put(player.getUniqueId(), now);
        player.teleport(safeJoinLocation());
        int slowFalling = plugin.getConfig().getInt("event.void-rescue.slow-falling-seconds", 8);
        if (slowFalling > 0) player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, slowFalling * 20, 0, false, false, true));
        messages.send(player, "void-rescued");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorCrossesBorder(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location destination = event.getTo();
        if (destination == null || eventWorld == null || !player.getWorld().equals(eventWorld)
                || !spectators.contains(player.getUniqueId())
                || !plugin.getConfig().getBoolean("world.border.enabled", true)
                || spectatorLocationIsSafe(destination)) return;
        player.setAllowFlight(true);
        player.setFlying(true);
        event.setTo(spectatorLocation());
        messages.send(player, "spectator-boundary");
    }

    private void runRespawnCommands(Player player) {
        for (String command : plugin.getConfig().getStringList("event.death.respawn-commands")) {
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
        if (state == EventState.FINISHING || state == EventState.RESETTING || state == EventState.IDLE) return;
        cancelTicker();
        cancelFightTasks();
        state = EventState.FINISHING;
        if (victory) {
            distributeRewards();
            int delay = Math.max(5, plugin.getConfig().getInt("event.finish-delay-seconds", 120));
            messages.broadcast("victory", Map.of("time", formatTime(delay)), "victory");
            startVictoryFireworks();
            startClosingCountdown(delay);
        } else {
            finishTask = Bukkit.getScheduler().runTaskLater(plugin, this::evacuateAndReset, 20L);
        }
    }

    private void finishDefeat(String messageKey, Map<String, String> replacements) {
        if (state != EventState.ACTIVE) return;
        cancelTicker();
        cancelFightTasks();
        state = EventState.FINISHING;
        cancelAprilVisual();
        if (dragon != null && dragon.isValid()) {
            dragon.setInvulnerable(true);
            dragon.setAI(false);
            dragon.setSilent(true);
            dragon.setVelocity(new Vector());
            dragon.setPhase(EnderDragon.Phase.HOVER);
        }
        recordDefeatStatistics();
        rebuildSafeExitPortal();
        int delay = Math.max(5, plugin.getConfig().getInt("event.finish-delay-seconds", 120));
        Map<String, String> values = new HashMap<>(replacements);
        values.put("time", formatTime(delay));
        messages.broadcast(messageKey, values, "defeat");
        startClosingCountdown(delay);
    }

    private void startClosingCountdown(int seconds) {
        cancelClosingCountdown();
        closingSecondsLeft = Math.max(1, seconds);
        sendClosingActionBar();
        closingCountdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            closingSecondsLeft--;
            if (closingSecondsLeft <= 0) {
                cancelClosingCountdown();
                return;
            }
            sendClosingActionBar();
        }, 20L, 20L);
        finishTask = Bukkit.getScheduler().runTaskLater(plugin, this::evacuateAndReset, closingSecondsLeft * 20L);
    }

    private void sendClosingActionBar() {
        if (eventWorld == null) return;
        Map<String, String> replacements = Map.of("time", formatTime(closingSecondsLeft));
        for (Player player : eventWorld.getPlayers()) {
            player.sendActionBar(messages.getRaw("closing-actionbar", replacements));
        }
    }

    private void startVictoryFireworks() {
        cancelVictoryFireworks();
        if (eventWorld == null || !plugin.getConfig().getBoolean("victory-fireworks.enabled", true)) return;
        int waves = Math.max(1, plugin.getConfig().getInt("victory-fireworks.waves", 4));
        int interval = Math.max(2, plugin.getConfig().getInt("victory-fireworks.interval-ticks", 12));
        int[] remaining = {waves};
        victoryFireworkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != EventState.FINISHING || eventWorld == null || remaining[0]-- <= 0) {
                cancelVictoryFireworks();
                return;
            }
            launchVictoryFireworkWave();
        }, 0L, interval);
    }

    private void launchVictoryFireworkWave() {
        DragonBattle battle = eventWorld.getEnderDragonBattle();
        Location center = portalLocation(battle);
        if (center == null) center = joinLocation();
        int amount = Math.max(1, plugin.getConfig().getInt("victory-fireworks.fireworks-per-wave", 5));
        int power = Math.max(0, Math.min(2, plugin.getConfig().getInt("victory-fireworks.power", 1)));
        Color[] colors = {
                Color.fromRGB(255, 43, 214),
                Color.fromRGB(56, 255, 101),
                Color.fromRGB(255, 215, 0),
                Color.fromRGB(55, 220, 255)
        };
        FireworkEffect.Type[] types = {
                FireworkEffect.Type.BALL_LARGE,
                FireworkEffect.Type.STAR,
                FireworkEffect.Type.BURST
        };
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < amount; index++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double radius = random.nextDouble(2.0, 10.0);
            Location launch = center.clone().add(Math.cos(angle) * radius, 2.0, Math.sin(angle) * radius);
            Firework firework = eventWorld.spawn(launch, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            Color primary = colors[random.nextInt(colors.length)];
            Color fade = colors[random.nextInt(colors.length)];
            meta.addEffect(FireworkEffect.builder()
                    .with(types[random.nextInt(types.length)])
                    .withColor(primary)
                    .withFade(fade)
                    .trail(true)
                    .flicker(true)
                    .build());
            meta.setPower(power);
            firework.setFireworkMeta(meta);
            firework.setVelocity(new Vector(random.nextDouble(-0.12, 0.12), 0.8,
                    random.nextDouble(-0.12, 0.12)));
            firework.addScoreboardTag("townysmp_dragon_victory");
        }
    }

    private void startAprilFoolsFinale(Location center) {
        if (eventWorld == null || !plugin.getConfig().getBoolean("april-fools.finale.enabled", true)) return;
        eventWorld.playSound(center, "minecraft:entity.creeper.primed", 1.5f, 0.55f);
        eventWorld.playSound(center, "minecraft:entity.generic.explode", 1.4f, 1.2f);
        Color[] colors = {
                Color.fromRGB(255, 43, 214), Color.fromRGB(56, 255, 101),
                Color.fromRGB(255, 215, 0), Color.fromRGB(55, 220, 255)
        };
        for (Color color : colors) {
            eventWorld.spawnParticle(Particle.DUST, center, 45, 2.5, 2.5, 2.5, 0.08,
                    new Particle.DustOptions(color, 1.6f));
        }
        eventWorld.spawnParticle(Particle.FIREWORK, center, 80, 3.0, 3.0, 3.0, 0.25);
        int minis = Math.max(0, Math.min(30,
                plugin.getConfig().getInt("april-fools.finale.mini-creepers", 10)));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < minis; index++) {
            Location spawn = center.clone().add(random.nextDouble(-2.0, 2.0), random.nextDouble(0.5, 2.5),
                    random.nextDouble(-2.0, 2.0));
            Creeper mini = eventWorld.spawn(spawn, Creeper.class, creeper -> {
                creeper.setAI(false);
                creeper.setPowered(random.nextBoolean());
                creeper.setExplosionRadius(0);
                creeper.setMaxFuseTicks(Math.max(10,
                        plugin.getConfig().getInt("april-fools.finale.mini-fuse-ticks", 40)));
                creeper.addScoreboardTag("townysmp_april_finale");
                AttributeInstance scale = creeper.getAttribute(Attribute.SCALE);
                if (scale != null) scale.setBaseValue(Math.max(0.2, Math.min(1.0,
                        plugin.getConfig().getDouble("april-fools.finale.mini-creeper-scale", 0.45))));
            });
            mini.setVelocity(new Vector(random.nextDouble(-0.7, 0.7), random.nextDouble(0.5, 1.1),
                    random.nextDouble(-0.7, 0.7)));
            mini.ignite();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (mini.isValid()) mini.remove();
            }, 100L);
        }
        int finaleFireworks = Math.max(0, Math.min(20,
                plugin.getConfig().getInt("april-fools.finale.fireworks", 6)));
        for (int index = 0; index < finaleFireworks; index++) {
            Location launch = center.clone().add(random.nextDouble(-4.0, 4.0), random.nextDouble(0.0, 2.0),
                    random.nextDouble(-4.0, 4.0));
            Firework firework = eventWorld.spawn(launch, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST)
                    .withColor(colors[random.nextInt(colors.length)])
                    .withFade(colors[random.nextInt(colors.length)]).trail(true).flicker(true).build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
            firework.addScoreboardTag("townysmp_dragon_victory");
        }
    }

    void stop() {
        if (state == EventState.IDLE) return;
        Bukkit.broadcast(messages.get("stopped"));
        cancelEventTasks();
        if (dragon != null && dragon.isValid()) dragon.remove();
        evacuateAndReset();
    }

    private void distributeRewards() {
        if (statisticsRecorded) return;
        statisticsRecorded = true;
        List<Map.Entry<UUID, Double>> ranking = damage.entrySet().stream()
                .filter(entry -> entry.getValue() > 0
                        && !spectators.contains(entry.getKey())
                        && !departedParticipants.contains(entry.getKey()))
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .toList();
        String players = participantNames();
        double minimum = plugin.getConfig().getDouble("event.minimum-reward-damage", 25.0);
        Bukkit.broadcast(messages.get("top-header"));
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            String name = Bukkit.getOfflinePlayer(ranking.get(i).getKey()).getName();
            Bukkit.broadcast(messages.get("top-entry", Map.of(
                    "rank", Integer.toString(i + 1),
                    "player", name == null ? ranking.get(i).getKey().toString() : name,
                    "damage", formatDamage(ranking.get(i).getValue()))));
        }
        broadcastMvpSummary(ranking);
        for (UUID uuid : participants) {
            int rank = ranking.stream().map(Map.Entry::getKey).toList().indexOf(uuid) + 1;
            if (damage.getOrDefault(uuid, 0.0) <= 0) rank = 0;
            double dealt = damage.getOrDefault(uuid, 0.0);
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            StatsManager.RecordResult result = stats.record(uuid, name == null ? uuid.toString() : name, dealt, rank,
                    eventDeaths.getOrDefault(uuid, 0), crystalsDestroyed.getOrDefault(uuid, 0),
                    explosionsTriggered.getOrDefault(uuid, 0));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                String resultKey = rank > 0 ? "personal-result" : "personal-result-unranked";
                messages.send(online, resultKey, Map.of(
                        "rank", Integer.toString(rank),
                        "damage", formatDamage(dealt),
                        "crystals", Integer.toString(crystalsDestroyed.getOrDefault(uuid, 0)),
                        "explosions", Integer.toString(explosionsTriggered.getOrDefault(uuid, 0))));
                if (result.personalBest()) messages.send(online, "personal-best", Map.of("damage", formatDamage(dealt)));
            }
            boolean rewardEligible = !spectators.contains(uuid) && !departedParticipants.contains(uuid);
            if (dealt >= minimum && rewardEligible) {
                boolean rankReward = rank >= 1 && rank <= 3
                        && !plugin.getConfig().getStringList("rewards.rank-" + rank).isEmpty();
                if (rankReward) {
                    runRewardCommands("rewards.rank-" + rank, uuid, rank, dealt, players);
                }
                boolean rankReplacesOthers = plugin.getConfig().getBoolean(
                        "rewards.policy.rank-replaces-other-rewards", true);
                if (!rankReward || !rankReplacesOthers) {
                    String participationPath = lateParticipants.contains(uuid)
                            ? "rewards.late-participation" : "rewards.participation";
                    runRewardCommands(participationPath, uuid, rank, dealt, players);
                    runDamageTierCommands(uuid, rank, dealt, players);
                    if (eventDeaths.getOrDefault(uuid, 0) == 0) {
                        runRewardCommands("rewards.no-death", uuid, rank, dealt, players);
                    }
                }
            } else if (online != null && rewardEligible) {
                messages.send(online, "reward-ineligible", Map.of("minimum", formatDamage(minimum)));
            }
            if (!ranking.isEmpty() && ranking.get(0).getKey().equals(uuid) && result.serverRecord()) {
                Bukkit.broadcast(messages.get("server-record", Map.of("player", name == null ? uuid.toString() : name, "damage", formatDamage(dealt))));
            }
        }
        runEventEndCommands(players);
        stats.save();
    }

    private void recordDefeatStatistics() {
        if (statisticsRecorded) return;
        statisticsRecorded = true;
        for (UUID uuid : participants) {
            double dealt = damage.getOrDefault(uuid, 0.0);
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            StatsManager.RecordResult result = stats.record(uuid, name == null ? uuid.toString() : name,
                    dealt, 0, eventDeaths.getOrDefault(uuid, 0),
                    crystalsDestroyed.getOrDefault(uuid, 0), explosionsTriggered.getOrDefault(uuid, 0));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                messages.send(online, "defeat-result", Map.of(
                        "damage", formatDamage(dealt),
                        "deaths", Integer.toString(eventDeaths.getOrDefault(uuid, 0)),
                        "crystals", Integer.toString(crystalsDestroyed.getOrDefault(uuid, 0)),
                        "explosions", Integer.toString(explosionsTriggered.getOrDefault(uuid, 0))));
                if (result.personalBest()) {
                    messages.send(online, "personal-best", Map.of("damage", formatDamage(dealt)));
                }
            }
        }
        stats.save();
    }

    private void broadcastMvpSummary(List<Map.Entry<UUID, Double>> ranking) {
        List<UUID> fighters = participants.stream()
                .filter(uuid -> damage.getOrDefault(uuid, 0.0) > 0.0)
                .filter(uuid -> !departedParticipants.contains(uuid))
                .filter(uuid -> !spectators.contains(uuid))
                .toList();
        if (fighters.isEmpty()) return;
        Bukkit.broadcast(messages.get("mvp-header"));
        if (!ranking.isEmpty()) {
            Bukkit.broadcast(messages.get("mvp-damage", Map.of(
                    "players", displayNames(List.of(ranking.get(0).getKey())),
                    "damage", formatDamage(ranking.get(0).getValue()))));
        }
        int fewestDeaths = fighters.stream().mapToInt(uuid -> eventDeaths.getOrDefault(uuid, 0)).min().orElse(0);
        List<UUID> survivors = fighters.stream()
                .filter(uuid -> eventDeaths.getOrDefault(uuid, 0) == fewestDeaths).toList();
        Bukkit.broadcast(messages.get("mvp-survivor", Map.of(
                "players", displayNames(survivors), "deaths", Integer.toString(fewestDeaths))));
        int mostCrystals = fighters.stream().mapToInt(uuid -> crystalsDestroyed.getOrDefault(uuid, 0)).max().orElse(0);
        if (mostCrystals > 0) {
            List<UUID> crystalMvp = fighters.stream()
                    .filter(uuid -> crystalsDestroyed.getOrDefault(uuid, 0) == mostCrystals).toList();
            Bukkit.broadcast(messages.get("mvp-crystals", Map.of(
                    "players", displayNames(crystalMvp), "crystals", Integer.toString(mostCrystals))));
        }
        int mostExplosions = fighters.stream().mapToInt(uuid -> explosionsTriggered.getOrDefault(uuid, 0)).max().orElse(0);
        if (mostExplosions > 0) {
            List<UUID> explosionMvp = fighters.stream()
                    .filter(uuid -> explosionsTriggered.getOrDefault(uuid, 0) == mostExplosions).toList();
            Bukkit.broadcast(messages.get("mvp-explosions", Map.of(
                    "players", displayNames(explosionMvp), "explosions", Integer.toString(mostExplosions))));
        }
    }

    private String displayNames(List<UUID> uuids) {
        return uuids.stream().map(Bukkit::getOfflinePlayer)
                .map(player -> player.getName() == null ? player.getUniqueId().toString() : player.getName())
                .sorted(String.CASE_INSENSITIVE_ORDER).reduce((left, right) -> left + ", " + right).orElse("-");
    }

    private void runDamageTierCommands(UUID uuid, int rank, double dealt, String players) {
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("rewards.damage-tiers");
        if (tiers == null) return;
        List<Double> reached = tiers.getKeys(false).stream().map(key -> {
            try { return Double.parseDouble(key); }
            catch (NumberFormatException ignored) { return null; }
        }).filter(java.util.Objects::nonNull).sorted().filter(required -> dealt >= required).toList();
        if (reached.isEmpty()) return;
        if (plugin.getConfig().getBoolean("rewards.policy.highest-damage-tier-only", true)) {
            double highest = reached.get(reached.size() - 1);
            runRewardCommands("rewards.damage-tiers." + formatTierKey(highest), uuid, rank, dealt, players);
            return;
        }
        reached.forEach(required -> runRewardCommands(
                "rewards.damage-tiers." + formatTierKey(required), uuid, rank, dealt, players));
    }

    private String formatTierKey(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private String formatDamage(double value) { return String.format(Locale.US, "%.2f", value); }

    private void runRewardCommands(String path, UUID uuid, int rank, double dealt, String players) {
        Player player = Bukkit.getPlayer(uuid);
        String name = player != null ? player.getName() : Bukkit.getOfflinePlayer(uuid).getName();
        if (name == null) return;
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (String command : plugin.getConfig().getStringList(path)) {
            Bukkit.dispatchCommand(console, applyCommandVariables(command, name, uuid, rank, dealt, players));
        }
    }

    private void runEventEndCommands(String players) {
        for (String command : plugin.getConfig().getStringList("rewards.event-end")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command
                    .replace("{players}", players)
                    .replace("{player_count}", Integer.toString(participants.size())));
        }
    }

    private String applyCommandVariables(String command, String name, UUID uuid, int rank, double dealt, String players) {
        return command.replace("{player}", name)
                .replace("{uuid}", uuid.toString())
                .replace("{rank}", Integer.toString(rank))
                .replace("{damage}", String.format(Locale.US, "%.2f", dealt))
                .replace("{crystals}", Integer.toString(crystalsDestroyed.getOrDefault(uuid, 0)))
                .replace("{explosions}", Integer.toString(explosionsTriggered.getOrDefault(uuid, 0)))
                .replace("{players}", players)
                .replace("{player_count}", Integer.toString(participants.size()));
    }

    private String participantNames() {
        return participants.stream().map(Bukkit::getOfflinePlayer).map(player -> player.getName() == null ? player.getUniqueId().toString() : player.getName())
                .sorted(String.CASE_INSENSITIVE_ORDER).reduce((left, right) -> left + "," + right).orElse("");
    }

    private Map<String, String> playerCountReplacement() {
        return Map.of("players", Integer.toString(playerCount()));
    }

    private void evacuateAndReset() {
        cancelEventTasks();
        state = EventState.RESETTING;
        long operation = ++lifecycleGeneration;
        resetDeleteQueued = false;
        cancelAllDisconnectGrace();
        List<CompletableFuture<Boolean>> evacuations = new ArrayList<>();
        for (UUID uuid : new HashSet<>(participants)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) restoreGameMode(player);
        }
        if (eventWorld != null) {
            for (Player player : new ArrayList<>(eventWorld.getPlayers())) {
                restoreGameMode(player);
                evacuations.add(runFallback(player));
            }
        }
        CompletableFuture.allOf(evacuations.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> runOnServerThread(() -> attemptWorldUnload(operation, 0)));
        resetTask = Bukkit.getScheduler().runTaskLater(plugin, () -> attemptWorldUnload(operation, 0), 20L);
    }

    private void attemptWorldUnload(long operation, int attempt) {
        if (operation != lifecycleGeneration || state != EventState.RESETTING || shuttingDown) return;
        if (eventWorld == null) {
            deleteRuntimeAndClear(operation);
            return;
        }
        if (!eventWorld.getPlayers().isEmpty()) {
            for (Player player : new ArrayList<>(eventWorld.getPlayers())) {
                restoreGameMode(player);
                runFallback(player);
            }
            scheduleUnloadRetry(operation, attempt, "players are still inside");
            return;
        }
        World worldToUnload = eventWorld;
        if (!Bukkit.unloadWorld(worldToUnload, false)) {
            scheduleUnloadRetry(operation, attempt, "Paper rejected the unload request");
            return;
        }
        eventWorld = null;
        dragon = null;
        deleteRuntimeAndClear(operation);
    }

    private void scheduleUnloadRetry(long operation, int attempt, String reason) {
        int maximum = Math.max(5, plugin.getConfig().getInt("world.reset.maximum-unload-attempts", 40));
        if (attempt >= maximum) {
            plugin.getLogger().severe("Runtime world reset paused because " + reason
                    + ". The world was NOT deleted. Fix the cause and run /dragon stop again.");
            return;
        }
        long delay = Math.max(1L, plugin.getConfig().getLong("world.reset.retry-delay-ticks", 10L));
        if (resetTask != null) resetTask.cancel();
        resetTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> attemptWorldUnload(operation, attempt + 1), delay);
    }

    private void deleteRuntimeAndClear(long operation) {
        if (resetDeleteQueued) return;
        resetDeleteQueued = true;
        if (resetTask != null) resetTask.cancel();
        resetTask = null;
        Path runtime;
        try {
            String runtimeName = activeRuntimeName == null ? runtimeWorldName() : activeRuntimeName;
            runtime = safeWorldPath(runtimeName);
        } catch (IllegalArgumentException exception) {
            resetDeleteQueued = false;
            plugin.getLogger().log(Level.SEVERE, "Refusing to delete an unsafe runtime world path", exception);
            return;
        }
        Path container = worldContainerPath();
        queueWorldIo(() -> WorldFiles.delete(container, runtime)).whenComplete((ignored, error) ->
                runOnServerThread(() -> {
                    if (operation != lifecycleGeneration || state != EventState.RESETTING) return;
                    if (error != null) {
                        resetDeleteQueued = false;
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not delete runtime event world. The event remains in RESETTING state.", error);
                        return;
                    }
                    clearSession();
                }));
    }

    private void clearSession() {
        cancelEventTasks();
        participants.clear();
        lateParticipants.clear();
        spectators.clear();
        departedParticipants.clear();
        exitPortalBlocks.clear();
        damage.clear();
        eventDeaths.clear();
        crystalsDestroyed.clear();
        explosionsTriggered.clear();
        explosiveOwners.clear();
        pendingBlockExplosions.clear();
        countedCrystals.clear();
        primedTnt.clear();
        aprilProjectiles.clear();
        originalGameModes.clear();
        originalAllowFlight.clear();
        originalFlying.clear();
        voidRescueCooldown.clear();
        cancelAllDisconnectGrace();
        activeTeleports.clear();
        aprilCreeper = null;
        activeRuntimeName = null;
        eventMode = EventMode.NORMAL;
        fightSecondsLeft = 0;
        closingSecondsLeft = 0;
        effectiveDragonHealth = 0.0;
        incomingDamageMultiplier = 1.0;
        dragonAttackMultiplier = 1.0;
        lastDragonLocation = null;
        dragonStationarySeconds = 0;
        aprilBarrageTicks = 0;
        aprilAmbientSoundTicks = 0;
        lastAprilHurtSoundTick = -1L;
        spawningScaledProjectile = false;
        statisticsRecorded = false;
        difficultyFighterCount = 0;
        resetDeleteQueued = false;
        secondsLeft = 0;
        prepareFuture = CompletableFuture.completedFuture(null);
        state = EventState.IDLE;
    }

    void recoverAfterRestart() {
        shuttingDown = false;
        String runtimeName = runtimeWorldName();
        Path runtime;
        try {
            runtime = safeWorldPath(runtimeName);
        } catch (IllegalArgumentException exception) {
            state = EventState.RESETTING;
            plugin.getLogger().log(Level.SEVERE,
                    "Unsafe world.runtime-name. Dragon events are disabled until the config is corrected.", exception);
            return;
        }
        String sourceName = resolveTemplateWorldName();
        if (runtimeName.equals(sourceName)) {
            state = EventState.RESETTING;
            plugin.getLogger().severe("world.runtime-name matches the template world. No files were changed.");
            return;
        }
        try {
            if (Files.exists(runtime) && !WorldFiles.isOwnedRuntime(worldContainerPath(), runtime)) {
                state = EventState.RESETTING;
                plugin.getLogger().severe("The configured runtime folder is not owned by TownyDragonEvent. "
                        + "No files were changed: " + runtime);
                return;
            }
        } catch (IOException exception) {
            state = EventState.RESETTING;
            plugin.getLogger().log(Level.SEVERE, "Could not validate the stale runtime world", exception);
            return;
        }
        activeRuntimeName = runtimeName;
        eventWorld = Bukkit.getWorld(runtimeName);
        if (eventWorld != null && eventWorld.getEnvironment() != World.Environment.THE_END) {
            state = EventState.RESETTING;
            eventWorld = null;
            plugin.getLogger().severe("The configured runtime name belongs to a non-End world. No files were changed.");
            return;
        }
        evacuateAndReset();
    }

    void shutdown() {
        shuttingDown = true;
        lifecycleGeneration++;
        cancelEventTasks();
        cancelAllDisconnectGrace();
        Location exit = exitLocation();
        for (UUID uuid : new HashSet<>(participants)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) restoreGameMode(player);
        }
        if (eventWorld != null) {
            for (Player player : new ArrayList<>(eventWorld.getPlayers())) {
                restoreGameMode(player);
                if (exit != null) player.teleport(exit);
            }
            if (eventWorld.getPlayers().isEmpty() && !Bukkit.unloadWorld(eventWorld, false)) {
                plugin.getLogger().warning("Could not unload the Dragon runtime world during shutdown; it will be recovered on startup.");
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String managedRuntimeName = activeRuntimeName == null ? runtimeWorldName() : activeRuntimeName;
        cancelDisconnectGrace(uuid);
        if (eventMode == EventMode.APRIL_FOOLS && state == EventState.ACTIVE) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> hideAprilDragonFrom(event.getPlayer()), 1L);
        }
        if ((event.getPlayer().getScoreboardTags().contains("townysmp_dragon_spectator")
                || event.getPlayer().getScoreboardTags().contains("townysmp_dragon_participant"))
                && (state == EventState.IDLE || state == EventState.RESETTING)) {
            event.getPlayer().removeScoreboardTag("townysmp_dragon_spectator");
            event.getPlayer().removeScoreboardTag("townysmp_dragon_participant");
            event.getPlayer().setGameMode(GameMode.SURVIVAL);
            event.getPlayer().setFlying(false);
            event.getPlayer().setAllowFlight(false);
        }
        // A crash during an event must never leave a player trapped in the disposable world.
        if (event.getPlayer().getWorld().getName().equals(managedRuntimeName)
                && (state == EventState.IDLE || state == EventState.RESETTING)) {
            runFallback(event.getPlayer());
        } else if (departedParticipants.contains(uuid)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> returnPlayer(event.getPlayer()), 1L);
        } else if (participants.contains(uuid)
                && !departedParticipants.contains(uuid)
                && (state == EventState.JOINING || state == EventState.RESPAWNING || state == EventState.ACTIVE)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> teleportToEvent(event.getPlayer()), 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if ((state != EventState.ACTIVE && state != EventState.RESPAWNING) || !participants.contains(uuid)) return;
        restoreGameMode(event.getPlayer());
        cancelDisconnectGrace(uuid);
        int grace = Math.max(0, plugin.getConfig().getInt("event.disconnect-grace-seconds", 60));
        if (grace == 0) {
            departedParticipants.add(uuid);
            Bukkit.getScheduler().runTask(plugin, this::checkForBattleLoss);
            return;
        }
        disconnectGraceTasks.put(uuid, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            disconnectGraceTasks.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if ((state != EventState.ACTIVE && state != EventState.RESPAWNING)
                    || !participants.contains(uuid) || (player != null && player.isOnline())) return;
            departedParticipants.add(uuid);
            checkForBattleLoss();
        }, grace * 20L));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnCommand(PlayerCommandPreprocessEvent event) {
        if (eventWorld == null || !event.getPlayer().getWorld().equals(eventWorld)) return;
        String command = event.getMessage().trim().split("\\s+", 2)[0];
        if (!command.equalsIgnoreCase("/spawn")) return;
        event.setCancelled(true);
        if (!leave(event.getPlayer())) {
            restoreGameMode(event.getPlayer());
            runFallback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaBlockPlace(BlockPlaceEvent event) {
        if (isArenaBuildingBlocked(event.getPlayer())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "block-building-disabled");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaBlockBreak(BlockBreakEvent event) {
        if (isArenaBuildingBlocked(event.getPlayer())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "block-building-disabled");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUnauthorizedArenaEntry(PlayerTeleportEvent event) {
        Location destination = event.getTo();
        if (eventWorld == null || destination == null || destination.getWorld() == null
                || event.getFrom().getWorld().equals(eventWorld)
                || !destination.getWorld().equals(eventWorld)
                || participants.contains(event.getPlayer().getUniqueId())
                || event.getPlayer().hasPermission("townysmp.dragon.admin")) return;
        event.setCancelled(true);
        messages.send(event.getPlayer(), "arena-private");
    }

    private boolean isArenaBuildingBlocked(Player player) {
        return eventWorld != null && player.getWorld().equals(eventWorld)
                && state != EventState.IDLE && state != EventState.RESETTING
                && !plugin.getConfig().getBoolean("event.block-building", true)
                && !player.hasPermission("townysmp.dragon.build");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLeavesArena(PlayerTeleportEvent event) {
        if (state != EventState.ACTIVE || eventWorld == null
                || !participants.contains(event.getPlayer().getUniqueId())
                || !event.getFrom().getWorld().equals(eventWorld)) return;
        Location destination = event.getTo();
        if (destination != null && destination.getWorld() != null && destination.getWorld().equals(eventWorld)) return;
        departedParticipants.add(event.getPlayer().getUniqueId());
        restoreGameMode(event.getPlayer());
        Bukkit.getScheduler().runTask(plugin, this::checkForBattleLoss);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEventEndPortalTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL
                || eventWorld == null || !event.getFrom().getWorld().equals(eventWorld)) return;
        event.setCancelled(true);
        if (state == EventState.FINISHING) {
            departedParticipants.add(event.getPlayer().getUniqueId());
            restoreGameMode(event.getPlayer());
            Bukkit.getScheduler().runTask(plugin, () -> runFallback(event.getPlayer()));
            return;
        }
        if (plugin.getConfig().getBoolean("event.lock-exit-until-victory", true)) {
            messages.send(event.getPlayer(), "exit-locked");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVanillaEndPortal(PlayerPortalEvent event) {
        if (eventWorld != null && event.getFrom().getWorld().equals(eventWorld) && state == EventState.FINISHING) {
            event.setCancelled(true);
            departedParticipants.add(event.getPlayer().getUniqueId());
            restoreGameMode(event.getPlayer());
            Bukkit.getScheduler().runTask(plugin, () -> runFallback(event.getPlayer()));
            return;
        }
        if (eventWorld != null && event.getFrom().getWorld().equals(eventWorld)
                && plugin.getConfig().getBoolean("event.lock-exit-until-victory", true)
                && (state == EventState.JOINING || state == EventState.RESPAWNING || state == EventState.ACTIVE)) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "exit-locked");
            return;
        }
        if (state != EventState.PREPARING
                || !plugin.getConfig().getBoolean("world.protect-vanilla-portal-during-copy", true)
                || !usesVanillaTemplate()) return;
        Location target = event.getTo();
        if (target == null || target.getWorld() == null
                || target.getWorld().getEnvironment() != World.Environment.THE_END) return;
        String vanillaEnd = plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end");
        if (!target.getWorld().getName().equals(vanillaEnd)) return;
        event.setCancelled(true);
        messages.send(event.getPlayer(), "end-temporarily-unavailable");
    }

    /**
     * A second, late guard prevents portal managers from re-enabling the
     * arena's sealed End portal after our LOWEST handler cancelled it.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void enforceArenaEndPortalLock(PlayerPortalEvent event) {
        if (eventWorld == null || !event.getFrom().getWorld().equals(eventWorld)) return;
        if (state == EventState.FINISHING
                || (plugin.getConfig().getBoolean("event.lock-exit-until-victory", true)
                && (state == EventState.JOINING || state == EventState.RESPAWNING || state == EventState.ACTIVE))) {
            event.setCancelled(true);
        }
    }

    private CompletableFuture<Boolean> returnPlayer(Player player) {
        restoreGameMode(player);
        return runFallback(player);
    }

    private void restoreGameMode(Player player) {
        UUID uuid = player.getUniqueId();
        GameMode original = originalGameModes.remove(uuid);
        if (original != null) player.setGameMode(original);
        Boolean allowFlight = originalAllowFlight.remove(uuid);
        Boolean flying = originalFlying.remove(uuid);
        if (allowFlight != null) player.setAllowFlight(allowFlight);
        if (flying != null && player.getAllowFlight()) player.setFlying(flying);
        player.removeScoreboardTag("townysmp_dragon_spectator");
        player.removeScoreboardTag("townysmp_dragon_participant");
    }

    private CompletableFuture<Boolean> runFallback(Player player) {
        Location target = exitLocation();
        if (target == null) {
            plugin.getLogger().severe("No normal world is loaded and no Dragon exit location is configured.");
            return CompletableFuture.completedFuture(false);
        }
        return trackedTeleport(player, target);
    }

    private CompletableFuture<Boolean> trackedTeleport(Player player, Location target) {
        if (player == null || !player.isOnline() || target == null || target.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID uuid = player.getUniqueId();
        CompletableFuture<Boolean> existing = activeTeleports.get(uuid);
        if (existing != null && !existing.isDone()) return existing;

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        activeTeleports.put(uuid, result);
        try {
            player.teleportAsync(target.clone()).whenComplete((success, error) -> {
                activeTeleports.remove(uuid, result);
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Asynchronous Dragon Event teleport failed for " + player.getName(), error);
                    result.complete(false);
                } else {
                    result.complete(Boolean.TRUE.equals(success));
                }
            });
        } catch (RuntimeException exception) {
            activeTeleports.remove(uuid, result);
            plugin.getLogger().log(Level.WARNING, "Could not start Dragon Event teleport for " + player.getName(), exception);
            result.complete(false);
        }
        return result;
    }

    private Location exitLocation() {
        String configuredWorld = plugin.getConfig().getString("world.exit-location.world", "").trim();
        World target = configuredWorld.isEmpty() ? null : Bukkit.getWorld(configuredWorld);
        if (target != null) {
            return new Location(target,
                    plugin.getConfig().getDouble("world.exit-location.x"),
                    plugin.getConfig().getDouble("world.exit-location.y"),
                    plugin.getConfig().getDouble("world.exit-location.z"),
                    (float) plugin.getConfig().getDouble("world.exit-location.yaw"),
                    (float) plugin.getConfig().getDouble("world.exit-location.pitch"));
        }
        World fallback = Bukkit.getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElseGet(() -> Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        return fallback == null ? null : fallback.getSpawnLocation().clone().add(0.5, 0.0, 0.5);
    }

    private Location portalLocation(DragonBattle battle) {
        if (eventWorld == null) return null;
        if (plugin.getConfig().getBoolean("world.portal-location.set", false)) {
            return new Location(eventWorld,
                    plugin.getConfig().getInt("world.portal-location.x"),
                    plugin.getConfig().getInt("world.portal-location.y"),
                    plugin.getConfig().getInt("world.portal-location.z"));
        }
        return battle == null ? null : battle.getEndPortalLocation();
    }

    private Location detectPortalCenter(Player player) {
        Location origin = player.getLocation();
        World world = player.getWorld();
        long totalX = 0;
        long totalZ = 0;
        int portalY = Integer.MIN_VALUE;
        int count = 0;
        for (int x = origin.getBlockX() - 8; x <= origin.getBlockX() + 8; x++) {
            for (int y = origin.getBlockY() - 8; y <= origin.getBlockY() + 4; y++) {
                for (int z = origin.getBlockZ() - 8; z <= origin.getBlockZ() + 8; z++) {
                    if (world.getBlockAt(x, y, z).getType() != Material.END_PORTAL) continue;
                    totalX += x;
                    totalZ += z;
                    portalY = Math.max(portalY, y);
                    count++;
                }
            }
        }
        if (count > 0) {
            return new Location(world, Math.round((double) totalX / count), portalY, Math.round((double) totalZ / count));
        }
        Block pillarTop = origin.getBlock().getRelative(0, -1, 0);
        boolean vanillaPillar = pillarTop.getType() == Material.BEDROCK;
        for (int depth = 1; depth <= 3 && vanillaPillar; depth++) {
            vanillaPillar = pillarTop.getRelative(0, -depth, 0).getType() == Material.BEDROCK;
        }
        if (vanillaPillar) return pillarTop.getRelative(0, -3, 0).getLocation();
        return pillarTop.getLocation();
    }

    private Map<String, String> locationReplacements(Location location) {
        return Map.of(
                "world", location.getWorld() == null ? "-" : location.getWorld().getName(),
                "x", String.format(Locale.US, "%.1f", location.getX()),
                "y", String.format(Locale.US, "%.1f", location.getY()),
                "z", String.format(Locale.US, "%.1f", location.getZ()));
    }

    private Location joinLocation() {
        return new Location(eventWorld,
                plugin.getConfig().getDouble("world.join-location.x", 0.5),
                plugin.getConfig().getDouble("world.join-location.y", 65),
                plugin.getConfig().getDouble("world.join-location.z", 18.5),
                (float) plugin.getConfig().getDouble("world.join-location.yaw", 180),
                (float) plugin.getConfig().getDouble("world.join-location.pitch", 0));
    }

    private Location safeJoinLocation() {
        Location target = joinLocation();
        if (!plugin.getConfig().getBoolean("event.safe-respawn.enabled", true) || eventWorld == null) return target;
        Block floor = target.clone().add(0.0, -1.0, 0.0).getBlock();
        if (floor.getType().isSolid()) return target;
        int radius = Math.max(1, Math.min(6,
                plugin.getConfig().getInt("event.safe-respawn.platform-radius", 2)));
        Material material;
        try {
            material = Material.valueOf(plugin.getConfig()
                    .getString("event.safe-respawn.platform-material", "OBSIDIAN").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            material = Material.OBSIDIAN;
        }
        int floorY = target.getBlockY() - 1;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                eventWorld.getBlockAt(target.getBlockX() + x, floorY, target.getBlockZ() + z)
                        .setType(material, false);
            }
        }
        eventWorld.getBlockAt(target.getBlockX(), target.getBlockY(), target.getBlockZ()).setType(Material.AIR, false);
        eventWorld.getBlockAt(target.getBlockX(), target.getBlockY() + 1, target.getBlockZ()).setType(Material.AIR, false);
        return target;
    }

    private Location spectatorLocation() {
        return new Location(eventWorld,
                plugin.getConfig().getDouble("world.spectator-location.x", 0.5),
                plugin.getConfig().getDouble("world.spectator-location.y", 100.0),
                plugin.getConfig().getDouble("world.spectator-location.z", 0.5),
                (float) plugin.getConfig().getDouble("world.spectator-location.yaw", 0.0),
                (float) plugin.getConfig().getDouble("world.spectator-location.pitch", 45.0));
    }

    private boolean spectatorLocationIsSafe(Location location) {
        if (eventWorld == null || location.getWorld() == null || !location.getWorld().equals(eventWorld)) return false;
        double minimumY = plugin.getConfig().getDouble("world.spectator-boundary.min-y", 0.0);
        double maximumY = plugin.getConfig().getDouble("world.spectator-boundary.max-y", 220.0);
        if (location.getY() < minimumY || location.getY() > maximumY) return false;
        return !plugin.getConfig().getBoolean("world.border.enabled", true)
                || eventWorld.getWorldBorder().isInside(location);
    }

    private void startSpectatorGuard() {
        cancelSpectatorGuard();
        long interval = Math.max(1L, plugin.getConfig().getLong("world.spectator-boundary.check-interval-ticks", 5L));
        spectatorGuardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (eventWorld == null) return;
            for (UUID uuid : new HashSet<>(spectators)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline() || !player.getWorld().equals(eventWorld)) continue;
                if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
                if (!player.getAllowFlight()) player.setAllowFlight(true);
                if (!player.isFlying()) player.setFlying(true);
                if (!spectatorLocationIsSafe(player.getLocation())) {
                    player.setVelocity(player.getVelocity().zero());
                    trackedTeleport(player, spectatorLocation());
                }
            }
        }, interval, interval);
    }

    private void configureWorldBorder() {
        if (eventWorld == null || !plugin.getConfig().getBoolean("world.border.enabled", true)) return;
        WorldBorder border = eventWorld.getWorldBorder();
        boolean centerOnPortal = plugin.getConfig().getBoolean("world.border.center-on-portal", true)
                && plugin.getConfig().getBoolean("world.portal-location.set", false);
        double centerX = centerOnPortal
                ? plugin.getConfig().getInt("world.portal-location.x") + 0.5
                : plugin.getConfig().getDouble("world.border.center-x", 0.0);
        double centerZ = centerOnPortal
                ? plugin.getConfig().getInt("world.portal-location.z") + 0.5
                : plugin.getConfig().getDouble("world.border.center-z", 0.0);
        border.setCenter(centerX, centerZ);
        border.setSize(Math.max(64.0, plugin.getConfig().getDouble("world.border.size", 512.0)));
        border.setWarningDistance(Math.max(0, plugin.getConfig().getInt("world.border.warning-distance", 16)));
    }

    private void removeEventEntities() {
        for (Entity entity : eventWorld.getEntities()) {
            if (entity instanceof EnderDragon || entity instanceof EnderCrystal || entity instanceof Item) entity.remove();
        }
    }

    private void closeExitPortal() {
        exitPortalBlocks.clear();
        sealExitPortal();
    }

    private void sealExitPortal() {
        if (!plugin.getConfig().getBoolean("event.lock-exit-until-victory", true) || eventWorld == null) return;
        DragonBattle battle = eventWorld.getEnderDragonBattle();
        if (battle == null) return;
        List<Location> centers = new ArrayList<>();
        Location configured = portalLocation(battle);
        Location vanilla = battle.getEndPortalLocation();
        if (configured != null) centers.add(configured);
        if (vanilla != null && centers.stream().noneMatch(location -> location.getBlockX() == vanilla.getBlockX()
                && location.getBlockY() == vanilla.getBlockY() && location.getBlockZ() == vanilla.getBlockZ())) centers.add(vanilla);
        for (Location center : centers) {
            for (int x = -4; x <= 4; x++) {
                for (int y = -2; y <= 3; y++) {
                    for (int z = -4; z <= 4; z++) {
                        Block block = eventWorld.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
                        if (block.getType() == Material.END_PORTAL) {
                            Location location = block.getLocation();
                            if (!exitPortalBlocks.contains(location)) exitPortalBlocks.add(location);
                            block.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
    }

    private void openExitPortal() {
        if (plugin.getConfig().getBoolean("event.lock-exit-until-victory", true)) {
            for (Location location : exitPortalBlocks) {
                if (location.getWorld() != null) location.getBlock().setType(Material.END_PORTAL, false);
            }
        }
    }

    private void rebuildSafeExitPortal() {
        if (eventWorld == null) return;
        DragonBattle battle = eventWorld.getEnderDragonBattle();
        Location center = portalLocation(battle);
        if (battle != null && center != null
                && plugin.getConfig().getBoolean("event.safe-exit.rebuild-vanilla-portal", true)) {
            try {
                if (plugin.getConfig().getBoolean("world.portal-location.set", false)) {
                    forceBattlePortalLocation(battle, center);
                }
                battle.generateEndPortal(true);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not regenerate the safe Dragon exit fountain", exception);
            }
        }
        openExitPortal();
        if (center == null || !plugin.getConfig().getBoolean("event.safe-exit.platform.enabled", true)) return;
        int radius = Math.max(2, Math.min(12,
                plugin.getConfig().getInt("event.safe-exit.platform.radius", 4)));
        int floorY = center.getBlockY() - 1;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Block floor = eventWorld.getBlockAt(center.getBlockX() + x, floorY, center.getBlockZ() + z);
                if (!floor.getType().isSolid()) floor.setType(Material.BEDROCK, false);
            }
        }
    }

    private Player resolvePlayer(Entity entity) {
        return resolvePlayer(entity, new HashSet<>());
    }

    private Player resolvePlayer(Entity entity, Set<UUID> visited) {
        if (entity == null || !visited.add(entity.getUniqueId())) return null;
        if (entity instanceof Player player) return player;
        UUID trackedOwner = explosiveOwners.get(entity.getUniqueId());
        if (trackedOwner != null) {
            Player player = Bukkit.getPlayer(trackedOwner);
            if (player != null) return player;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() != null) {
            Player player = resolvePlayer(tnt.getSource(), visited);
            if (player != null) return player;
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
            if (shooter instanceof Entity shooterEntity) return resolvePlayer(shooterEntity, visited);
        }
        return null;
    }

    private boolean isBossDamage(Entity damager) {
        if (damager == dragon || damager == aprilCreeper) return true;
        if (damager.getScoreboardTags().contains("townysmp_april_projectile")
                || damager.getScoreboardTags().contains("townysmp_scaled_dragon_projectile")) return true;
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() == dragon || projectile.getShooter() == aprilCreeper;
        }
        return false;
    }

    private boolean isAprilProjectile(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains("townysmp_april_projectile");
    }

    private void cancelTicker() {
        if (ticker != null) ticker.cancel();
        ticker = null;
    }

    private void cancelFinishTask() {
        if (finishTask != null) finishTask.cancel();
        finishTask = null;
    }

    private void cancelEventTasks() {
        cancelTicker();
        cancelFinishTask();
        cancelFightTasks();
        cancelClosingCountdown();
        cancelSpectatorGuard();
        cancelAprilVisual();
        cancelVictoryFireworks();
        if (resetTask != null) resetTask.cancel();
        resetTask = null;
    }

    private void cancelFightTasks() {
        if (fightTimeoutTask != null) fightTimeoutTask.cancel();
        if (fightClockTask != null) fightClockTask.cancel();
        fightTimeoutTask = null;
        fightClockTask = null;
    }

    private void cancelClosingCountdown() {
        if (closingCountdownTask != null) closingCountdownTask.cancel();
        closingCountdownTask = null;
    }

    private void cancelSpectatorGuard() {
        if (spectatorGuardTask != null) spectatorGuardTask.cancel();
        spectatorGuardTask = null;
    }

    private void cancelAprilVisual() {
        if (aprilVisualTask != null) aprilVisualTask.cancel();
        aprilVisualTask = null;
        if (aprilCreeper != null && aprilCreeper.isValid()) aprilCreeper.remove();
        aprilCreeper = null;
    }

    private void cancelVictoryFireworks() {
        if (victoryFireworkTask != null) victoryFireworkTask.cancel();
        victoryFireworkTask = null;
    }

    private void cancelDisconnectGrace(UUID uuid) {
        BukkitTask task = disconnectGraceTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void cancelAllDisconnectGrace() {
        for (BukkitTask task : disconnectGraceTasks.values()) task.cancel();
        disconnectGraceTasks.clear();
    }

    private static String formatTime(int seconds) {
        Duration duration = Duration.ofSeconds(Math.max(0, seconds));
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        long rest = duration.minusHours(hours).minusMinutes(minutes).toSeconds();
        StringBuilder result = new StringBuilder();
        if (hours > 0) result.append(hours).append("h");
        if (minutes > 0) {
            if (!result.isEmpty()) result.append(' ');
            result.append(minutes).append("m");
        }
        if (rest > 0 || result.isEmpty()) {
            if (!result.isEmpty()) result.append(' ');
            result.append(rest).append("s");
        }
        return result.toString();
    }
}
