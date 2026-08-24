package net.townysmp.dragonevent;

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

    DragonCommand(TownyDragonEventPlugin plugin, DragonEventManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
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
            case "start" -> {
                if (!admin(sender)) return true;
                Integer seconds = null;
                if (args.length > 1) {
                    try { seconds = Integer.parseInt(args[1]); }
                    catch (NumberFormatException ignored) { sender.sendMessage("Usage: /dragon start [join-seconds]"); return true; }
                }
                if (!manager.start(seconds)) sender.sendMessage("The Dragon Event is already running.");
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
            default -> sender.sendMessage("/dragon <join|leave|status|start|stop|reload>");
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("townysmp.dragon.admin")) return true;
        sender.sendMessage("No permission.");
        return false;
    }

    private void status(CommandSender sender) {
        messages.send(sender, "status", Map.of(
                "state", manager.state().name(),
                "players", Integer.toString(manager.playerCount())));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        List<String> choices = new ArrayList<>(List.of("join", "leave", "status"));
        if (sender.hasPermission("townysmp.dragon.admin")) choices.addAll(List.of("start", "stop", "reload"));
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
