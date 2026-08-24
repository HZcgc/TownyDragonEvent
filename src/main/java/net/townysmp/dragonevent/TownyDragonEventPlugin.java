package net.townysmp.dragonevent;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class TownyDragonEventPlugin extends JavaPlugin {
    private Messages messages;
    private DragonEventManager eventManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        eventManager = new DragonEventManager(this, messages);
        DragonCommand command = new DragonCommand(this, eventManager, messages);
        PluginCommand dragon = Objects.requireNonNull(getCommand("dragon"));
        dragon.setExecutor(command);
        dragon.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(eventManager, this);
        eventManager.recoverAfterRestart();
        getLogger().info("TownyDragonEvent is ready. Event world: " + eventManager.runtimeWorldName());
    }

    @Override
    public void onDisable() {
        if (eventManager != null) eventManager.shutdown();
    }

    void reloadPlugin() {
        reloadConfig();
        messages.reload();
    }
}
