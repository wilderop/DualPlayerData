package me.benjamin.dualplayerdata;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DualPlayerData extends JavaPlugin {

    private PlayerDataManager dataManager;
    private AuthManager authManager;
    private AuthListener authListener;
    private CommandHandler commandHandler;

    // Tracks currently connected usernames (lowercase) to prevent duplicate sessions
    // across online-UUID and offline-UUID connections
    private final Set<String> onlineUsernames = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        dataManager = new PlayerDataManager(this);
        authManager = new AuthManager(this);
        authListener = new AuthListener(this, authManager, dataManager);
        commandHandler = new CommandHandler(this, authManager, dataManager);

        getServer().getPluginManager().registerEvents(authListener, this);

        getCommand("login").setExecutor(commandHandler);
        getCommand("register").setExecutor(commandHandler);
        getCommand("changedatapass").setExecutor(commandHandler);
        getCommand("datasync").setExecutor(commandHandler);
        getCommand("datasyncadmin").setExecutor(commandHandler);

        // Check auth timeouts every 30 seconds
        Bukkit.getScheduler().runTaskTimer(this, authListener::checkAuthTimeouts, 600L, 600L);

        getLogger().info("DualPlayerData v1.2.0 enabled – dual UUID sync + offline password protection + duplicate-session lock");
    }

    @Override
    public void onDisable() {
        if (authManager != null) {
            authManager.saveData();
        }
        if (dataManager != null) {
            dataManager.saveMappings();
        }
        onlineUsernames.clear();
        getLogger().info("DualPlayerData disabled.");
    }

    public boolean isUsernameOnline(String username) {
        return onlineUsernames.contains(username.toLowerCase());
    }

    public void addOnlineUsername(String username) {
        onlineUsernames.add(username.toLowerCase());
    }

    public void removeOnlineUsername(String username) {
        onlineUsernames.remove(username.toLowerCase());
    }

    public PlayerDataManager getDataManager() {
        return dataManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }
}
