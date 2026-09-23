package me.benjamin.dualplayerdata;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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
        String ip = e.getAddress().getHostAddress();

        if (dataManager.isOnlineAuthenticated(p)) {
            // Real Mojang auth via Velocity
            dataManager.recordOnlineUUID(nameLower, p.getUniqueId());
            authManager.markAuthenticated(p.getUniqueId());
            authManager.updateLastIp(nameLower, ip);

            if (!authManager.isRegistered(nameLower)) {
                String generated = authManager.generateAndSetInitialPassword(nameLower);
                if (generated != null) {
                    p.sendMessage(ChatColor.AQUA + "§lYour offline protection password is: §e" + generated);
                    p.sendMessage(ChatColor.YELLOW + "Save it! Use /changedatapass to change later.");
                }
            }
        } else {
            // OFFLINE MODE (Velocity offline or direct connect)
            if (dataManager.isKnownOnlineAccount(nameLower)) {
                // Known online account — protect it
                if (!authManager.isRegistered(nameLower)) {
                    String generated = authManager.generateAndSetInitialPassword(nameLower);
                    if (generated != null) {
                        plugin.getLogger().warning("[DUALDATA] Generated initial password for protected account '" + nameLower + "': " + generated);
                        p.kickPlayer(ChatColor.RED + "This account is protected.\nIt has already been claimed in online mode.\nContact an administrator for your password.");
                        return;
                    }
                }
                authManager.startAuthSession(p.getUniqueId(), "login");
            } else {
                // Brand-new username — normal flow
                String action = authManager.isRegistered(nameLower) ? "login" : "register";
                authManager.startAuthSession(p.getUniqueId(), action);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        if (dataManager.isOnlineAuthenticated(p)) {
            p.sendMessage(ChatColor.GREEN + "Authenticated via Velocity proxy");
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
        p.teleport(p.getWorld().getSpawnLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        authManager.clearSession(p.getUniqueId());
        dataManager.syncOnQuit(p);
    }

    private boolean needsAuth(Player p) {
        return !authManager.isAuthenticated(p.getUniqueId());
    }

    // === FREEZE EVENTS (offline mode only) ===
    @EventHandler public void onMove(PlayerMoveEvent e) { if (needsAuth(e.getPlayer())) e.setTo(e.getFrom()); }
    @EventHandler public void onChat(AsyncPlayerChatEvent e) { if (needsAuth(e.getPlayer())) { e.setCancelled(true); e.getPlayer().sendMessage(ChatColor.RED + "You must /login or /register first!"); } }
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (needsAuth(p)) {
            String msg = e.getMessage().toLowerCase();
            if (!msg.startsWith("/login") && !msg.startsWith("/register")) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You must authenticate first!");
            }
        }
    }
    @EventHandler public void onBlockBreak(BlockBreakEvent e) { if (needsAuth(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onBlockPlace(BlockPlaceEvent e) { if (needsAuth(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (needsAuth(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onInventory(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && needsAuth(p)) e.setCancelled(true);
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
                } else if (elapsed % 30_000 < 1_000) {
                    int remaining = (int) ((120_000 - elapsed) / 1000);
                    p.sendMessage(ChatColor.RED + "§lYou have " + remaining + " seconds left to authenticate!");
                }
            }
        }
    }
}
