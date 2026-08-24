package net.townysmp.dragonevent;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

final class StatsManager {
    private final TownyDragonEventPlugin plugin;
    private final File target;
    private final YamlConfiguration data;

    StatsManager(TownyDragonEventPlugin plugin) {
        this.plugin = plugin;
        this.target = new File(plugin.getDataFolder(), "stats.yml");
        this.data = YamlConfiguration.loadConfiguration(target);
    }

    synchronized void record(UUID uuid, double damage, int rank) {
        String root = "players." + uuid + ".";
        data.set(root + "participations", participations(uuid) + 1);
        data.set(root + "total-damage", totalDamage(uuid) + damage);
        data.set(root + "last-damage", damage);
        if (rank == 1) data.set(root + "wins", wins(uuid) + 1);
        int best = bestRank(uuid);
        if (rank > 0 && (best == 0 || rank < best)) data.set(root + "best-rank", rank);
    }

    int participations(UUID uuid) { return data.getInt(path(uuid, "participations")); }
    int wins(UUID uuid) { return data.getInt(path(uuid, "wins")); }
    int bestRank(UUID uuid) { return data.getInt(path(uuid, "best-rank")); }
    double totalDamage(UUID uuid) { return data.getDouble(path(uuid, "total-damage")); }
    double lastDamage(UUID uuid) { return data.getDouble(path(uuid, "last-damage")); }

    synchronized void save() {
        try { data.save(target); }
        catch (IOException ex) { plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml", ex); }
    }

    private String path(UUID uuid, String key) { return "players." + uuid + "." + key; }
}
