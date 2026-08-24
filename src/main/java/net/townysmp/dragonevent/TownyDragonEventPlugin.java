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
        messages.reload();
    }
}
