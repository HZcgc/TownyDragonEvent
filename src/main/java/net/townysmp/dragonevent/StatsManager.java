package net.townysmp.dragonevent;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import net.townysmp.dragonevent.TownyDragonEventPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

final class StatsManager {
    private final TownyDragonEventPlugin plugin;
    private final File target;
    private final YamlConfiguration data;

    StatsManager(TownyDragonEventPlugin plugin) {
        this.plugin = plugin;
        this.target = new File(plugin.getDataFolder(), "stats.yml");
        this.data = YamlConfiguration.loadConfiguration(this.target);
    }

    synchronized RecordResult record(UUID uuid, String name, double damage, int rank, int deaths, int crystals, int explosions) {
        this.data.set("players." + String.valueOf(uuid) + ".name", name);
        this.data.set(this.seasonRoot(uuid) + "name", name);
        double oldPersonalBest = this.personalBest(uuid);
        double oldServerRecord = this.serverRecordDamage();
        this.updatePlayer("players." + String.valueOf(uuid) + ".", damage, rank, deaths, crystals, explosions);
        this.updatePlayer(this.seasonRoot(uuid), damage, rank, deaths, crystals, explosions);
        if (damage > oldPersonalBest) {
            this.data.set(this.path(uuid, "personal-best"), damage);
        }
        if (damage > oldServerRecord) {
            this.data.set("records.server-best.damage", damage);
            this.data.set("records.server-best.player", name);
            this.data.set("records.server-best.uuid", uuid.toString());
        }
        return new RecordResult(damage > oldPersonalBest, damage > oldServerRecord);
    }

    private void updatePlayer(String root, double damage, int rank, int deaths, int crystals, int explosions) {
        int participations = this.data.getInt(root + "participations");
        int wins = this.data.getInt(root + "wins");
        int best = this.data.getInt(root + "best-rank");
        double total = this.data.getDouble(root + "total-damage");
        this.data.set(root + "participations", participations + 1);
        this.data.set(root + "total-damage", total + damage);
        this.data.set(root + "last-damage", damage);
        this.data.set(root + "deaths", this.data.getInt(root + "deaths") + deaths);
        this.data.set(root + "crystals", this.data.getInt(root + "crystals") + crystals);
        this.data.set(root + "explosions", this.data.getInt(root + "explosions") + explosions);
        this.data.set(root + "last-crystals", crystals);
        this.data.set(root + "last-explosions", explosions);
        this.data.set(root + "personal-best", Math.max(this.data.getDouble(root + "personal-best"), damage));
        if (rank == 1) {
            this.data.set(root + "wins", wins + 1);
        }
        if (rank > 0 && (best == 0 || rank < best)) {
            this.data.set(root + "best-rank", rank);
        }
    }

    int participations(UUID uuid) {
        return this.data.getInt(this.path(uuid, "participations"));
    }

    int wins(UUID uuid) {
        return this.data.getInt(this.path(uuid, "wins"));
    }

    int bestRank(UUID uuid) {
        return this.data.getInt(this.path(uuid, "best-rank"));
    }

    double totalDamage(UUID uuid) {
        return this.data.getDouble(this.path(uuid, "total-damage"));
    }

    double lastDamage(UUID uuid) {
        return this.data.getDouble(this.path(uuid, "last-damage"));
    }

    double personalBest(UUID uuid) {
        return this.data.getDouble(this.path(uuid, "personal-best"));
    }

    int deaths(UUID uuid) {
        return this.data.getInt(this.path(uuid, "deaths"));
    }

    int crystals(UUID uuid) {
        return this.data.getInt(this.path(uuid, "crystals"));
    }

    int explosions(UUID uuid) {
        return this.data.getInt(this.path(uuid, "explosions"));
    }

    int lastCrystals(UUID uuid) {
        return this.data.getInt(this.path(uuid, "last-crystals"));
    }

    int lastExplosions(UUID uuid) {
        return this.data.getInt(this.path(uuid, "last-explosions"));
    }

    int seasonParticipations(UUID uuid) {
        return this.data.getInt(this.seasonRoot(uuid) + "participations");
    }

