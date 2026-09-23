package me.benjamin.dualplayerdata;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class DualPlayerData extends JavaPlugin {

    private PlayerDataManager dataManager;
    private AuthManager authManager;
    private AuthListener authListener;
    private CommandHandler commandHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        // Initialize managers
        dataManager = new PlayerDataManager(this);
        authManager = new AuthManager(this);
        authListener = new AuthListener(this, authManager, dataManager);
        commandHandler = new CommandHandler(this, authManager, dataManager);

        // Register events
        getServer().getPluginManager().registerEvents(authListener, this);

        // Register commands
        getCommand("login").setExecutor(commandHandler);
        getCommand("register").setExecutor(commandHandler);
        getCommand("changedatapass").setExecutor(commandHandler);
        getCommand("datasync").setExecutor(commandHandler);
        getCommand("datasyncadmin").setExecutor(commandHandler);

        // Start timeout checker (every 30 seconds)
        Bukkit.getScheduler().runTaskTimer(this, authListener::checkAuthTimeouts, 600L, 600L);

        getLogger().info("DualPlayerData v1.2 loaded - hybrid mappings + legacy import ready!");
    }

    @Override
    public void onDisable() {
        if (authManager != null) authManager.saveData();
        if (dataManager != null) dataManager.saveMappings();
        getLogger().info("DualPlayerData disabled.");
    }

    public PlayerDataManager getDataManager() {
        return dataManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }
}
