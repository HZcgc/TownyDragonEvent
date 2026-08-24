package net.townysmp.dragonevent;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DragonCommand implements CommandExecutor, TabCompleter {
    private final TownyDragonEventPlugin plugin;
    private final DragonEventManager manager;
    private final Messages messages;
    private final StatsManager stats;

    DragonCommand(TownyDragonEventPlugin plugin, DragonEventManager manager, Messages messages, StatsManager stats) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.stats = stats;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            status(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (!(sender instanceof Player player)) sender.sendMessage("Only players can join.");
                else if (!player.hasPermission("townysmp.dragon.join")) sender.sendMessage("No permission.");
                else manager.join(player);
            }
            case "leave" -> {
                if (sender instanceof Player player) manager.leave(player);
            }
            case "status" -> status(sender);
            case "stats" -> showStats(sender, args);
            case "start" -> {
                if (!admin(sender)) return true;
                EventMode mode = EventMode.NORMAL;
                Integer seconds = null;
                if (args.length > 1) {
                    EventMode parsed = EventMode.parse(args[1]);
                    if (parsed != null) {
                        mode = parsed;
                        if (args.length > 2) {
                            try { seconds = Integer.parseInt(args[2]); }
                            catch (NumberFormatException ignored) {
                                sender.sendMessage("Usage: /dragon start [normal|april_fools] [countdown-seconds]");
                                return true;
                            }
                        }
                    } else {
                        try { seconds = Integer.parseInt(args[1]); }
                        catch (NumberFormatException ignored) {
                            sender.sendMessage("Usage: /dragon start [normal|april_fools] [countdown-seconds]");
                            return true;
                        }
                    }
                }
                if (!manager.start(seconds, mode)) sender.sendMessage("The Dragon Event is already running or could not be prepared.");
            }
            case "stop" -> {
                if (admin(sender)) manager.stop();
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                if (manager.state() != EventState.IDLE) {
                    sender.sendMessage("Stop the active event before reloading.");
                    return true;
                }
                plugin.reloadPlugin();
                sender.sendMessage("TownyDragonEvent reloaded.");
            }
            case "setexit" -> {
                if (!admin(sender)) return true;
                if (!idle(sender)) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only a player can set the Dragon exit location.");
                    return true;
                }
                manager.setExitLocation(player);
            }
            case "setspawn" -> {
                if (!admin(sender)) return true;
                if (!idle(sender)) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only a player can set the Dragon arena spawn.");
                    return true;
                }
                manager.setArenaSpawn(player);
            }
            case "setportal" -> {
                if (!admin(sender)) return true;
                if (!idle(sender)) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only a player can set the Dragon portal center.");
                    return true;
                }
                manager.setPortalLocation(player);
            }
            case "season" -> {
                if (!admin(sender)) return true;
                if (args.length < 2 || !args[1].matches("[A-Za-z0-9_-]{1,32}")) {
                    sender.sendMessage("Usage: /dragon season <name>");
                    return true;
                }
                stats.setSeason(args[1]);
                messages.send(sender, "season-changed", Map.of("season", args[1]));
            }
            default -> sender.sendMessage("/dragon <join|leave|status|stats|start|stop|reload|setspawn|setportal|setexit|season>");
        }
        return true;
    }

    private void showStats(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length > 1) {
            if (!sender.hasPermission("townysmp.dragon.admin")) {
                sender.sendMessage("No permission to view other players.");
                return;
            }
            target = Bukkit.getOfflinePlayer(args[1]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage("Usage: /dragon stats <player>");
            return;
        }
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        String bestRank = stats.bestRank(target.getUniqueId()) == 0 ? "-" : Integer.toString(stats.bestRank(target.getUniqueId()));
        messages.send(sender, "stats-header", Map.of("player", name));
        messages.send(sender, "stats-lifetime", Map.of(
                "damage", format(stats.totalDamage(target.getUniqueId())),
                "participations", Integer.toString(stats.participations(target.getUniqueId())),
                "wins", Integer.toString(stats.wins(target.getUniqueId())),
                "deaths", Integer.toString(stats.deaths(target.getUniqueId()))));
        messages.send(sender, "stats-season", Map.of(
                "season", stats.season(),
                "season_damage", format(stats.seasonDamage(target.getUniqueId())),
                "season_participations", Integer.toString(stats.seasonParticipations(target.getUniqueId())),
                "season_wins", Integer.toString(stats.seasonWins(target.getUniqueId())),
                "season_deaths", Integer.toString(stats.seasonDeaths(target.getUniqueId()))));
        messages.send(sender, "stats-best", Map.of(
                "personal_best", format(stats.personalBest(target.getUniqueId())), "best_rank", bestRank));
    }

    private String format(double value) { return String.format(Locale.US, "%.2f", value); }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("townysmp.dragon.admin")) return true;
        sender.sendMessage("No permission.");
        return false;
    }

    private boolean idle(CommandSender sender) {
        if (manager.state() == EventState.IDLE) return true;
        sender.sendMessage("Stop the active Dragon Event before changing locations.");
        return false;
    }

    private void status(CommandSender sender) {
        messages.send(sender, "status", Map.of(
                "state", manager.state().name(),
                "players", Integer.toString(manager.playerCount()),
                "mode", manager.eventMode().commandName()));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("start")
                && sender.hasPermission("townysmp.dragon.admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("normal", "april_fools").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("start")
                && EventMode.parse(args[1]) != null && sender.hasPermission("townysmp.dragon.admin")) {
            return List.of("30", "300", "10800").stream().filter(value -> value.startsWith(args[2])).toList();
        }
        if (args.length != 1) return List.of();
        List<String> choices = new ArrayList<>(List.of("join", "leave", "status", "stats"));
        if (sender.hasPermission("townysmp.dragon.admin")) {
            choices.addAll(List.of("start", "stop", "reload", "setspawn", "setportal", "setexit", "season"));
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
