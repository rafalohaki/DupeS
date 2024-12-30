package com.rafalohaki.dupe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class DupeS extends JavaPlugin implements Listener {

    private double dupeChance; // Now directly represents a percentage (0-100)
    private boolean enableMessages;
    private boolean requirePermission;
    private boolean logSuccessfulDuplications;
    private PermissionManager permissionManager;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        LuckPerms luckPerms = null;
        if (provider != null) {
            luckPerms = provider.getProvider();
        } else {
            getLogger().severe("LuckPerms API not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        permissionManager = new PermissionManager(this, luckPerms);
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        log("DupeS enabled.");
    }

    @Override
    public void onDisable() {
        log("DupeS disabled. All resources cleaned up.");
        getServer().getScheduler().cancelTasks(this);
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        dupeChance = config.getDouble("dupe-chance", 1.0); // Read directly as a percentage
        enableMessages = config.getBoolean("enable-messages", true);
        requirePermission = config.getBoolean("require-permission", true);
        logSuccessfulDuplications = config.getBoolean("log-successful-duplications", true);
        validateConfigValues();
    }

    private void validateConfigValues() {
        if (dupeChance < 0.0 || dupeChance > 100.0) {
            getLogger().warning("Invalid dupeChance in config.yml. Must be between 0 and 100. Resetting to default.");
            dupeChance = 1.0;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return; // Not an item frame or player interaction, skip
        }
        if (requirePermission) {
            permissionManager.hasDupePermissionLuckPerms(player, hasPermission -> {
                if (!hasPermission) {
                    event.setCancelled(true);
                    log(String.format("Player %s does not have the required permissions.", player.getName()));
                    return;
                }
                processDuplication(player, frame);
            });
        } else {
            processDuplication(player, frame);
        }
    }

    private void processDuplication(Player player, ItemFrame frame) {
        ItemStack frameItem = frame.getItem();
        if (frameItem == null || frameItem.getType().isAir()) {
            return; // Empty item frame, skip
        }
        attemptDuplication(player, frameItem, frame.getLocation());
    }

    private void attemptDuplication(Player player, ItemStack frameItem, Location dropLoc) {
        double playerChance = player.hasPermission("dupes.vip") ? dupeChance * 2 : dupeChance;
        // No need to multiply random number by 100, dupeChance is already 0-100
        if (ThreadLocalRandom.current().nextDouble() * 100 > playerChance) {
            return; // Duplication failed by chance
        }
        getServer().getScheduler().runTask(this, () -> {
            dropLoc.getWorld().dropItemNaturally(dropLoc, frameItem.clone());
            if (enableMessages) {
                player.sendMessage(Component.text("[DupeS] Item duplicated!", NamedTextColor.GREEN));
            }
            if (logSuccessfulDuplications) {
                logDuplication(player, frameItem.getType().toString(), dropLoc, player.hasPermission("dupes.vip"));
            }
        });
    }

    private void logDuplication(Player player, String itemType, Location location, boolean isVip) {
        log(String.format("Player %s duplicated %s at [%d, %d, %d] using %s chance.",
                player.getName(),
                itemType,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                isVip ? "VIP" : "Default"));
    }

    protected void log(String message) {
        getLogger().info(message);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("dupe")) {
            return false;
        }
        if (!sender.hasPermission("dupes.admin")) {
            sender.sendMessage(Component.text("[DupeS] You don't have permission!", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelpMessage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                loadConfig();
                sender.sendMessage(Component.text("[DupeS] Configuration reloaded!", NamedTextColor.GREEN));
                return true;
            default:
                sender.sendMessage(Component.text("[DupeS] Unknown subcommand. Use /dupe help.", NamedTextColor.RED));
                return true;
        }
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(Component.text("[DupeS] Subcommands: reload", NamedTextColor.AQUA));
    }
}