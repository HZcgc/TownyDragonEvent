package net.townysmp.dragonevent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;

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
        InputStream defaultsStream = plugin.getResource("messages.yml");
        if (defaultsStream != null) {
            file.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(
                    defaultsStream, StandardCharsets.UTF_8)));
            file.options().copyDefaults(true);
            try {
                file.save(target);
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not write missing defaults to messages.yml: " + exception.getMessage());
            }
        }
    }

    Component get(String key) {
        return get(key, Map.of());
    }

    Component get(String key, Map<String, String> replacements) {
        String prefix = file.getString("prefix", "");
        return mini.deserialize(prefix).append(getRaw(key, replacements));
    }

    Component getRaw(String key, Map<String, String> replacements) {
        String value = file.getString(key, "<red>Missing message: " + key + "</red>");
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return mini.deserialize(value);
    }

    void send(CommandSender sender, String key) {
        sender.sendMessage(get(key));
    }

    void send(CommandSender sender, String key, Map<String, String> replacements) {
        sender.sendMessage(get(key, replacements));
    }

    void broadcast(String messageKey, Map<String, String> replacements, String notificationKey) {
        Component chat = get(messageKey, replacements);
        String root = "notifications." + notificationKey;
        boolean titleEnabled = plugin.getConfig().getBoolean(root + ".title", false);
        String sound = plugin.getConfig().getString(root + ".sound", "");
        float volume = (float) plugin.getConfig().getDouble(root + ".volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble(root + ".pitch", 1.0);
        int fadeIn = plugin.getConfig().getInt(root + ".fade-in-ticks", 10);
        int stay = plugin.getConfig().getInt(root + ".stay-ticks", 50);
        int fadeOut = plugin.getConfig().getInt(root + ".fade-out-ticks", 10);
        Title title = Title.title(
                getRaw(notificationKey + "-title", replacements),
                getRaw(notificationKey + "-subtitle", replacements),
                Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(chat);
            if (titleEnabled) player.showTitle(title);
            List<String> sounds = plugin.getConfig().getStringList(root + ".sounds");
            if (sounds.isEmpty() && sound != null && !sound.isBlank()) {
                player.playSound(player.getLocation(), sound, volume, pitch);
            }
            for (String entry : sounds) {
                String[] parts = entry.split(";");
                if (parts.length == 0 || parts[0].isBlank()) continue;
                float entryVolume = parts.length > 1 ? parseFloat(parts[1], volume) : volume;
                float entryPitch = parts.length > 2 ? parseFloat(parts[2], pitch) : pitch;
                player.playSound(player.getLocation(), parts[0], entryVolume, entryPitch);
            }
        }
        Bukkit.getConsoleSender().sendMessage(chat);
    }

    private float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
