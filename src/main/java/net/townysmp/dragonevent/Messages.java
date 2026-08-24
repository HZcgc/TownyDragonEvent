package net.townysmp.dragonevent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;

final class Messages {
    private final TownyDragonEventPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private YamlConfiguration file;

    Messages(TownyDragonEventPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        File target = new File(plugin.getDataFolder(), "messages.yml");
        if (!target.exists()) plugin.saveResource("messages.yml", false);
        file = YamlConfiguration.loadConfiguration(target);
    }

    Component get(String key) {
        return get(key, Map.of());
    }

    Component get(String key, Map<String, String> replacements) {
        String prefix = file.getString("prefix", "");
        String value = file.getString(key, "<red>Missing message: " + key + "</red>");
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return mini.deserialize(prefix + value);
    }

    void send(CommandSender sender, String key) {
        sender.sendMessage(get(key));
    }

    void send(CommandSender sender, String key, Map<String, String> replacements) {
        sender.sendMessage(get(key, replacements));
    }
}
