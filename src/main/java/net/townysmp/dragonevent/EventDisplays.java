package net.townysmp.dragonevent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

final class EventDisplays {
    private static final String TAG = "townyreborn_dragon_board";
    private final TownyDragonEventPlugin plugin;
    private final BossBar lobby;
    private final List<TextDisplay> boards = new ArrayList<>();
    private String previous = "";

    EventDisplays(TownyDragonEventPlugin plugin) {
        this.plugin = plugin;
        lobby = Bukkit.createBossBar("Towny Reborn · Dragon lobby", BarColor.PINK, BarStyle.SOLID);
    }

    // Keep the build34 signature for integration compatibility. All four sides
    // intentionally show live data; lifetime statistics remain in /dragon stats.
    void update(World world, Location center, EventState state, int seconds, int players,
                List<String> live, List<String> season, List<String> allTime, List<String> wins) {
        if (world == null || center == null || state == EventState.IDLE || state == EventState.RESETTING) {
            clear();
            return;
        }
        boolean joining = state == EventState.JOINING;
        String time = String.format(Locale.ROOT, "%02d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60);
        if (joining && plugin.getConfig().getBoolean("displays.lobby-countdown", true)) {
            lobby.setTitle("Towny Reborn · Dragon starts in " + time + " · " + players + " players");
            lobby.setProgress(Math.max(0.0, Math.min(1.0, (double) seconds /
                    Math.max(1, plugin.getConfig().getInt("event.join-seconds", 300)))));
            var visible = new HashSet<>(world.getPlayers());
            for (Player p : new ArrayList<>(lobby.getPlayers())) if (!visible.contains(p)) lobby.removePlayer(p);
            for (Player p : visible) {
                if (!lobby.getPlayers().contains(p)) lobby.addPlayer(p);
                if (plugin.getConfig().getBoolean("displays.lobby-actionbar", true))
                    p.sendActionBar(Component.text("Dragon starts in " + time, NamedTextColor.LIGHT_PURPLE));
            }
            lobby.setVisible(true);
        } else {
            lobby.removeAll();
            lobby.setVisible(false);
        }
        if (!plugin.getConfig().getBoolean("displays.portal-leaderboards", true)) {
            removeBoards();
            return;
        }
        if (boards.stream().anyMatch(b -> !b.isValid() || !b.getWorld().equals(world))) removeBoards();
        if (boards.isEmpty()) createBoards(world, center);

        String status = switch (state) {
            case JOINING -> "Starts in " + time + " · " + players + " players";
            case ACTIVE -> "Time left " + time + " · " + players + " fighters";
            case FINISHING -> "Final results · Arena closes in " + time;
            case RESPAWNING -> "Summoning the boss";
            default -> "Registration open · " + players + " players";
        };
        String entries = live.isEmpty() ? "Damage rankings appear when the fight starts." : String.join("\n", live);
        String key = status + "\n" + entries;
        if (key.equals(previous)) return;
        Component text = Component.empty().append(Component.text("TOWNY", TextColor.color(0xFF55FF), TextDecoration.BOLD))
                .append(Component.text("REBORN\n", TextColor.color(0x55FF55), TextDecoration.BOLD))
                .append(Component.text("LIVE DAMAGE · CURRENT EVENT\n", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(status + "\n\n", NamedTextColor.GRAY))
                .append(Component.text(entries, NamedTextColor.WHITE));
        for (TextDisplay board : boards) if (board.isValid()) board.text(text);
        previous = key;
    }

    private void createBoards(World world, Location center) {
        for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        double radius = BossBalance.finite(plugin.getConfig().getDouble("displays.portal-radius", 3.0)
                + plugin.getConfig().getDouble("displays.distance-from-portal", 10.5), 13.5, 3.0, 60.0);
        double y = BossBalance.finite(plugin.getConfig().getDouble("displays.height", 4.5), 4.5, 0.0, 30.0);
        for (int side = 0; side < 4; side++) {
            double angle = Math.toRadians(side * 90.0);
            Location location = center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            boards.add(world.spawn(location, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setGravity(false);
                d.setInvulnerable(true);
                d.addScoreboardTag(TAG);
                d.setShadowed(true);
                d.setSeeThrough(false);
                d.setBackgroundColor(Color.fromARGB(210, 12, 16, 28));
                d.setLineWidth(310);
                d.setViewRange((float) BossBalance.finite(plugin.getConfig().getDouble("displays.view-distance", 64.0) / 64.0,
                        1.0, 0.25, 4.0));
            }));
        }
        previous = "";
    }

    private void removeBoards() {
        boards.forEach(Entity::remove);
        boards.clear();
        previous = "";
    }

    void clear() {
        lobby.removeAll();
        lobby.setVisible(false);
        removeBoards();
    }
}
