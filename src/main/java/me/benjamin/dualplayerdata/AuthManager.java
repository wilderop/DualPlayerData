package me.benjamin.dualplayerdata;

import org.bukkit.configuration.file.YamlConfiguration;
import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final DualPlayerData plugin;
    private final File playersFile;
    private YamlConfiguration config;

    private final ConcurrentHashMap<UUID, Boolean> authenticated = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> pendingAction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> authStartTimes = new ConcurrentHashMap<>();

    private static final String PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+";

    public AuthManager(DualPlayerData plugin) {
        this.plugin = plugin;
        this.playersFile = new File(plugin.getDataFolder(), "players.yml");
        loadData();
    }

    private void loadData() {
        if (!playersFile.exists()) {
            try {
                playersFile.createNewFile();
            } catch (IOException ignored) {
            }
        }
        config = YamlConfiguration.loadConfiguration(playersFile);
    }

    public void saveData() {
        try {
            config.save(playersFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save players.yml: " + e.getMessage());
        }
    }

    public boolean isRegistered(String username) {
        return config.contains("players." + username.toLowerCase() + ".hash");
    }

    public boolean checkPassword(String username, String password) {
        String hash = config.getString("players." + username.toLowerCase() + ".hash");
        return hash != null && BCrypt.checkpw(password, hash);
    }

    public void register(String username, String password) {
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        String key = "players." + username.toLowerCase();
        config.set(key + ".hash", hash);
        config.set(key + ".lastOnlineIp", "");
        saveData();
    }

    /**
     * Generates a random 12-character password, stores the BCrypt hash, and returns the plaintext.
     * Returns null if the player is already registered.
     */
    public String generateAndSetInitialPassword(String username) {
        if (isRegistered(username)) {
            return null;
        }

        SecureRandom random = new SecureRandom();
        StringBuilder pass = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            pass.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        String password = pass.toString();

        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        String key = "players." + username.toLowerCase();
        config.set(key + ".hash", hash);
        config.set(key + ".lastOnlineIp", "");
        saveData();

        return password;
    }

    public void changePassword(String username, String newPassword) {
        String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        config.set("players." + username.toLowerCase() + ".hash", hash);
        saveData();
    }

    public void resetPassword(String username) {
        config.set("players." + username.toLowerCase() + ".hash", null);
        saveData();
    }

    public void updateLastIp(String username, String ip) {
        config.set("players." + username.toLowerCase() + ".lastOnlineIp", ip);
        saveData();
    }

    public boolean isIpSessionValid(String username, String currentIp) {
        String lastIp = config.getString("players." + username.toLowerCase() + ".lastOnlineIp");
        return lastIp != null && lastIp.equals(currentIp);
    }

    public void startAuthSession(UUID uuid, String action) {
        pendingAction.put(uuid, action);
        authenticated.remove(uuid);
        recordAuthStart(uuid);
    }

    public void markAuthenticated(UUID uuid) {
        authenticated.put(uuid, true);
        pendingAction.remove(uuid);
        authStartTimes.remove(uuid);
    }

    public void clearSession(UUID uuid) {
        authenticated.remove(uuid);
        pendingAction.remove(uuid);
        authStartTimes.remove(uuid);
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.getOrDefault(uuid, false);
    }

    public String getPendingAction(UUID uuid) {
        return pendingAction.get(uuid);
    }

    public void recordAuthStart(UUID uuid) {
        authStartTimes.put(uuid, System.currentTimeMillis());
    }

    public long getAuthStartTime(UUID uuid) {
        return authStartTimes.getOrDefault(uuid, 0L);
    }
}
