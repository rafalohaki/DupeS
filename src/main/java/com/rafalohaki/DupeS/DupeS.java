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
    private boolean enableMessages;
    private boolean requirePermission;
    private final ConcurrentHashMap<UUID, Long> recentDupes = new ConcurrentHashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startCooldownCleaner();
        getLogger().info("DupeS enabled with dupe chance: " + dupeChance + "%");
    }

    @Override
    public void onDisable() {
        // Cancel all scheduled tasks to prevent memory leaks
        getServer().getScheduler().cancelTasks(this);
        getLogger().info("DupeS disabled. All tasks cancelled.");
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        // Set defaults if they don't exist
        config.addDefault("dupe-chance", 100.0);
        config.addDefault("enable-messages", true);
        config.addDefault("require-permission", false);
        config.options().copyDefaults(true);
        saveConfig();

        // Load values
        dupeChance = config.getDouble("dupe-chance", 100.0);
        enableMessages = config.getBoolean("enable-messages", true);
        requirePermission = config.getBoolean("require-permission", false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return;
        }

        // Permission check if enabled
        if (requirePermission && !player.hasPermission("dupes.use")) {
            return;
        }

        // Anti-spam check
        UUID playerUUID = player.getUniqueId();
        if (isPlayerOnCooldown(playerUUID)) {
            return;
        }

        ItemStack frameItem = frame.getItem();
        if (frameItem == null || frameItem.getType().isAir()) {
            return;
        }

        // Check duplication chance
        if (random.nextDouble() * 100 > dupeChance) {
            return;  // Failed chance check
        }

        // Add player to cooldown
        addPlayerToCooldown(playerUUID, 2000); // 2-second cooldown

        // Drop duplicate item after 1 tick
        Location dropLoc = frame.getLocation();
        getServer().getScheduler().runTaskLater(this, () -> {
            dropLoc.getWorld().dropItemNaturally(dropLoc, frameItem.clone());

            if (enableMessages) {
                player.sendMessage(Component.text("[DupeS] ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text("Item duplicated!")
                                .color(NamedTextColor.YELLOW)));
            }
        }, 1L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("dupereload")) {
            return false;
        }

        if (!sender.hasPermission("dupes.reload")) {
            sender.sendMessage(Component.text("[DupeS] No permission!")
                    .color(NamedTextColor.RED));
            return true;
        }

        loadConfig();
        sender.sendMessage(Component.text("[DupeS] ")
                .color(NamedTextColor.GREEN)
                .append(Component.text("Configuration reloaded! Dupe chance: " + dupeChance + "%")
                        .color(NamedTextColor.YELLOW)));
        return true;
    }

    // Cooldown management
    private boolean isPlayerOnCooldown(UUID playerUUID) {
        long currentTime = System.currentTimeMillis();
        return recentDupes.getOrDefault(playerUUID, 0L) > currentTime;
    }

    private void addPlayerToCooldown(UUID playerUUID, long cooldownMillis) {
        recentDupes.put(playerUUID, System.currentTimeMillis() + cooldownMillis);
    }

    // Periodic cleanup of expired cooldowns
    private void startCooldownCleaner() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            long currentTime = System.currentTimeMillis();
            recentDupes.entrySet().removeIf(entry -> entry.getValue() <= currentTime);
        }, 20L, 20L); // Runs every second
    }
}
