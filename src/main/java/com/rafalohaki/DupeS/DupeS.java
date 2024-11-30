package com.rafalohaki.dupe;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
    private boolean consoleLogs;
    private long cooldownMillis;
    private final ConcurrentHashMap<UUID, Long> recentDupes = new ConcurrentHashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startCooldownCleaner();
        getLogger().info("DupeS enabled with chances: " +
                dupeChance + "% (default), " + dupeChanceVip + "% (VIP), and cooldown: " + cooldownMillis + "ms");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        getLogger().info("DupeS disabled. All tasks cancelled.");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        // Define defaults and save them
        config.addDefault("dupe-chance", 1.0);
        config.addDefault("dupe-chance-vip", 2.0);
        config.addDefault("enable-messages", true);
        config.addDefault("require-permission", false);
        config.addDefault("cooldown-millis", 1000L);
        config.addDefault("console-logs", true);
        config.options().copyDefaults(true);
        saveConfig();

        // Load and validate values
        dupeChance = Math.max(0.0, Math.min(config.getDouble("dupe-chance"), 100.0)); // Cap at 100%, min at 0%
        dupeChanceVip = Math.max(0.0, Math.min(config.getDouble("dupe-chance-vip"), 100.0)); // Cap at 100%, min at 0%
        enableMessages = config.getBoolean("enable-messages");
        requirePermission = config.getBoolean("require-permission");
        cooldownMillis = Math.max(config.getLong("cooldown-millis"), 0L); // Ensure non-negative
        consoleLogs = config.getBoolean("console-logs", true); // Load console logs value
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Ensure event entities are valid and of the correct types
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return;
        }

        if (requirePermission && !player.hasPermission("dupes.use")) {
            return;
        }

        UUID playerUUID = player.getUniqueId();
        if (isPlayerOnCooldown(playerUUID)) {
            return;
        }

        ItemStack frameItem = frame.getItem();
        if (frameItem == null || frameItem.getType().isAir()) {
            return;
        }

        // Determine chance based on permissions
        double playerChance = player.hasPermission("dupes.vip") ? dupeChanceVip : dupeChance;
        if (random.nextDouble() * 100 > playerChance) {
            return;  // Failed chance check
        }

        addPlayerToCooldown(playerUUID, cooldownMillis);

        Location dropLoc = frame.getLocation();
        getServer().getScheduler().runTask(this, () -> { // Ensures world access is synchronous
            dropLoc.getWorld().dropItemNaturally(dropLoc, frameItem.clone());

            if (enableMessages) {
                player.sendMessage(Component.text("[DupeS] ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text("Item duplicated!")
                                .color(NamedTextColor.YELLOW)));
            }

            // Log to console only if consoleLogs is true
            if (consoleLogs) {
                getLogger().info("Player " + player.getName() + " successfully duplicated an item with chance: " + playerChance + "%");
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("dupereload")) {
            if (!sender.hasPermission("dupes.reload")) {
                sender.sendMessage(Component.text("[DupeS] No permission!")
                        .color(NamedTextColor.RED));
                return true;
            }

            loadConfig();
            sender.sendMessage(Component.text("[DupeS] Configuration reloaded! Dupe chance: " +
                    dupeChance + "% (default), " + dupeChanceVip + "% (VIP)")
                    .color(NamedTextColor.GREEN));
            return true;
        }
        return false;
    }

    private boolean isPlayerOnCooldown(UUID playerUUID) {
        long currentTime = System.currentTimeMillis();
        return recentDupes.getOrDefault(playerUUID, 0L) > currentTime;
    }

    private void addPlayerToCooldown(UUID playerUUID, long cooldownMillis) {
        recentDupes.put(playerUUID, System.currentTimeMillis() + cooldownMillis);
    }

    private void startCooldownCleaner() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            long currentTime = System.currentTimeMillis();
            recentDupes.entrySet().removeIf(entry -> entry.getValue() <= currentTime);
        }, 100L, 100L); // Run every 5 seconds
    }
}