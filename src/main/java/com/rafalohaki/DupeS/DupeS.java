package com.rafalohaki.dupe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.plugin.java.JavaPlugin;

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


    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        validateConfigValues();
        getServer().getPluginManager().registerEvents(this, this);
        startCooldownCleaner();
        getLogger().info(String.format("DupeS enabled: dupeChance=%.1f%%, dupeChanceVip=%.1f%%, cooldown=%dms",
                dupeChance, dupeChanceVip, cooldownMillis));
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        getLogger().info("DupeS disabled. All resources cleaned up.");
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
            return;
        }

        // Enforce permission if required
        if (requirePermission && !player.hasPermission("dupes.use")) {
            event.setCancelled(true); // Block interaction and duplication
            return;
        }

        UUID playerUUID = player.getUniqueId();
        if (isPlayerOnCooldown(playerUUID)) {
            return; // Prevent duplication during cooldown
        }

        ItemStack frameItem = frame.getItem();
        if (frameItem == null || frameItem.getType().isAir()) {
            return; // Skip empty item frames
        }

        double playerChance = player.hasPermission("dupes.vip") ? dupeChanceVip : dupeChance;
        if (ThreadLocalRandom.current().nextDouble() * 100 > playerChance) {
            return; // Skip if duplication chance fails
        }

        addPlayerToCooldown(playerUUID, cooldownMillis);

        Location dropLoc = frame.getLocation();
        getServer().getScheduler().runTask(this, () -> {
           dropLoc.getWorld().dropItemNaturally(dropLoc, frameItem.clone());

            if (enableMessages) {
                player.sendMessage(Component.text("[DupeS] Item duplicated!", NamedTextColor.GREEN));
            }

            if (logSuccessfulDuplications) {
               getLogger().info(String.format("Player %s duplicated %s at [%d, %d, %d] using %s chance.",
                        player.getName(),
                        frameItem.getType(),
                        dropLoc.getBlockX(), dropLoc.getBlockY(), dropLoc.getBlockZ(),
                        player.hasPermission("dupes.vip") ? "VIP" : "Default"));
           }
       });
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
          sender.sendMessage(Component.text("[DupeS] Subcommands: getchance, status, reload", NamedTextColor.AQUA));
          return true;
      }
       if (args[0].equalsIgnoreCase("getchance")) {
         sender.sendMessage(Component.text(String.format("[DupeS] Default chance: %.1f%%, VIP chance: %.1f%%",
                dupeChance, dupeChanceVip), NamedTextColor.GREEN));
         return true;
      }

     if (args[0].equalsIgnoreCase("status")) {
           if (sender instanceof Player player) {
              UUID playerUUID = player.getUniqueId();
                long remainingTime = isPlayerOnCooldown(playerUUID) ?
                      (recentDupes.get(playerUUID) - System.nanoTime()) / 1_000_000_000 : 0;
            sender.sendMessage(Component.text(String.format("[DupeS] Cooldown: %d seconds", remainingTime),
                  NamedTextColor.YELLOW));
          } else {
               sender.sendMessage(Component.text("[DupeS] Only players can check their status.", NamedTextColor.RED));
           }
           return true;
      }
    if (args[0].equalsIgnoreCase("reload")) {
          loadConfig();
          validateConfigValues();
          sender.sendMessage(Component.text("[DupeS] Configuration reloaded!", NamedTextColor.GREEN));
           return true;
       }
    sender.sendMessage(Component.text("[DupeS] Unknown subcommand. Use /dupe help.", NamedTextColor.RED));
        return true;
    }

    private boolean isPlayerOnCooldown(UUID playerUUID) {
       long currentTime = System.nanoTime();
        return recentDupes.getOrDefault(playerUUID, 0L) > currentTime;
    }

   private void addPlayerToCooldown(UUID playerUUID, long cooldownMillis) {
      recentDupes.put(playerUUID, System.nanoTime() + cooldownMillis * 1_000_000L);
    }

   private void startCooldownCleaner() {
       getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
          long currentTime = System.nanoTime();
           recentDupes.entrySet().removeIf(entry -> entry.getValue() <= currentTime);
        }, 100L, 100L);
    }
}