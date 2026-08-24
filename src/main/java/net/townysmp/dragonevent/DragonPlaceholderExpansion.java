package net.townysmp.dragonevent;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

final class DragonPlaceholderExpansion extends PlaceholderExpansion {
    private final TownyDragonEventPlugin plugin;
    private final DragonEventManager event;
    private final StatsManager stats;

    DragonPlaceholderExpansion(TownyDragonEventPlugin plugin, DragonEventManager event, StatsManager stats) {
        this.plugin = plugin;
        this.event = event;
        this.stats = stats;
    }

    @Override public @NotNull String getIdentifier() { return "townydragon"; }
    @Override public @NotNull String getAuthor() { return "TownySMP"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameter) {
        String key = parameter.toLowerCase(Locale.ROOT);
        if (key.equals("state")) return event.state().name();
        if (key.equals("time_remaining")) return event.timeRemaining();
        if (key.equals("seconds_remaining")) return Integer.toString(event.secondsRemaining());
        if (key.equals("registered_count")) return Integer.toString(event.playerCount());
        if (player == null) return "";
        UUID uuid = player.getUniqueId();
        return switch (key) {
            case "registered" -> Boolean.toString(event.isRegistered(uuid));
            case "damage" -> format(event.currentDamage(uuid));
            case "rank" -> Integer.toString(event.currentRank(uuid));
            case "total_damage" -> format(stats.totalDamage(uuid));
            case "last_damage" -> format(stats.lastDamage(uuid));
            case "participations" -> Integer.toString(stats.participations(uuid));
            case "wins" -> Integer.toString(stats.wins(uuid));
            case "best_rank" -> Integer.toString(stats.bestRank(uuid));
            default -> null;
        };
    }

    private String format(double value) { return String.format(Locale.US, "%.2f", value); }
}
