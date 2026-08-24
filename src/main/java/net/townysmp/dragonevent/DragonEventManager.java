package net.townysmp.dragonevent;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Map<String, UUID> joinedIps = new HashMap<>();
    private EventState state = EventState.IDLE;
    private World eventWorld;
    private EnderDragon dragon;
    private BukkitTask ticker;
    private BukkitTask finishTask;
    private BukkitTask resetTask;
    private int secondsLeft;

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
    int currentRank(UUID uuid) {
        if (!damage.containsKey(uuid) || damage.getOrDefault(uuid, 0.0) <= 0) return 0;
        List<UUID> ranking = damage.entrySet().stream().filter(entry -> entry.getValue() > 0)
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
        removeEventEntities();
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
        String ip = address(player);
        if (plugin.getConfig().getBoolean("event.one-account-per-ip", true)
                && ip != null && joinedIps.containsKey(ip) && !joinedIps.get(ip).equals(player.getUniqueId())) {
            messages.send(player, "alt-blocked");
            return false;
        }
        participants.add(player.getUniqueId());
        if (state == EventState.RESPAWNING || state == EventState.ACTIVE) {
            lateParticipants.add(player.getUniqueId());
        }
        if (ip != null) joinedIps.put(ip, player.getUniqueId());
        damage.put(player.getUniqueId(), 0.0);
        if (state == EventState.SCHEDULED) {
            messages.send(player, "registered");
        } else {
            teleportToEvent(player);
            messages.send(player, "joined");
        }
        return true;
    }

    boolean leave(Player player) {
        if (!participants.remove(player.getUniqueId())) return false;
        joinedIps.values().removeIf(uuid -> uuid.equals(player.getUniqueId()));
        if (returnLocations.containsKey(player.getUniqueId())
                || (eventWorld != null && player.getWorld().equals(eventWorld))) {
            returnPlayer(player);
        }
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
        returnLocations.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
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
        if (battle == null || battle.getEndPortalLocation() == null) {
            plugin.getLogger().severe("Template world has no completed End portal. Prepare the template after killing its original dragon once.");
            finish(false);
            return;
        }
        state = EventState.RESPAWNING;
        messages.broadcast("respawn-start", Map.of(), "respawn");
        Location center = battle.getEndPortalLocation().clone().add(0.5, 1.0, 0.5);
        for (int[] offset : CRYSTAL_OFFSETS) {
            EnderCrystal crystal = eventWorld.spawn(center.clone().add(offset[0], 0, offset[1]), EnderCrystal.class);
            crystal.setShowingBottom(false);
            crystal.addScoreboardTag("townysmp_dragon_respawn");
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> battle.initiateRespawn(), 2L);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::watchRespawn, 20L, 20L);
    }

    private void watchRespawn() {
        if (state != EventState.RESPAWNING) return;
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
        messages.broadcast("fight-start", Map.of("health", String.format(Locale.US, "%.0f", health)), "fight");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (state != EventState.ACTIVE || event.getEntity() != dragon) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || !participants.contains(attacker.getUniqueId())) return;
        damage.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (state == EventState.ACTIVE && event.getEntity() == dragon) finish(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEggDrop(ItemSpawnEvent event) {
        if (eventWorld != null && event.getLocation().getWorld().equals(eventWorld)
                && event.getEntity().getItemStack().getType() == Material.DRAGON_EGG) event.setCancelled(true);
    }

    private void finish(boolean victory) {
        if (state == EventState.FINISHING || state == EventState.RESETTING || state == EventState.IDLE) return;
        cancelTicker();
        state = EventState.FINISHING;
        if (victory) {
            distributeRewards();
            int delay = plugin.getConfig().getInt("event.finish-delay-seconds", 20);
            messages.broadcast("victory", Map.of("time", formatTime(delay)), "victory");
            finishTask = Bukkit.getScheduler().runTaskLater(plugin, this::evacuateAndReset, Math.max(5, delay) * 20L);
        } else {
            finishTask = Bukkit.getScheduler().runTaskLater(plugin, this::evacuateAndReset, 20L);
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
                .filter(entry -> entry.getValue() > 0)
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
            StatsManager.RecordResult result = stats.record(uuid, name == null ? uuid.toString() : name, dealt, rank);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                String resultKey = rank > 0 ? "personal-result" : "personal-result-unranked";
                messages.send(online, resultKey, Map.of("rank", Integer.toString(rank), "damage", formatDamage(dealt)));
                if (result.personalBest()) messages.send(online, "personal-best", Map.of("damage", formatDamage(dealt)));
            }
            if (dealt >= minimum) {
                String participationPath = lateParticipants.contains(uuid) ? "rewards.late-participation" : "rewards.participation";
                runRewardCommands(participationPath, uuid, rank, dealt, players);
                runDamageTierCommands(uuid, rank, dealt, players);
            } else if (online != null) {
                messages.send(online, "reward-ineligible", Map.of("minimum", formatDamage(minimum)));
            }
            if (!ranking.isEmpty() && ranking.get(0).getKey().equals(uuid) && result.serverRecord()) {
                Bukkit.broadcast(messages.get("server-record", Map.of("player", name == null ? uuid.toString() : name, "damage", formatDamage(dealt))));
            }
        }
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            if (ranking.get(i).getValue() >= minimum) {
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
        returnLocations.clear();
        joinedIps.clear();
        damage.clear();
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
        // A crash during an event must never leave a player trapped in the disposable world.
        if (event.getPlayer().getWorld().getName().equals(runtimeWorldName()) && state == EventState.IDLE) {
            runFallback(event.getPlayer());
        } else if (participants.contains(event.getPlayer().getUniqueId())
                && (state == EventState.JOINING || state == EventState.RESPAWNING || state == EventState.ACTIVE)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> teleportToEvent(event.getPlayer()), 20L);
        }
    }

    private void returnPlayer(Player player) {
        Location location = returnLocations.remove(player.getUniqueId());
        if (location == null || location.getWorld() == null || !player.teleport(location)) runFallback(player);
    }

    private void runFallback(Player player) {
        String command = plugin.getConfig().getString("world.fallback-command", "spawn {player}")
                .replace("{player}", player.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private Location joinLocation() {
        return new Location(eventWorld,
                plugin.getConfig().getDouble("world.join-location.x", 0.5),
                plugin.getConfig().getDouble("world.join-location.y", 65),
                plugin.getConfig().getDouble("world.join-location.z", 18.5),
                (float) plugin.getConfig().getDouble("world.join-location.yaw", 180),
                (float) plugin.getConfig().getDouble("world.join-location.pitch", 0));
    }

    private void removeEventEntities() {
        for (Entity entity : eventWorld.getEntities()) {
            if (entity instanceof EnderDragon || entity instanceof EnderCrystal || entity instanceof Item) entity.remove();
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

    private String address(Player player) {
        if (player.getAddress() == null) return null;
        InetAddress address = player.getAddress().getAddress();
        return address == null ? null : address.getHostAddress();
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
        if (resetTask != null) resetTask.cancel();
        resetTask = null;
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
