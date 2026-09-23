package me.benjamin.dualplayerdata;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;

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
        if (mappingsFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mappingsFile);
            for (String name : cfg.getKeys(false)) {
                try {
                    usernameToOnlineUUID.put(name.toLowerCase(), UUID.fromString(cfg.getString(name)));
                } catch (Exception ignored) {}
            }
        }
    }

    public void saveMappings() {   // ← NOW PUBLIC
        YamlConfiguration cfg = new YamlConfiguration();
        usernameToOnlineUUID.forEach((name, uuid) -> cfg.set(name, uuid.toString()));
        try {
            cfg.save(mappingsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save mappings.yml");
        }
    }

    public void recordOnlineUUID(String username, UUID uuid) {
        String lower = username.toLowerCase();
        if (!usernameToOnlineUUID.containsKey(lower)) {
            usernameToOnlineUUID.put(lower, uuid);
            saveMappings();
        }
    }

    public boolean isKnownOnlineAccount(String username) {
        String lower = username.toLowerCase();
        if (usernameToOnlineUUID.containsKey(lower)) return true;

        UUID offlineUuid = getOfflineUUID(username);
        File datFile = new File(plugin.getServer().getWorlds().get(0).getWorldFolder(), "playerdata/" + offlineUuid + ".dat");
        if (!datFile.exists()) return false;

        if (offlineUuid.version() != 4) return false;

        UUID mojangUuid = fetchMojangUUIDFromFile(offlineUuid);
        if (mojangUuid != null) {
            recordOnlineUUID(username, mojangUuid);
            return true;
        }
        return false;
    }

    private UUID fetchMojangUUIDFromFile(UUID uuid) {
        try (var client = HttpClient.newHttpClient()) {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "")))
                    .GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String json = resp.body();
                int start = json.indexOf("\"name\":\"") + 8;
                int end = json.indexOf("\"", start);
                if (start > 7 && end > start) {
                    String name = json.substring(start, end);
                    recordOnlineUUID(name, uuid);
                    return uuid;
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().info("Mojang API lookup failed for UUID " + uuid);
        }
        return null;
    }

    public void importLegacyPlayers() {
        plugin.getLogger().info("Starting legacy player import from playerdata folder...");
        File pd = new File(plugin.getServer().getWorlds().get(0).getWorldFolder(), "playerdata");
        File[] files = pd.listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null) return;

        int imported = 0;
        for (File f : files) {
            try {
                String filename = f.getName();
                UUID uuid = UUID.fromString(filename.substring(0, filename.length() - 4));
                if (uuid.version() != 4) continue;
                if (fetchMojangUUIDFromFile(uuid) != null) imported++;
                TimeUnit.MILLISECONDS.sleep(110);
            } catch (Exception ignored) {}
        }
        plugin.getLogger().info("Legacy import complete! Imported " + imported + " online accounts.");
    }

    public UUID getOfflineUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isOnlineAuthenticated(Player p) {
        UUID offlineUuid = getOfflineUUID(p.getName());
        return !p.getUniqueId().equals(offlineUuid);
    }

    public void syncOnQuit(Player p) {
        String lower = p.getName().toLowerCase();
        UUID onlineUUID = usernameToOnlineUUID.get(lower);
        UUID offlineUUID = getOfflineUUID(p.getName());
        UUID current = p.getUniqueId();

        if (onlineUUID == null) return;

        UUID other = current.equals(onlineUUID) ? offlineUUID : onlineUUID;
        if (!current.equals(other)) {
            copyPlayerFiles(current, other, p.getWorld());
            plugin.getLogger().info("Auto-synced dual files for " + p.getName());
        }
    }

    private void copyPlayerFiles(UUID from, UUID to, World world) {
        File base = world.getWorldFolder();
        copyWithBackup(new File(base, "playerdata/" + from + ".dat"), new File(base, "playerdata/" + to + ".dat"));
        copyWithBackup(new File(base, "advancements/" + from + ".json"), new File(base, "advancements/" + to + ".json"));
        copyWithBackup(new File(base, "stats/" + from + ".json"), new File(base, "stats/" + to + ".json"));
    }

    private void copyWithBackup(File src, File dest) {
        if (!src.exists()) return;
        if (dest.exists()) {
            File bak = new File(dest.getParentFile(), dest.getName() + ".bak");
            try { Files.copy(dest.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
        }
        try { Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) {
            plugin.getLogger().warning("Copy failed: " + src.getName());
        }
    }
}
