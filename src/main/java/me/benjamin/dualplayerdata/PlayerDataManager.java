package me.benjamin.dualplayerdata;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PlayerDataManager {

    private final DualPlayerData plugin;
    private final Map<String, UUID> usernameToOnlineUUID = new HashMap<>();
    private final File mappingsFile;

    public PlayerDataManager(DualPlayerData plugin) {
        this.plugin = plugin;
        this.mappingsFile = new File(plugin.getDataFolder(), "mappings.yml");
        loadMappings();
    }

    private void loadMappings() {
        if (!mappingsFile.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mappingsFile);
        for (String name : cfg.getKeys(false)) {
            try {
                String uuidStr = cfg.getString(name);
                if (uuidStr != null) {
                    usernameToOnlineUUID.put(name.toLowerCase(), UUID.fromString(uuidStr));
                }
            } catch (Exception ignored) {
            }
        }
        plugin.getLogger().info("Loaded " + usernameToOnlineUUID.size() + " known online accounts from mappings.yml");
    }

    public void saveMappings() {
        YamlConfiguration cfg = new YamlConfiguration();
        usernameToOnlineUUID.forEach((name, uuid) -> cfg.set(name, uuid.toString()));
        try {
            cfg.save(mappingsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save mappings.yml: " + e.getMessage());
        }
    }

    public void recordOnlineUUID(String username, UUID uuid) {
        String lower = username.toLowerCase();
        if (!usernameToOnlineUUID.containsKey(lower) || !usernameToOnlineUUID.get(lower).equals(uuid)) {
            usernameToOnlineUUID.put(lower, uuid);
            saveMappings();
        }
    }

    /**
     * Returns true if this username has ever been seen with a real Mojang (online) UUID.
     * Populated by online joins and by /datasyncadmin importlegacyplayers.
     */
    public boolean isKnownOnlineAccount(String username) {
        return usernameToOnlineUUID.containsKey(username.toLowerCase());
    }

    public UUID getOnlineUUID(String username) {
        return usernameToOnlineUUID.get(username.toLowerCase());
    }

    /**
     * Scans the playerdata folder for all version-4 (Mojang) UUID files,
     * looks up the current username via the Mojang API, and adds them to mappings.yml.
     * Rate-limited to stay under Mojang limits. Safe to run multiple times.
     */
    public void importLegacyPlayers() {
        plugin.getLogger().info("Starting legacy player import from playerdata folder...");
        File pdFolder = new File(plugin.getServer().getWorlds().get(0).getWorldFolder(), "playerdata");
        if (!pdFolder.isDirectory()) {
            plugin.getLogger().warning("playerdata folder not found!");
            return;
        }

        File[] files = pdFolder.listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null || files.length == 0) {
            plugin.getLogger().info("No playerdata files found.");
            return;
        }

        int imported = 0;
        int skipped = 0;
        int failed = 0;

        for (File f : files) {
            try {
                String filename = f.getName();
                UUID uuid = UUID.fromString(filename.substring(0, filename.length() - 4));

                // Only real Mojang UUIDs (version 4). Offline UUIDs are version 3.
                if (uuid.version() != 4) {
                    skipped++;
                    continue;
                }

                // Skip if we already have this UUID mapped
                boolean alreadyMapped = usernameToOnlineUUID.containsValue(uuid);
                if (alreadyMapped) {
                    skipped++;
                    continue;
                }

                String name = lookupUsernameFromMojang(uuid);
                if (name != null) {
                    recordOnlineUUID(name, uuid);
                    imported++;
                    plugin.getLogger().info("Imported: " + name + " → " + uuid);
                } else {
                    failed++;
                }

                // Rate limit (~8–9 requests per second is still safe under 600/min)
                TimeUnit.MILLISECONDS.sleep(120);
            } catch (Exception e) {
                failed++;
            }
        }

        saveMappings();
        plugin.getLogger().info("Legacy import complete. Imported: " + imported + ", Skipped: " + skipped + ", Failed: " + failed);
    }

    private String lookupUsernameFromMojang(UUID uuid) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String json = resp.body();
                // Simple extraction: "name":"PlayerName"
                int start = json.indexOf("\"name\"") + 8;
                if (start < 8) return null;
                // Skip whitespace and colon/quote
                while (start < json.length() && (json.charAt(start) == ':' || json.charAt(start) == '"' || Character.isWhitespace(json.charAt(start)))) {
                    start++;
                }
                int end = json.indexOf('"', start);
                if (end > start) {
                    return json.substring(start, end);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Mojang lookup failed for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    public UUID getOfflineUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * True when the player is connecting with a real Mojang UUID (i.e. through an online-mode proxy).
     */
    public boolean isOnlineAuthenticated(Player p) {
        UUID offlineUuid = getOfflineUUID(p.getName());
        return !p.getUniqueId().equals(offlineUuid);
    }

    public void syncOnQuit(Player p) {
        String lower = p.getName().toLowerCase();
        UUID onlineUUID = usernameToOnlineUUID.get(lower);
        if (onlineUUID == null) {
            return; // No known online counterpart
        }

        UUID offlineUUID = getOfflineUUID(p.getName());
        UUID current = p.getUniqueId();
        UUID other = current.equals(onlineUUID) ? offlineUUID : onlineUUID;

        if (!current.equals(other)) {
            copyPlayerFiles(current, other, p.getWorld());
            plugin.getLogger().info("Auto-synced dual files for " + p.getName() + " (" + current + " → " + other + ")");
        }
    }

    private void copyPlayerFiles(UUID from, UUID to, World world) {
        File base = world.getWorldFolder();
        copyWithBackup(new File(base, "playerdata/" + from + ".dat"), new File(base, "playerdata/" + to + ".dat"));
        copyWithBackup(new File(base, "advancements/" + from + ".json"), new File(base, "advancements/" + to + ".json"));
        copyWithBackup(new File(base, "stats/" + from + ".json"), new File(base, "stats/" + to + ".json"));
    }

    private void copyWithBackup(File src, File dest) {
        if (!src.exists()) {
            return;
        }
        try {
            if (dest.exists()) {
                File bak = new File(dest.getParentFile(), dest.getName() + ".bak");
                Files.copy(dest.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to copy " + src.getName() + ": " + e.getMessage());
        }
    }
}
