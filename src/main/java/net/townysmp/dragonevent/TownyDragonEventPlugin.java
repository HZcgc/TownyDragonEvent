package net.townysmp.dragonevent;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class TownyDragonEventPlugin extends JavaPlugin {
    private Messages messages;
    private DragonEventManager eventManager;
    private StatsManager statsManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages = new Messages(this);
        statsManager = new StatsManager(this);
        eventManager = new DragonEventManager(this, messages, statsManager);
        DragonCommand command = new DragonCommand(this, eventManager, messages, statsManager);
        PluginCommand dragon = Objects.requireNonNull(getCommand("dragon"));
        dragon.setExecutor(command);
        dragon.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(eventManager, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new DragonPlaceholderExpansion(this, eventManager, statsManager).register();
            getLogger().info("PlaceholderAPI integration enabled.");
        }
        eventManager.recoverAfterRestart();
        getLogger().info("TownyDragonEvent is ready. Event world: " + eventManager.runtimeWorldName());
    }

    @Override
    public void onDisable() {
        if (eventManager != null) eventManager.shutdown();
        if (statsManager != null) statsManager.save();
    }

    void reloadPlugin() {
        reloadConfig();
        migrateConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messages.reload();
    }

    private void migrateConfig() {
        int version = getConfig().contains("config-version", true)
                ? getConfig().getInt("config-version", 0) : 0;
        if (version >= 2) return;

        // Only replace the exact Build 26 defaults. Deliberately customised
        // installations keep their configured values.
        if (approximately(getConfig().getDouble("event.dragon-health", 1000.0), 1000.0)) {
            getConfig().set("event.dragon-health", 500.0);
        }
        if (approximately(getConfig().getDouble(
                "event.difficulty-scaling.health-per-additional-fighter", 125.0), 125.0)) {
            getConfig().set("event.difficulty-scaling.health-per-additional-fighter", 250.0);
        }
        if (approximately(getConfig().getDouble(
                "event.difficulty-scaling.maximum-effective-health", 5000.0), 5000.0)) {
            getConfig().set("event.difficulty-scaling.maximum-effective-health", 6000.0);
        }
        getConfig().set("config-version", 2);
        getLogger().info("Updated Dragon health balance to config version 2.");
    }

    private boolean approximately(double value, double expected) {
        return Math.abs(value - expected) < 0.000001;
    }
}