    int seasonWins(UUID uuid) {
        return this.data.getInt(this.seasonRoot(uuid) + "wins");
    }

    int seasonBestRank(UUID uuid) {
        return this.data.getInt(this.seasonRoot(uuid) + "best-rank");
    }

    double seasonDamage(UUID uuid) {
        return this.data.getDouble(this.seasonRoot(uuid) + "total-damage");
    }

    double seasonPersonalBest(UUID uuid) {
        return this.data.getDouble(this.seasonRoot(uuid) + "personal-best");
    }

    int seasonDeaths(UUID uuid) {
        return this.data.getInt(this.seasonRoot(uuid) + "deaths");
    }

    int seasonCrystals(UUID uuid) {
        return this.data.getInt(this.seasonRoot(uuid) + "crystals");
    }

    int seasonExplosions(UUID uuid) {
        return this.data.getInt(this.seasonRoot(uuid) + "explosions");
    }

    double serverRecordDamage() {
        return this.data.getDouble("records.server-best.damage");
    }

    String serverRecordPlayer() {
        return this.data.getString("records.server-best.player", "-");
    }

    String season() {
        return this.plugin.getConfig().getString("statistics.season", "season-1");
    }

    void setSeason(String season) {
        this.plugin.getConfig().set("statistics.season", season);
        this.plugin.saveConfig();
    }

    synchronized Map<String, Object> snapshot() {
        ArrayList<LinkedHashMap<String, Object>> rows = new ArrayList<LinkedHashMap<String, Object>>();
        ConfigurationSection section = this.data.getConfigurationSection("players");
        if (section != null) {
            for (String uuid : section.getKeys(false)) {
                ConfigurationSection player = section.getConfigurationSection(uuid);
                if (player == null) continue;
                LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>(player.getValues(false));
                row.put("uuid", uuid);
                row.putIfAbsent("name", Objects.toString(Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName(), uuid));
                rows.add(row);
            }
        }
        return Map.of("players", rows);
    }

    synchronized List<String> topDamage(boolean season) {
        Object root = season ? "seasons." + this.season().replaceAll("[^A-Za-z0-9_-]", "_") + ".players" : "players";
        ConfigurationSection section = this.data.getConfigurationSection((String)root);
        if (section == null) {
            return List.of();
        }
        return section.getKeys(false).stream().filter(id -> section.getDouble(id + ".total-damage") > 0.0).sorted(Comparator.comparingDouble(id -> section.getDouble(id + ".total-damage")).reversed()).limit(5L).map(id -> section.getString(id + ".name", this.data.getString("players." + id + ".name", Objects.toString(Bukkit.getOfflinePlayer(UUID.fromString(id)).getName(), id))) + " \u00b7 " + String.format(Locale.ROOT, "%.1f", section.getDouble(id + ".total-damage"))).toList();
    }

    synchronized List<String> topWins() {
        ConfigurationSection section = this.data.getConfigurationSection("players");
        if (section == null) {
            return List.of();
        }
        return section.getKeys(false).stream().filter(id -> section.getInt(id + ".wins") > 0).sorted(Comparator.comparingInt(id -> section.getInt(id + ".wins")).reversed()).limit(5L).map(id -> section.getString(id + ".name", Objects.toString(Bukkit.getOfflinePlayer(UUID.fromString(id)).getName(), id)) + " \u00b7 " + section.getInt(id + ".wins") + " wins").toList();
    }

    synchronized void save() {
        try {
            Path path = this.target.toPath();
            Path tmp = path.resolveSibling(String.valueOf(path.getFileName()) + ".tmp");
            Files.writeString(tmp, (CharSequence)this.data.saveToString(), new OpenOption[0]);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml", ex);
        }
    }

    private String path(UUID uuid, String key) {
        return "players." + String.valueOf(uuid) + "." + key;
    }

    private String seasonRoot(UUID uuid) {
        String safe = this.season().replaceAll("[^A-Za-z0-9_-]", "_");
        return "seasons." + safe + ".players." + String.valueOf(uuid) + ".";
    }

    record RecordResult(boolean personalBest, boolean serverRecord) {
    }
}

