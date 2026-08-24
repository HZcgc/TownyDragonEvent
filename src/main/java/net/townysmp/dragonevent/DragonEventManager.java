package net.townysmp.dragonevent;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

final class DragonEventManager implements Listener {
    private static final int[][] CRYSTAL_OFFSETS = {{3, 0}, {0, 3}, {-3, 0}, {0, -3}};

    private final TownyDragonEventPlugin plugin;
    private final Messages messages;
    private final StatsManager stats;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> lateParticipants = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final Set<UUID> departedParticipants = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Map<UUID, Integer> eventDeaths = new HashMap<>();
    private final Map<UUID, GameMode> originalGameModes = new HashMap<>();
    private final Map<UUID, Boolean> originalAllowFlight = new HashMap<>();
    private final Map<UUID, Boolean> originalFlying = new HashMap<>();
    private final Map<UUID, Long> voidRescueCooldown = new HashMap<>();
    private final List<Location> exitPortalBlocks = new ArrayList<>();
    private EventState state = EventState.IDLE;
    private World eventWorld;
    private EnderDragon dragon;
    private BukkitTask ticker;
    private BukkitTask finishTask;
    private BukkitTask resetTask;
    private BukkitTask fightTimeoutTask;
    private BukkitTask fightClockTask;
    private BukkitTask closingCountdownTask;
    private BukkitTask spectatorGuardTask;
    private int secondsLeft;
    private int fightSecondsLeft;
    private int closingSecondsLeft;

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
    boolean isSpectator(UUID uuid) { return spectators.contains(uuid); }
    boolean hasDeparted(UUID uuid) { return departedParticipants.contains(uuid); }
    int fightSecondsRemaining() { return Math.max(0, fightSecondsLeft); }
    String fightTimeRemaining() { return formatTime(fightSecondsRemaining()); }
    int closingSecondsRemaining() { return Math.max(0, closingSecondsLeft); }
    String closingTimeRemaining() { return formatTime(closingSecondsRemaining()); }
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
        String configuredTemplate = plugin.getConfig().getString("world.template-folder", "").trim();
        String templateWorld = configuredTemplate.isEmpty()
                ? plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end").trim()
                : configuredTemplate;
        if (player.getWorld().getName().equals(templateWorld)) return true;
        messages.send(player, "template-world-required", Map.of("world", templateWorld));
        return false;
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
    String runtimeWorldName() { return plugin.getConfig().getString("world.runtime-name", "dragonevent_end"); }

    synchronized boolean start(Integer customSeconds) {
        if (state != EventState.IDLE) return false;
        state = EventState.PREPARING;
        secondsLeft = customSeconds == null
                ? plugin.getConfig().getInt("event.countdown-seconds", 10800)
                : Math.max(10, customSeconds);
        Bukkit.broadcast(messages.get("preparing"));

        Path container = Bukkit.getWorldContainer().toPath();
        String configuredTemplate = plugin.getConfig().getString("world.template-folder", "").trim();
        String sourceFolder = configuredTemplate.isEmpty()
                ? plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end").trim()
                : configuredTemplate;
        Path template = container.resolve(sourceFolder).normalize();
        Path runtime = container.resolve(runtimeWorldName());
        if (sourceFolder.isEmpty() || template.equals(runtime.normalize())) {
            plugin.getLogger().severe("The event source world must be set and must differ from the runtime world.");
            state = EventState.IDLE;
            return false;
        }
        World loadedSource = Bukkit.getWorld(sourceFolder);
        boolean reloadSource = loadedSource != null;
        if (loadedSource != null) {
            if (!loadedSource.getPlayers().isEmpty()) {
                plugin.getLogger().warning("Cannot clone source world " + sourceFolder + " while players are inside it.");
                state = EventState.IDLE;
                return false;
            }
            loadedSource.save();
            if (!Bukkit.unloadWorld(loadedSource, true)) {
                plugin.getLogger().severe("Could not safely unload source world " + sourceFolder + " for cloning.");
                state = EventState.IDLE;
                return false;
            }
        }
        CompletableFuture.runAsync(() -> {
            try {
                WorldFiles.copy(template, runtime);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (reloadSource && Bukkit.getWorld(sourceFolder) == null) {
                new WorldCreator(sourceFolder).environment(World.Environment.THE_END).createWorld();
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not prepare the event world", error);
                state = EventState.IDLE;
                return;
            }
            loadWorldAndStartCountdown();
        }));
        return true;
    }

