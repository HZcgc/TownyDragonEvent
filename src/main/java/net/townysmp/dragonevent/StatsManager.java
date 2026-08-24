package net.townysmp.dragonevent;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

final class StatsManager {
    record RecordResult(boolean personalBest, boolean serverRecord) {}

    private final TownyDragonEventPlugin plugin;
    private final File target;
    private final YamlConfiguration data;

    StatsManager(TownyDragonEventPlugin plugin) {
        this.plugin = plugin;
        this.target = new File(plugin.getDataFolder(), "stats.yml");
        this.data = YamlConfiguration.loadConfiguration(target);
    }

    synchronized RecordResult record(UUID uuid, String name, double damage, int rank, int deaths,
                                     int crystals, int explosions) {
        double oldPersonalBest = personalBest(uuid);
        double oldServerRecord = serverRecordDamage();
        updatePlayer("players." + uuid + ".", damage, rank, deaths, crystals, explosions);
        updatePlayer(seasonRoot(uuid), damage, rank, deaths, crystals, explosions);
        if (damage > oldPersonalBest) data.set(path(uuid, "personal-best"), damage);
        if (damage > oldServerRecord) {
            data.set("records.server-best.damage", damage);
            data.set("records.server-best.player", name);
            data.set("records.server-best.uuid", uuid.toString());
        }
        return new RecordResult(damage > oldPersonalBest, damage > oldServerRecord);
    }

    private void updatePlayer(String root, double damage, int rank, int deaths, int crystals, int explosions) {
        int participations = data.getInt(root + "participations");
        int wins = data.getInt(root + "wins");
        int best = data.getInt(root + "best-rank");
        double total = data.getDouble(root + "total-damage");
        data.set(root + "participations", participations + 1);
        data.set(root + "total-damage", total + damage);
        data.set(root + "last-damage", damage);
        data.set(root + "deaths", data.getInt(root + "deaths") + deaths);
        data.set(root + "crystals", data.getInt(root + "crystals") + crystals);
        data.set(root + "explosions", data.getInt(root + "explosions") + explosions);
        data.set(root + "last-crystals", crystals);
        data.set(root + "last-explosions", explosions);
        data.set(root + "personal-best", Math.max(data.getDouble(root + "personal-best"), damage));
        if (rank == 1) data.set(root + "wins", wins + 1);
        if (rank > 0 && (best == 0 || rank < best)) data.set(root + "best-rank", rank);
    }

    int participations(UUID uuid) { return data.getInt(path(uuid, "participations")); }
    int wins(UUID uuid) { return data.getInt(path(uuid, "wins")); }
    int bestRank(UUID uuid) { return data.getInt(path(uuid, "best-rank")); }
    double totalDamage(UUID uuid) { return data.getDouble(path(uuid, "total-damage")); }
    double lastDamage(UUID uuid) { return data.getDouble(path(uuid, "last-damage")); }
    double personalBest(UUID uuid) { return data.getDouble(path(uuid, "personal-best")); }
    int deaths(UUID uuid) { return data.getInt(path(uuid, "deaths")); }
    int crystals(UUID uuid) { return data.getInt(path(uuid, "crystals")); }
    int explosions(UUID uuid) { return data.getInt(path(uuid, "explosions")); }
    int lastCrystals(UUID uuid) { return data.getInt(path(uuid, "last-crystals")); }
    int lastExplosions(UUID uuid) { return data.getInt(path(uuid, "last-explosions")); }

    int seasonParticipations(UUID uuid) { return data.getInt(seasonRoot(uuid) + "participations"); }
    int seasonWins(UUID uuid) { return data.getInt(seasonRoot(uuid) + "wins"); }
    int seasonBestRank(UUID uuid) { return data.getInt(seasonRoot(uuid) + "best-rank"); }
    double seasonDamage(UUID uuid) { return data.getDouble(seasonRoot(uuid) + "total-damage"); }
    double seasonPersonalBest(UUID uuid) { return data.getDouble(seasonRoot(uuid) + "personal-best"); }
    int seasonDeaths(UUID uuid) { return data.getInt(seasonRoot(uuid) + "deaths"); }
    int seasonCrystals(UUID uuid) { return data.getInt(seasonRoot(uuid) + "crystals"); }
    int seasonExplosions(UUID uuid) { return data.getInt(seasonRoot(uuid) + "explosions"); }

    double serverRecordDamage() { return data.getDouble("records.server-best.damage"); }
    String serverRecordPlayer() { return data.getString("records.server-best.player", "-"); }
    String season() { return plugin.getConfig().getString("statistics.season", "season-1"); }

    void setSeason(String season) {
        plugin.getConfig().set("statistics.season", season);
        plugin.saveConfig();
    }

    synchronized void save() {
        try { data.save(target); }
        catch (IOException ex) { plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml", ex); }
    }

    private String path(UUID uuid, String key) { return "players." + uuid + "." + key; }
    private String seasonRoot(UUID uuid) {
        String safe = season().replaceAll("[^A-Za-z0-9_-]", "_");
        return "seasons." + safe + ".players." + uuid + ".";
    }
}
