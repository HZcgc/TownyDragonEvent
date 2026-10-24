package net.townysmp.dragonevent;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual production methods against the real Paper API; no running Minecraft server. */
final class NativeBossMechanicsTest {
    MockedStatic<Bukkit> bukkit;
    TownyDragonEventPlugin plugin;
    DragonEventManager manager;
    Messages messages;
    StatsManager stats;
    YamlConfiguration config;
    BukkitScheduler scheduler;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        plugin = mock(TownyDragonEventPlugin.class);
        config = YamlConfiguration.loadConfiguration(new File(System.getProperty("dragon.config", "src/main/resources/config.yml")));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DragonTest"));
        var bar = mock(BossBar.class);
        when(bar.getPlayers()).thenReturn(List.of());
        bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class), any(BarFlag[].class))).thenReturn(bar);
        scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
        when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));
        messages = mock(Messages.class);
        stats = mock(StatsManager.class);
        manager = new DragonEventManager(plugin, messages, stats);
    }
    @AfterEach void close() { bukkit.close(); }

    static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args);
    }
    static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }

    @Test void actualManagerUsesConfiguredHealthForEveryMode() throws Exception {
        for (EventMode mode : EventMode.values()) {
            set(manager, "eventMode", mode);
            assertEquals(1024.0, call(manager, "configureDifficulty", new Class<?>[] {int.class}, 1));
            assertEquals(mode == EventMode.NORMAL ? 5000.0 : 10000.0, field(manager, "effectiveDragonHealth"));
            call(manager, "configureDifficulty", new Class<?>[] {int.class}, 150);
            assertEquals(mode == EventMode.NORMAL ? 40000.0 : 80000.0, field(manager, "effectiveDragonHealth"));
        }
    }

    @Test void actualFightClockSchedulesThirtyMinutesForEveryMode() throws Exception {
        var timers = new ArrayList<Runnable>();
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), eq(20L), eq(20L))).thenAnswer(inv -> {
            timers.add(inv.getArgument(1)); return mock(BukkitTask.class);
        });
        for (EventMode mode : EventMode.values()) {
            set(manager, "eventMode", mode);
            set(manager, "state", EventState.ACTIVE);
            call(manager, "startFightClock");
            assertEquals(1800, manager.fightSecondsRemaining());
            timers.getLast().run();
            assertEquals(1799, manager.fightSecondsRemaining());
        }
        verify(scheduler, times(3)).runTaskLater(eq(plugin), any(Runnable.class), eq(36000L));
    }

    @Test void commandCanForceEachModeWhilePlainStartUsesAutomaticSelection() {
        var target = mock(DragonEventManager.class);
        var cmd = new DragonCommand(plugin, target, messages, stats);
        var sender = mock(CommandSender.class);
        when(sender.hasPermission("townysmp.dragon.admin")).thenReturn(true);
        cmd.onCommand(sender, null, "dragon", new String[] {"start"});
        verify(target).start(null, null);
        cmd.onCommand(sender, null, "dragon", new String[] {"start", "normal", "10"});
        verify(target).start(10, EventMode.NORMAL);
        cmd.onCommand(sender, null, "dragon", new String[] {"start", "april_fools", "10"});
        verify(target).start(10, EventMode.APRIL_FOOLS);
        cmd.onCommand(sender, null, "dragon", new String[] {"start", "freddie", "10"});
        verify(target).start(10, EventMode.FREDDIE);
        cmd.onCommand(sender, null, "dragon", new String[] {"start", "auto", "10"});
        verify(target).start(10, null);
    }

    @Test void freddieReadsHisOwnSettingsAndFallsBackToSharedExplosiveSettings() throws Exception {
        set(manager, "eventMode", EventMode.FREDDIE);
        config.set("freddie.projectiles.speed", 1.6);
        assertEquals(1.6, call(manager, "jokeDouble", new Class<?>[] {String.class, double.class}, "april-fools.projectiles.speed", 1.1));
        config.set("freddie.projectiles.speed", null);
        assertEquals(1.25, call(manager, "jokeDouble", new Class<?>[] {String.class, double.class}, "april-fools.projectiles.speed", 1.1));
    }

    @Test void freddieSpawnsAFrogAndFoolsSpawnsACreeper() throws Exception {
        World world = mock(World.class);
        EnderDragon dragon = mock(EnderDragon.class);
        when(dragon.isValid()).thenReturn(true);
        when(dragon.getLocation()).thenReturn(new Location(world, 0, 100, 0));
        Frog frog = mock(Frog.class);
        Creeper creeper = mock(Creeper.class);
        when(world.spawn(any(Location.class), eq(Frog.class), any(Consumer.class))).thenReturn(frog);
        when(world.spawn(any(Location.class), eq(Creeper.class), any(Consumer.class))).thenReturn(creeper);
        set(manager, "eventWorld", world);
        set(manager, "dragon", dragon);
        set(manager, "state", EventState.ACTIVE);
        set(manager, "eventMode", EventMode.FREDDIE);
        call(manager, "spawnAprilCreeper");
        assertSame(frog, field(manager, "aprilBoss"));
        set(manager, "aprilBoss", null);
        set(manager, "eventMode", EventMode.APRIL_FOOLS);
        call(manager, "spawnAprilCreeper");
        assertSame(creeper, field(manager, "aprilBoss"));
    }

    @Test void bothExplosiveModesProduceEightShotsPerSecondAndRespectTheEntityCap() throws Exception {
        World world = mock(World.class);
        when(world.getPlayers()).thenReturn(List.of());
        EnderDragon dragon = mock(EnderDragon.class);
        when(dragon.isValid()).thenReturn(true);
        when(dragon.getPhase()).thenReturn(EnderDragon.Phase.CIRCLING);
        when(dragon.getLocation()).thenReturn(new Location(world, 0, 100, 0));
        var boss = mock(LivingEntity.class);
        when(boss.isValid()).thenReturn(true);
        when(boss.getLocation()).thenReturn(new Location(world, 0, 95, 0));
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 10, 61, 0));
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        Map<UUID, TNTPrimed> entities = new HashMap<>();
        when(world.spawn(any(Location.class), eq(TNTPrimed.class))).thenAnswer(inv -> {
            TNTPrimed tnt = mock(TNTPrimed.class);
            UUID uuid = UUID.randomUUID();
            when(tnt.getUniqueId()).thenReturn(uuid);
            when(tnt.isValid()).thenReturn(true);
            entities.put(uuid, tnt);
            return tnt;
        });
        bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(inv -> entities.get(inv.getArgument(0)));
        @SuppressWarnings("unchecked") Set<UUID> participants = (Set<UUID>) field(manager, "participants");
        participants.add(id);
        set(manager, "state", EventState.ACTIVE);
        set(manager, "eventWorld", world);
        set(manager, "dragon", dragon);
        set(manager, "aprilBoss", boss);
        // Make the random shot type deterministic while testing actual barrage cadence.
        config.set("april-fools.projectiles.tnt-chance", 1.0);
        config.set("freddie.projectiles.tnt-chance", 1.0);
        config.set("april-fools.projectiles.portal-cease-fire.enabled", false);
        config.set("freddie.projectiles.portal-cease-fire.enabled", false);
        for (EventMode mode : List.of(EventMode.APRIL_FOOLS, EventMode.FREDDIE)) {
            set(manager, "eventMode", mode);
            set(manager, "aprilBarrageTicks", 0);
            int before = entities.size();
            for (int tick = 0; tick < 19; tick++) call(manager, "tickAprilFoolsEffects");
            assertEquals(before, entities.size());
            call(manager, "tickAprilFoolsEffects");
            assertEquals(before + 8, entities.size());
            for (int tick = 0; tick < 200; tick++) call(manager, "tickAprilFoolsEffects");
            assertTrue(((Deque<?>) field(manager, "aprilProjectiles")).size() <= 64);
            assertTrue(((Deque<?>) field(manager, "primedTnt")).size() <= 64);
            set(manager, "enraged", true);
            set(manager, "aprilBarrageTicks", 0);
            int beforeEnrage = entities.size();
            for (int tick = 0; tick < 14; tick++) call(manager, "tickAprilFoolsEffects");
            assertEquals(beforeEnrage, entities.size());
            call(manager, "tickAprilFoolsEffects");
            assertEquals(beforeEnrage + 8, entities.size());
            set(manager, "enraged", false);
        }
        assertTrue(entities.values().stream().anyMatch(tnt -> mockingDetails(tnt).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("remove"))));
    }

    @Test void victoryKeysAreNotPaidTwiceAndEliminatedPlayersReceiveNone() throws Exception {
        var pm = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
        bukkit.when(Bukkit::getConsoleSender).thenReturn(mock(ConsoleCommandSender.class));
        when(stats.record(any(), anyString(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new StatsManager.RecordResult(false, false));
        @SuppressWarnings("unchecked") Set<UUID> participants = (Set<UUID>) field(manager, "participants");
        @SuppressWarnings("unchecked") Set<UUID> spectators = (Set<UUID>) field(manager, "spectators");
        @SuppressWarnings("unchecked") Map<UUID, Double> damage = (Map<UUID, Double>) field(manager, "damage");
        double[] amounts = {1000, 500, 100, 50, 1, 5000};
        for (int i = 0; i < amounts.length; i++) {
            UUID id = UUID.randomUUID();
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.getName()).thenReturn("Player" + i);
            bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(player);
            participants.add(id); damage.put(id, amounts[i]);
            if (i == 5) spectators.add(id);
        }
        var commands = new ArrayList<String>();
        bukkit.when(() -> Bukkit.dispatchCommand(any(CommandSender.class), anyString())).thenAnswer(inv -> {
            commands.add(inv.getArgument(1)); return true;
        });
        call(manager, "distributeRewards");
        call(manager, "distributeRewards");
        assertEquals(1, Collections.frequency(commands, "cratekey give Player0 dragon 3"));
        assertEquals(1, Collections.frequency(commands, "cratekey give Player1 dragon 2"));
        assertEquals(1, Collections.frequency(commands, "cratekey give Player2 dragon 1"));
        assertFalse(commands.stream().anyMatch(c -> c.startsWith("cratekey give Player3 ")));
        assertTrue(commands.stream().anyMatch(c -> c.startsWith("eco give Player3 ")));
        assertFalse(commands.stream().anyMatch(c -> c.contains("Player4")));
        assertFalse(commands.stream().anyMatch(c -> c.contains("Player5")));
        verify(stats, times(6)).record(any(), anyString(), anyDouble(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test void allFourBoardsShowTheSameLiveEventAndMoveFiveBlocksOutward() throws Exception {
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());
        var positions = new ArrayList<Location>();
        var displays = new ArrayList<TextDisplay>();
        when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class))).thenAnswer(inv -> {
            Location location = inv.getArgument(0);
            positions.add(location.clone());
            TextDisplay display = mock(TextDisplay.class);
            when(display.isValid()).thenReturn(true);
            when(display.getWorld()).thenReturn(world);
            @SuppressWarnings("unchecked") Consumer<TextDisplay> configure = inv.getArgument(2);
            configure.accept(display);
            displays.add(display);
            return display;
        });
        EventDisplays boards = (EventDisplays) field(manager, "displays");
        Location center = new Location(world, 0.5, 60.5, 0.5);
        List<String> live = List.of("Alice · 1234.5", "Bob · 678.9");
        boards.update(world, center, EventState.ACTIVE, 1800, 2, live,
                List.of("OLD SEASON"), List.of("OLD LIFETIME"), List.of("OLD WINS"));
        assertEquals(4, positions.size());
        for (Location position : positions) {
            assertEquals(13.5, Math.hypot(position.getX() - center.getX(), position.getZ() - center.getZ()), 1e-6);
            assertEquals(5.0, Math.hypot(position.getX() - center.getX(), position.getZ() - center.getZ()) - 8.5, 1e-6);
            assertEquals(4.5, position.getY() - center.getY(), 1e-6);
        }
        for (TextDisplay display : displays) {
            var capture = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
            verify(display).text(capture.capture());
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(capture.getValue());
            assertTrue(text.contains("Alice · 1234.5"));
            assertTrue(text.contains("30:00"));
            assertFalse(text.contains("OLD"));
        }
        boards.update(world, center, EventState.ACTIVE, 1799, 2, List.of("Bob · 2000.0"), List.of(), List.of(), List.of());
        for (TextDisplay display : displays) verify(display, times(2)).text(any());
        boards.update(world, center, EventState.RESETTING, 0, 0, List.of(), List.of(), List.of(), List.of());
        for (TextDisplay display : displays) verify(display).remove();
    }

    @Test void ownTntCannotDamageEitherHiddenCarrier() throws Exception {
        EnderDragon dragon = mock(EnderDragon.class);
        when(dragon.isValid()).thenReturn(true);
        when(dragon.getVelocity()).thenReturn(new org.bukkit.util.Vector(0.1, 0, 0.1));
        TNTPrimed tnt = mock(TNTPrimed.class);
        when(tnt.getScoreboardTags()).thenReturn(Set.of("townysmp_april_projectile"));
        set(manager, "dragon", dragon);
        set(manager, "state", EventState.ACTIVE);
        for (EventMode mode : List.of(EventMode.APRIL_FOOLS, EventMode.FREDDIE)) {
            set(manager, "eventMode", mode);
            var event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
            when(event.getEntity()).thenReturn(dragon);
            when(event.getDamager()).thenReturn(tnt);
            manager.onAprilProjectileHitsCarrier(event);
            verify(event).setCancelled(true);
        }
    }

    @Test void finalPhaseTriggersAtQuarterHealthOnceAndCanBeDisabled() throws Exception {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(world.getPlayers()).thenReturn(List.of(player));
        EnderDragon dragon = mock(EnderDragon.class);
        when(dragon.isValid()).thenReturn(true);
        when(dragon.getHealth()).thenReturn(300.0);
        when(messages.getRaw(anyString(), anyMap())).thenReturn(net.kyori.adventure.text.Component.text("Final phase"));
        set(manager, "eventWorld", world);
        set(manager, "dragon", dragon);
        set(manager, "state", EventState.ACTIVE);
        set(manager, "effectiveDragonHealth", 5000.0);
        call(manager, "updateBossPhase");
        assertEquals(false, field(manager, "enraged"));
        when(dragon.getHealth()).thenReturn(256.0);
        call(manager, "updateBossPhase");
        assertEquals(true, field(manager, "enraged"));
        call(manager, "updateBossPhase");
        verify(messages, times(1)).send(eq(player), eq("boss-enraged"), anyMap());
        set(manager, "enraged", false);
        config.set("event.enrage.enabled", false);
        call(manager, "updateBossPhase");
        assertEquals(false, field(manager, "enraged"));
    }

    @Test void actionbarShowsPersonalRankDamageBossHealthAndRemainingTime() throws Exception {
        World world = mock(World.class);
        Player alice = mock(Player.class);
        Player bob = mock(Player.class);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(alice.getUniqueId()).thenReturn(a); when(bob.getUniqueId()).thenReturn(b);
        when(world.getPlayers()).thenReturn(List.of(alice, bob));
        EnderDragon dragon = mock(EnderDragon.class);
        when(dragon.isValid()).thenReturn(true); when(dragon.getHealth()).thenReturn(512.0);
        set(manager, "eventWorld", world); set(manager, "dragon", dragon);
        set(manager, "effectiveDragonHealth", 10000.0); set(manager, "fightSecondsLeft", 1700);
        set(manager, "eventMode", EventMode.FREDDIE);
        @SuppressWarnings("unchecked") Map<UUID, Double> damage = (Map<UUID, Double>) field(manager, "damage");
        damage.put(a, 500.0); damage.put(b, 1000.0);
        call(manager, "sendFightActionBar");
        @SuppressWarnings("unchecked") var capture = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(messages, times(2)).getRaw(eq("fight-actionbar"), capture.capture());
        assertEquals("2", capture.getAllValues().get(0).get("rank"));
        assertEquals("500", capture.getAllValues().get(0).get("damage"));
        assertEquals("1", capture.getAllValues().get(1).get("rank"));
        assertEquals("50", capture.getAllValues().get(1).get("hp_percent"));
        assertEquals("Freddie", capture.getAllValues().get(1).get("boss"));
        assertEquals("28m 20s", capture.getAllValues().get(1).get("time"));
    }
}