    private void loadWorldAndStartCountdown() {
        if (state != EventState.PREPARING) return;
        eventWorld = new WorldCreator(runtimeWorldName())
                .environment(World.Environment.THE_END)
                .generateStructures(false)
                .createWorld();
        if (eventWorld == null) {
            plugin.getLogger().severe("Paper could not load event world " + runtimeWorldName());
            state = EventState.IDLE;
            return;
        }
        eventWorld.setAutoSave(false);
        eventWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        eventWorld.setGameRule(GameRule.KEEP_INVENTORY, plugin.getConfig().getBoolean("event.death.keep-inventory", true));
        eventWorld.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, plugin.getConfig().getBoolean("event.death.immediate-respawn", true));
        eventWorld.setGameRule(GameRule.SHOW_DEATH_MESSAGES, true);
        configureWorldBorder();
        startSpectatorGuard();
        removeEventEntities();
        closeExitPortal();
        int joinSeconds = plugin.getConfig().getInt("event.join-seconds", 300);
        state = secondsLeft <= joinSeconds ? EventState.JOINING : EventState.SCHEDULED;
        if (state == EventState.JOINING) {
            messages.broadcast("lobby-open", playerCountReplacement(), "lobby");
        }
        announceCountdown();
        secondsLeft--;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::eventTick, 20L, 20L);
    }

    private void eventTick() {
        if (state != EventState.SCHEDULED && state != EventState.JOINING) return;
        if (state == EventState.SCHEDULED
                && secondsLeft <= plugin.getConfig().getInt("event.join-seconds", 300)) {
            state = EventState.JOINING;
            messages.broadcast("lobby-open", playerCountReplacement(), "lobby");
            teleportRegisteredPlayers();
        }
        if (secondsLeft <= 0) {
            beginRespawn();
            return;
        }
        announceCountdown();
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
            messages.send(player, "already-joined");
            return false;
        }
        participants.add(player.getUniqueId());
        if (state == EventState.RESPAWNING || state == EventState.ACTIVE
                || (state == EventState.JOINING && eventWorld != null && player.getWorld().equals(eventWorld))) {
            lateParticipants.add(player.getUniqueId());
        }
        damage.put(player.getUniqueId(), 0.0);
        eventDeaths.put(player.getUniqueId(), 0);
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
        if (eventWorld == null || player.getWorld().equals(eventWorld)) return;
        player.teleportAsync(joinLocation());
    }

    private void beginRespawn() {
        cancelTicker();
        int minimum = plugin.getConfig().getInt("event.minimum-players", 1);
        if (participants.size() < minimum) {
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
        if (battle.getEndPortalLocation() == null) {
            battle.setPreviouslyKilled(true);
            battle.generateEndPortal(false);
        }
        Location portal = portalLocation(battle);
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
        if (state == EventState.RESPAWNING && event.getLocation().getWorld().equals(eventWorld)
                && event.getEntity() instanceof EnderDragon spawned) {
            Bukkit.getScheduler().runTask(plugin, () -> activateDragon(spawned));
        }
    }

    private void activateDragon(EnderDragon spawned) {
        if (state != EventState.RESPAWNING) return;
        cancelTicker();
        dragon = spawned;
        double health = Math.min(1024.0, Math.max(1.0, plugin.getConfig().getDouble("event.dragon-health", 1000.0)));
        AttributeInstance maxHealth = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) maxHealth.setBaseValue(health);
        dragon.setHealth(health);
        dragon.customName(messages.getRaw("dragon-name", Map.of("health", String.format(Locale.US, "%.0f", health))));
        eventWorld.setGameRule(GameRule.DO_MOB_SPAWNING, true);
        state = EventState.ACTIVE;
        startFightClock();
        messages.broadcast("fight-start", Map.of("health", String.format(Locale.US, "%.0f", health)), "fight");
        checkForBattleLoss();
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (state != EventState.ACTIVE || event.getEntity() != dragon) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || !participants.contains(attacker.getUniqueId())) return;
        damage.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    private void startFightClock() {
        cancelFightTasks();
        fightSecondsLeft = Math.max(60, plugin.getConfig().getInt("event.fight-time-limit-seconds", 900));
        fightClockTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != EventState.ACTIVE) return;
            if (fightSecondsLeft == 600 || fightSecondsLeft == 300 || fightSecondsLeft == 60) {
                Bukkit.broadcast(messages.get("fight-time-warning", Map.of("time", formatTime(fightSecondsLeft))));
            }
            fightSecondsLeft--;
        }, 20L, 20L);
        fightTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != EventState.ACTIVE) return;
            finishDefeat("fight-timeout", Map.of("limit", formatTime(
                    plugin.getConfig().getInt("event.fight-time-limit-seconds", 900))));
        }, fightSecondsLeft * 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (state == EventState.ACTIVE && event.getEntity() == dragon) {
            openExitPortal();
            finish(true);
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
        if (eventWorld == null || !participants.contains(player.getUniqueId())
                || state == EventState.IDLE || state == EventState.RESETTING) return;
        event.setRespawnLocation(joinLocation());
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
                player.teleportAsync(spectatorLocation());
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
        player.teleport(joinLocation());
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
            return !departedParticipants.contains(uuid) && player != null && player.isOnline()
                    && eventWorld != null && player.getWorld().equals(eventWorld);
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
        if (dragon != null && dragon.isValid()) dragon.setInvulnerable(true);
        openExitPortal();
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

    void stop() {
        if (state == EventState.IDLE) return;
        Bukkit.broadcast(messages.get("stopped"));
        cancelEventTasks();
        if (dragon != null && dragon.isValid()) dragon.remove();
        evacuateAndReset();
    }

    private void distributeRewards() {
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
        for (UUID uuid : participants) {
            int rank = ranking.stream().map(Map.Entry::getKey).toList().indexOf(uuid) + 1;
            if (damage.getOrDefault(uuid, 0.0) <= 0) rank = 0;
            double dealt = damage.getOrDefault(uuid, 0.0);
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            StatsManager.RecordResult result = stats.record(uuid, name == null ? uuid.toString() : name, dealt, rank, eventDeaths.getOrDefault(uuid, 0));
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                String resultKey = rank > 0 ? "personal-result" : "personal-result-unranked";
                messages.send(online, resultKey, Map.of("rank", Integer.toString(rank), "damage", formatDamage(dealt)));
                if (result.personalBest()) messages.send(online, "personal-best", Map.of("damage", formatDamage(dealt)));
            }
            boolean rewardEligible = !spectators.contains(uuid) && !departedParticipants.contains(uuid);
            if (dealt >= minimum && rewardEligible) {
                String participationPath = lateParticipants.contains(uuid) ? "rewards.late-participation" : "rewards.participation";
                runRewardCommands(participationPath, uuid, rank, dealt, players);
                runDamageTierCommands(uuid, rank, dealt, players);
                if (eventDeaths.getOrDefault(uuid, 0) == 0) {
                    runRewardCommands("rewards.no-death", uuid, rank, dealt, players);
                }
            } else if (online != null && rewardEligible) {
                messages.send(online, "reward-ineligible", Map.of("minimum", formatDamage(minimum)));
            }
            if (!ranking.isEmpty() && ranking.get(0).getKey().equals(uuid) && result.serverRecord()) {
                Bukkit.broadcast(messages.get("server-record", Map.of("player", name == null ? uuid.toString() : name, "damage", formatDamage(dealt))));
            }
        }
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            if (ranking.get(i).getValue() >= minimum && !spectators.contains(ranking.get(i).getKey())
                    && !departedParticipants.contains(ranking.get(i).getKey())) {
                runRewardCommands("rewards.rank-" + (i + 1), ranking.get(i).getKey(), i + 1, ranking.get(i).getValue(), players);
            }
        }
        runEventEndCommands(players);
        stats.save();
    }

    private void runDamageTierCommands(UUID uuid, int rank, double dealt, String players) {
        ConfigurationSection tiers = plugin.getConfig().getConfigurationSection("rewards.damage-tiers");
        if (tiers == null) return;
        tiers.getKeys(false).stream().map(key -> {
            try { return Double.parseDouble(key); }
            catch (NumberFormatException ignored) { return null; }
        }).filter(java.util.Objects::nonNull).sorted().filter(required -> dealt >= required).forEach(required ->
                runRewardCommands("rewards.damage-tiers." + formatTierKey(required), uuid, rank, dealt, players));
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
        cancelFinishTask();
        cancelClosingCountdown();
        state = EventState.RESETTING;
        if (eventWorld != null) {
            for (Player player : new ArrayList<>(eventWorld.getPlayers())) returnPlayer(player);
        }
        resetTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (eventWorld != null) Bukkit.unloadWorld(eventWorld, false);
            eventWorld = null;
            dragon = null;
            Path runtime = Bukkit.getWorldContainer().toPath().resolve(runtimeWorldName());
            CompletableFuture.runAsync(() -> {
                try { WorldFiles.delete(runtime); }
                catch (IOException ex) { plugin.getLogger().log(Level.SEVERE, "Could not delete runtime event world", ex); }
            }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, this::clearSession));
        }, 20L);
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
        originalGameModes.clear();
        originalAllowFlight.clear();
        originalFlying.clear();
        voidRescueCooldown.clear();
        fightSecondsLeft = 0;
        closingSecondsLeft = 0;
        state = EventState.IDLE;
    }

    void recoverAfterRestart() {
        World stale = Bukkit.getWorld(runtimeWorldName());
        if (stale != null) Bukkit.unloadWorld(stale, false);
        Path runtime = Bukkit.getWorldContainer().toPath().resolve(runtimeWorldName());
        CompletableFuture.runAsync(() -> {
            try { WorldFiles.delete(runtime); }
            catch (IOException ex) { plugin.getLogger().log(Level.WARNING, "Could not clean stale event world", ex); }
        });
    }

    void shutdown() {
        cancelEventTasks();
        if (eventWorld != null) {
            for (Player player : new ArrayList<>(eventWorld.getPlayers())) returnPlayer(player);
            Bukkit.unloadWorld(eventWorld, false);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event.getPlayer().getScoreboardTags().contains("townysmp_dragon_spectator") && state == EventState.IDLE) {
            event.getPlayer().removeScoreboardTag("townysmp_dragon_spectator");
            event.getPlayer().setGameMode(GameMode.SURVIVAL);
        }
        // A crash during an event must never leave a player trapped in the disposable world.
        if (event.getPlayer().getWorld().getName().equals(runtimeWorldName()) && state == EventState.IDLE) {
            runFallback(event.getPlayer());
        } else if (departedParticipants.contains(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> returnPlayer(event.getPlayer()), 1L);
        } else if (participants.contains(event.getPlayer().getUniqueId())
                && !departedParticipants.contains(event.getPlayer().getUniqueId())
                && (state == EventState.JOINING || state == EventState.RESPAWNING || state == EventState.ACTIVE)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> teleportToEvent(event.getPlayer()), 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (state != EventState.ACTIVE || !participants.contains(event.getPlayer().getUniqueId())) return;
        departedParticipants.add(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, this::checkForBattleLoss);
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
                || !plugin.getConfig().getString("world.template-folder", "").trim().isEmpty()) return;
        Location target = event.getTo();
        if (target == null || target.getWorld() == null
                || target.getWorld().getEnvironment() != World.Environment.THE_END) return;
        String vanillaEnd = plugin.getConfig().getString("world.vanilla-end-folder", "world_the_end");
        if (!target.getWorld().getName().equals(vanillaEnd)) return;
        event.setCancelled(true);
        messages.send(event.getPlayer(), "end-temporarily-unavailable");
    }

    private void returnPlayer(Player player) {
        restoreGameMode(player);
        runFallback(player);
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
    }

    private void runFallback(Player player) {
        Location target = exitLocation();
        if (target == null) {
            plugin.getLogger().severe("No normal world is loaded and no Dragon exit location is configured.");
            return;
        }
        player.teleportAsync(target);
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
        return origin.getBlock().getRelative(0, -1, 0).getLocation();
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
                    player.teleportAsync(spectatorLocation());
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

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
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
