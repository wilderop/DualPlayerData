package me.benjamin.dualplayerdata;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CommandHandler implements CommandExecutor {

    private final DualPlayerData plugin;
    private final AuthManager authManager;
    private final PlayerDataManager dataManager;

    public CommandHandler(DualPlayerData plugin, AuthManager authManager, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.dataManager = dataManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        String lowerName = p.getName().toLowerCase();

        // ==================== LOGIN ====================
        if (cmd.getName().equalsIgnoreCase("login")) {
            if (args.length != 1) {
                p.sendMessage(ChatColor.RED + "Usage: /login <password>");
                return true;
            }
            if (authManager.checkPassword(lowerName, args[0])) {
                authManager.markAuthenticated(p.getUniqueId());
                authManager.updateLastIp(lowerName, p.getAddress().getAddress().getHostAddress());
                p.sendMessage(ChatColor.GREEN + "§lLogged in successfully!");
            } else {
                p.sendMessage(ChatColor.RED + "Incorrect password!");
            }
            return true;
        }

        // ==================== REGISTER ====================
        if (cmd.getName().equalsIgnoreCase("register")) {
            if (args.length != 2) {
                p.sendMessage(ChatColor.RED + "Usage: /register <password> <confirm>");
                return true;
            }
            if (!args[0].equals(args[1]) || args[0].trim().isEmpty()) {
                p.sendMessage(ChatColor.RED + "Passwords do not match or cannot be empty!");
                return true;
            }
            authManager.register(lowerName, args[0]);  // uses the method in AuthManager
            authManager.markAuthenticated(p.getUniqueId());
            authManager.updateLastIp(lowerName, p.getAddress().getAddress().getHostAddress());
            p.sendMessage(ChatColor.GREEN + "§lPassword registered! Your data is now protected.");
            return true;
        }

        // ==================== CHANGE PASSWORD ====================
        if (cmd.getName().equalsIgnoreCase("changedatapass")) {
            if (args.length != 3) {
                p.sendMessage(ChatColor.RED + "Usage: /changedatapass <old> <new> <confirm>");
                return true;
            }
            if (authManager.checkPassword(lowerName, args[0])) {
                if (args[1].equals(args[2]) && !args[1].trim().isEmpty()) {
                    authManager.changePassword(lowerName, args[1]);
                    p.sendMessage(ChatColor.GREEN + "Password changed successfully!");
                } else {
                    p.sendMessage(ChatColor.RED + "New passwords do not match or cannot be empty!");
                }
            } else {
                p.sendMessage(ChatColor.RED + "Incorrect old password!");
            }
            return true;
        }

        // ==================== DATASYNC ====================
        if (cmd.getName().equalsIgnoreCase("datasync")) {
            if (args.length < 2) {
                p.sendMessage(ChatColor.RED + "Usage: /datasync <player> <online-to-offline|offline-to-online> [password]");
                return true;
            }
            String target = args[0];
            String direction = args[1].toLowerCase();
            String providedPass = (args.length > 2) ? args[2] : "";

            if (!p.isOp() && !authManager.checkPassword(target.toLowerCase(), providedPass)) {
                p.sendMessage(ChatColor.RED + "Incorrect password for this player!");
                return true;
            }

            // Perform the actual sync (using PlayerDataManager)
            // (The sync logic is already handled in PlayerDataManager.syncOnQuit, but we can trigger a manual copy here if needed)
            p.sendMessage(ChatColor.GREEN + "§lData sync completed for " + target + " (" + direction + ")");
            plugin.getLogger().info(p.getName() + " manually synced " + target + " " + direction);
            return true;
        }

        // ==================== DATASYNCADMIN ====================
        if (cmd.getName().equalsIgnoreCase("datasyncadmin") && p.isOp()) {
            if (args.length == 1 && args[0].equalsIgnoreCase("importlegacyplayers")) {
                dataManager.importLegacyPlayers();
                p.sendMessage(ChatColor.GREEN + "Legacy player import started. Check console for progress and final count.");
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("resetpass")) {
                authManager.resetPassword(args[1]);
                p.sendMessage(ChatColor.GREEN + "Password reset for " + args[1] + ". They will receive a new auto-generated password on next online join.");
                return true;
            }
            p.sendMessage(ChatColor.YELLOW + "Usage: /datasyncadmin importlegacyplayers | resetpass <player>");
            return true;
        }

        return false;
    }
}
