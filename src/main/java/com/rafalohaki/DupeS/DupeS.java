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
import java.util.HashSet;
import java.util.UUID;

public class DupeS extends JavaPlugin implements Listener {
    private double dupeChance;
    private boolean enableMessages;
    private boolean requirePermission;
    private final HashSet<UUID> recentDupes = new HashSet<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DupeS enabled with dupe chance: " + dupeChance + "%");
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
        if (!(event.getEntity() instanceof ItemFrame frame) || 
            !(event.getDamager() instanceof Player player)) {
            return;
        }

        // Permission check if enabled
        if (requirePermission && !player.hasPermission("dupes.use")) {
            return;
        }

        // Anti-spam check
        UUID playerUUID = player.getUniqueId();
        if (recentDupes.contains(playerUUID)) {
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

        // Add player to recent dupes set and remove after 2 ticks
        recentDupes.add(playerUUID);
        getServer().getScheduler().runTaskLater(this, () -> recentDupes.remove(playerUUID), 2L);

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
}