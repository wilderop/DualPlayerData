package me.benjamin.dualplayerdata;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

import java.util.UUID;

public class AuthListener implements Listener {

    private final DualPlayerData plugin;
    private final AuthManager authManager;
    private final PlayerDataManager dataManager;

    public AuthListener(DualPlayerData plugin, AuthManager authManager, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.dataManager = dataManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent e) {
        Player p = e.getPlayer();
        String nameLower = p.getName().toLowerCase();

        // === Strict mutual exclusion: only one session per username ===
        if (plugin.isUsernameOnline(nameLower)) {
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "You are already logged in on another connection/proxy.\nPlease log out first.");
            return;
        }

        String ip = e.getAddress() != null ? e.getAddress().getHostAddress() : "unknown";

        if (dataManager.isOnlineAuthenticated(p)) {
            // Real Mojang UUID (online-mode proxy)
            dataManager.recordOnlineUUID(nameLower, p.getUniqueId());
            authManager.markAuthenticated(p.getUniqueId());
            authManager.updateLastIp(nameLower, ip);

            if (!authManager.isRegistered(nameLower)) {
                String generated = authManager.generateAndSetInitialPassword(nameLower);
                if (generated != null) {
                    // Message is sent later in onJoin so the player actually receives it
                    p.sendMessage(ChatColor.AQUA + "§lYour offline protection password is: §e" + generated);
                    p.sendMessage(ChatColor.YELLOW + "Save it! Use /changedatapass to change it later.");
                }
            }
        } else {
            // Offline UUID (offline-mode proxy or direct connect)
            if (dataManager.isKnownOnlineAccount(nameLower)) {
                // This username belongs to a real online account → protect it
                if (!authManager.isRegistered(nameLower)) {
                    String generated = authManager.generateAndSetInitialPassword(nameLower);
                    if (generated != null) {
                        plugin.getLogger().warning("[DUALDATA] Generated initial password for protected account '"
                                + nameLower + "': " + generated + " (tell the real owner)");
                        e.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                                ChatColor.RED + "This account is protected.\n"
                                        + "It has already been claimed in online mode.\n"
                                        + "Contact an administrator for your password.");
                        return;
                    }
                }
                // Password exists → require /login
                authManager.startAuthSession(p.getUniqueId(), "login");
            } else {
                // Brand-new username → normal register / login flow
                String action = authManager.isRegistered(nameLower) ? "login" : "register";
                authManager.startAuthSession(p.getUniqueId(), action);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String nameLower = p.getName().toLowerCase();

        // Mark username as currently online (prevents second connection)
        plugin.addOnlineUsername(nameLower);

        if (dataManager.isOnlineAuthenticated(p)) {
            p.sendMessage(ChatColor.GREEN + "Authenticated via Velocity (online mode)");
            return;
        }

        if (authManager.isAuthenticated(p.getUniqueId())) {
            p.sendMessage(ChatColor.GREEN + "Auto-logged in via IP session!");
            return;
        }

        String pending = authManager.getPendingAction(p.getUniqueId());
        if ("login".equals(pending)) {
            p.sendMessage(ChatColor.YELLOW + "§lPlease /login <password>");
        } else if ("register".equals(pending)) {
            p.sendMessage(ChatColor.RED + "§lPlease /register <password> <confirm>");
        }

        // Freeze them at spawn until they authenticate
        p.teleport(p.getWorld().getSpawnLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.removeOnlineUsername(p.getName());
        authManager.clearSession(p.getUniqueId());
        dataManager.syncOnQuit(p);
    }

    private boolean needsAuth(Player p) {
        return !authManager.isAuthenticated(p.getUniqueId());
    }

    // ========== Freeze events while not authenticated ==========

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (needsAuth(e.getPlayer())) {
            e.setTo(e.getFrom());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (needsAuth(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You must /login or /register first!");
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (needsAuth(p)) {
            String msg = e.getMessage().toLowerCase();
            if (!msg.startsWith("/login") && !msg.startsWith("/register")) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You must authenticate first!");
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (needsAuth(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (needsAuth(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (needsAuth(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventory(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && needsAuth(p)) {
            e.setCancelled(true);
        }
    }

    public void checkAuthTimeouts() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uid = p.getUniqueId();
            if (!authManager.isAuthenticated(uid) && authManager.getPendingAction(uid) != null) {
                long start = authManager.getAuthStartTime(uid);
                if (start == 0) continue;
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > 120_000) {
                    p.kickPlayer(ChatColor.RED + "Timed out! You must login/register within 120 seconds.");
                    authManager.clearSession(uid);
                    plugin.removeOnlineUsername(p.getName());
                } else if (elapsed % 30_000 < 1_000) {
                    int remaining = (int) ((120_000 - elapsed) / 1000);
                    p.sendMessage(ChatColor.RED + "§lYou have " + remaining + " seconds left to authenticate!");
                }
            }
        }
    }
}
