package com.rafalohaki.dupe;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Location;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DupeS extends JavaPlugin implements Listener {
    private double dupeChance;
    private double dupeChanceVip;
    private boolean enableMessages;
    private boolean requirePermission;
    private boolean logSuccessfulDuplications;
    private long cooldownMillis;
    private final ConcurrentHashMap<UUID, Long> recentDupes = new ConcurrentHashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startCooldownCleaner();
        getLogger().info(String.format("DupeS enabled: dupeChance=%.1f%%, dupeChanceVip=%.1f%%, cooldown=%dms",
                dupeChance, dupeChanceVip, cooldownMillis));
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this); // Cancel scheduled tasks
        HandlerList.unregisterAll(this.getServer().getPluginManager().getPlugin("DupeS")); // Unregister all listeners
        getLogger().info("DupeS disabled. All resources cleaned up.");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        // Load and validate configuration values
        dupeChance = Math.max(0.0, Math.min(config.getDouble("dupe-chance", 1.0), 100.0));
        dupeChanceVip = Math.max(0.0, Math.min(config.getDouble("dupe-chance-vip", 2.0), 100.0));
        enableMessages = config.getBoolean("enable-messages", true);
        requirePermission = config.getBoolean("require-permission", false);
        cooldownMillis = Math.max(config.getLong("cooldown-millis", 1000L), 0);
        logSuccessfulDuplications = config.getBoolean("log-successful-duplications", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return; // Ignore non-player or non-item-frame interactions
        }

        if (requirePermission && !player.hasPermission("dupes.use")) {
            return; // Block unauthorized duplication attempts
        }

        UUID playerUUID = player.getUniqueId();
        if (isPlayerOnCooldown(playerUUID)) {
            return; // Prevent duplication spam using cooldown
        }

        ItemStack frameItem = frame.getItem();
        if (frameItem == null || frameItem.getType().isAir()) {
            return; // Skip empty item frames
        }

        double playerChance = player.hasPermission("dupes.vip") ? dupeChanceVip : dupeChance;
        if (random.nextDouble() * 100 > playerChance) {
            return; // Skip if duplication chance fails
        }

        addPlayerToCooldown(playerUUID, cooldownMillis);

        Location dropLoc = frame.getLocation();
        getServer().getScheduler().runTask(this, () -> {
            dropLoc.getWorld().dropItemNaturally(dropLoc, frameItem.clone());

            if (enableMessages) {
                player.sendMessage(Component.text("[DupeS] Item duplicated!")
                        .color(NamedTextColor.GREEN));
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
        if (!command.getName().equalsIgnoreCase("dupereload")) {
            return false;
        }

        if (!sender.hasPermission("dupes.reload")) {
            sender.sendMessage(Component.text("[DupeS] You don't have permission!")
                    .color(NamedTextColor.RED));
            return true;
        }

        loadConfig();
        sender.sendMessage(Component.text(String.format("[DupeS] Configuration reloaded: dupeChance=%.1f%%, dupeChanceVip=%.1f%%",
                dupeChance, dupeChanceVip))
                .color(NamedTextColor.GREEN));
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
        }, 100L, 100L); // Run cleanup every 5 seconds
    }
}
