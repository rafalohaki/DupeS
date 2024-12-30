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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class DupeS extends JavaPlugin implements Listener {

    private double dupeChance;
    private double dupeChanceVip;
    private boolean enableMessages;
    private boolean requirePermission;
    private boolean logSuccessfulDuplications;
    private long cooldownMillis;
    private final ConcurrentHashMap<UUID, Long> recentDupes = new ConcurrentHashMap<>();
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
        } else {
            getLogger().severe("LuckPerms API not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startCooldownCleaner();
        log(String.format("DupeS enabled: dupeChance=%.1f%%, dupeChanceVip=%.1f%%, cooldown=%dms",
                dupeChance, dupeChanceVip, cooldownMillis));
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        log("DupeS disabled. All resources cleaned up.");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        dupeChance = config.getDouble("dupe-chance", 1.0);
        dupeChanceVip = config.getDouble("dupe-chance-vip", 2.0);
        enableMessages = config.getBoolean("enable-messages", true);
        requirePermission = config.getBoolean("require-permission", true);
        cooldownMillis = config.getLong("cooldown-millis", 1000L);
        logSuccessfulDuplications = config.getBoolean("log-successful-duplications", true);
        validateConfigValues();
    }

    private void validateConfigValues() {
        if (dupeChance < 0.0 || dupeChance > 100.0) {
            getLogger().warning("Invalid dupeChance. Resetting to default.");
            dupeChance = 1.0;
        }
        if (dupeChanceVip < 0.0 || dupeChanceVip > 100.0) {
            getLogger().warning("Invalid dupeChanceVip. Resetting to default.");
            dupeChanceVip = 2.0;
        }
        if (cooldownMillis < 0) {
            getLogger().warning("Invalid cooldownMillis. Resetting to default.");
            cooldownMillis = 1000L;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
         if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return; // Not an item frame or player interaction, skip
        }
        UUID playerUUID = player.getUniqueId();

         //First Check Cooldown
        if (isOnCooldown(playerUUID)) {
          return; //Player is on cooldown, skip
        }

         //Then Check Permissions
         if (requirePermission && !hasDupePermissionLuckPerms(player)) {
           event.setCancelled(true); // Player lacks permission, skip
            return;
         }

        ItemStack frameItem = frame.getItem();
        if (frameItem == null || frameItem.getType().isAir()) {
           return; // Empty item frame, skip
        }
         attemptDuplication(player, frameItem, frame.getLocation());
         addPlayerToCooldown(playerUUID);
    }

    private boolean hasDupePermissionLuckPerms(Player player) {
         return hasPermissionLuckPerms(player, "dupes.use");
    }

    private boolean isVipLuckPerms(Player player) {
        return hasPermissionLuckPerms(player, "dupes.vip");
    }

    private boolean hasPermissionLuckPerms(Player player, String permission) {
        if (luckPerms == null) return player.hasPermission(permission);
        return luckPerms.getUserManager().loadUser(player.getUniqueId()).thenApplyAsync(user ->
                Optional.ofNullable(user)
                        .map(u -> u.getCachedData().getPermissionData().checkPermission(permission).asBoolean())
                        .orElse(false)
        ).join();
    }

    private void attemptDuplication(Player player, ItemStack frameItem, Location dropLoc) {
        double playerChance = isVipLuckPerms(player) ? dupeChanceVip : dupeChance;
        if (ThreadLocalRandom.current().nextDouble() * 100 > playerChance) {
            return; // Duplication failed by chance
        }
         getServer().getScheduler().runTask(this, () -> {
            dropLoc.getWorld().dropItemNaturally(dropLoc, frameItem.clone());
            if (enableMessages) {
                player.sendMessage(Component.text("[DupeS] Item duplicated!", NamedTextColor.GREEN));
            }
            if (logSuccessfulDuplications) {
                logDuplication(player, frameItem.getType().toString(), dropLoc, isVipLuckPerms(player));
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
    private void log(String message) {
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
            case "getchance":
                sendChanceMessage(sender);
                return true;
            case "status":
                sendStatusMessage(sender);
                return true;
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
        sender.sendMessage(Component.text("[DupeS] Subcommands: getchance, status, reload", NamedTextColor.AQUA));
    }
   private void sendChanceMessage(CommandSender sender) {
        sender.sendMessage(Component.text(String.format("[DupeS] Default chance: %.1f%%, VIP chance: %.1f%%",
                dupeChance, dupeChanceVip), NamedTextColor.GREEN));
    }
      private void sendStatusMessage(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[DupeS] Only players can check their status.", NamedTextColor.RED));
            return;
        }

        UUID playerUUID = player.getUniqueId();
        long remainingSeconds = getRemainingCooldownSeconds(playerUUID);
        sender.sendMessage(Component.text(String.format("[DupeS] Cooldown: %d seconds", remainingSeconds),
                NamedTextColor.YELLOW));
    }

    private boolean isOnCooldown(UUID playerUUID) {
        return recentDupes.getOrDefault(playerUUID, 0L) > System.currentTimeMillis();
    }

    private long getRemainingCooldownSeconds(UUID playerUUID) {
        long remainingMillis = recentDupes.getOrDefault(playerUUID, 0L) - System.currentTimeMillis();
        return Math.max(0, remainingMillis / 1000);
    }

    private void addPlayerToCooldown(UUID playerUUID) {
        recentDupes.put(playerUUID, System.currentTimeMillis() + cooldownMillis);
    }
    private void startCooldownCleaner() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            long currentTime = System.currentTimeMillis();
            recentDupes.entrySet().removeIf(entry -> entry.getValue() <= currentTime);
        }, 100L, 100L);
    }
}