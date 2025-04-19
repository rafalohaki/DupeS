package com.rafalohaki.DupeS; // FIXED package name

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class DupeS extends JavaPlugin implements Listener {

    private static final int CURRENT_CONFIG_VERSION = 1;

    private double dupeChance;
    private boolean enableMessages;
    private boolean requirePermission;
    private boolean logSuccessfulDuplications;
    private boolean preventNbtDuplication;
    private Set<Material> blacklist;
    private PermissionManager permissionManager;
    private final SimpleDateFormat logTimestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        // LuckPerms Hook
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms API not found. DupeS requires LuckPerms. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LuckPerms luckPerms = provider.getProvider();
        log("Successfully hooked into LuckPerms.");

        permissionManager = new PermissionManager(this, luckPerms);

        // Configuration Loading
        saveDefaultConfig(); // Ensure config.yml exists
        if (!loadConfigValues()) {
            getLogger().severe("Failed to load configuration. Please check config.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Event Registration
        getServer().getPluginManager().registerEvents(this, this);

        // Use non-deprecated method for version - FIX for diagnostic
        log("DupeS v" + this.getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        log("DupeS disabled.");
    }

    private boolean loadConfigValues() {
        try {
            reloadConfig();
            FileConfiguration config = getConfig();

            // --- Config Version Check ---
            int configVersion = config.getInt("config-version", 0);
            if (configVersion < CURRENT_CONFIG_VERSION) {
                getLogger().warning("Old config version detected (v" + configVersion + "). Please consider regenerating your config.yml (backup first!). Attempting to load anyway...");
                // Add config migration logic here if needed in the future
            } else if (configVersion > CURRENT_CONFIG_VERSION) {
                 getLogger().warning("Config version (v" + configVersion + ") is newer than plugin supports (v" + CURRENT_CONFIG_VERSION + "). Errors may occur.");
            }

            // --- Load Values with Validation ---
            boolean changed = false; // Track if we need to save the config

            // Dupe Chance
            if (!config.isDouble("dupe-chance") && !config.isInt("dupe-chance")) {
                 getLogger().warning("Config Error: 'dupe-chance' is not a valid number. Using default 1.0.");
                 dupeChance = 1.0;
                 config.set("dupe-chance", dupeChance); changed = true;
            } else {
                dupeChance = config.getDouble("dupe-chance", 1.0);
                if (dupeChance < 0.0) {
                    getLogger().warning("Config Warning: 'dupe-chance' < 0. Setting to 0.0.");
                    dupeChance = 0.0; config.set("dupe-chance", dupeChance); changed = true;
                } else if (dupeChance > 100.0) {
                    getLogger().warning("Config Warning: 'dupe-chance' > 100. Setting to 100.0.");
                    dupeChance = 100.0; config.set("dupe-chance", dupeChance); changed = true;
                }
            }

            // Enable Messages
            if (!config.isBoolean("enable-messages")) {
                 getLogger().warning("Config Error: 'enable-messages' is not true/false. Using default true.");
                 enableMessages = true;
                 config.set("enable-messages", enableMessages); changed = true;
            } else {
                 enableMessages = config.getBoolean("enable-messages", true);
            }

            // Require Permission
             if (!config.isBoolean("require-permission")) {
                 getLogger().warning("Config Error: 'require-permission' is not true/false. Using default true.");
                 requirePermission = true;
                 config.set("require-permission", requirePermission); changed = true;
            } else {
                 requirePermission = config.getBoolean("require-permission", true);
            }

            // Log Successful Duplications
             if (!config.isBoolean("log-successful-duplications")) {
                 getLogger().warning("Config Error: 'log-successful-duplications' is not true/false. Using default true.");
                 logSuccessfulDuplications = true;
                 config.set("log-successful-duplications", logSuccessfulDuplications); changed = true;
            } else {
                 logSuccessfulDuplications = config.getBoolean("log-successful-duplications", true);
            }

            // Prevent NBT Duplication
             if (!config.isBoolean("prevent-nbt-duplication")) {
                 getLogger().warning("Config Error: 'prevent-nbt-duplication' is not true/false. Using default false.");
                 preventNbtDuplication = false;
                 config.set("prevent-nbt-duplication", preventNbtDuplication); changed = true;
            } else {
                 preventNbtDuplication = config.getBoolean("prevent-nbt-duplication", false);
            }

            // Blacklist
            if (!config.isList("blacklisted-items")) {
                 getLogger().warning("Config Error: 'blacklisted-items' is not a list. Using default blacklist.");
                 blacklist = new HashSet<>(Set.of(Material.BEDROCK, Material.COMMAND_BLOCK, Material.STRUCTURE_BLOCK));
                 config.set("blacklisted-items", blacklist.stream().map(Enum::name).collect(Collectors.toList())); changed = true;
            } else {
                List<String> stringList = config.getStringList("blacklisted-items");
                blacklist = new HashSet<>();
                for (String s : stringList) {
                    try {
                        Material mat = Material.matchMaterial(s.toUpperCase());
                        if (mat != null) {
                            blacklist.add(mat);
                        } else {
                            getLogger().warning("Config Warning: Invalid material '" + s + "' in blacklist.");
                        }
                    } catch (Exception e) {
                         getLogger().warning("Config Warning: Error parsing material '" + s + "' in blacklist.");
                    }
                }
            }

            // Save if corrections were made
            if (changed) {
                saveConfig();
                log("Configuration values corrected/updated and saved.");
            }

            log(String.format("Config loaded: Chance=%.2f%%, Messages=%b, RequirePerm=%b, Logging=%b, PreventNBT=%b, BlacklistSize=%d",
                    dupeChance, enableMessages, requirePermission, logSuccessfulDuplications, preventNbtDuplication, blacklist.size()));

            return true; // Load successful

        } catch (Exception e) {
            getLogger().severe("FATAL: Could not load config.yml: " + e.getMessage());
            e.printStackTrace();
            return false; // Load failed
        }
    }


    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return;
        }

        // --- Add Robustness Checks ---
        if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) {
             // Log this attempt? Might be spammy. Silently return for now.
             return;
        }

        ItemStack frameItem = frame.getItem();
        if (frameItem == null || frameItem.getType().isAir()) {
            return; // Allow breaking empty frames
        }

        // --- Blacklist / NBT Check ---
        if (isBlacklisted(frameItem) || (preventNbtDuplication && hasNbtData(frameItem))) {
            if (enableMessages) {
                player.sendMessage(Component.text("[DupeS] This item cannot be duplicated.", NamedTextColor.YELLOW));
            }
            // Don't cancel the event, just prevent duplication
            return;
        }


        // --- Permission Check ---
        if (requirePermission) {
            permissionManager.hasPermissionLuckPerms(player, "dupes.use", hasPermission -> {
                if (hasPermission) {
                    attemptDuplication(player, frame, frameItem);
                } else {
                    if (enableMessages) {
                        player.sendMessage(Component.text("[DupeS] You do not have permission to duplicate items.", NamedTextColor.RED));
                    }
                    log(String.format("Player %s lacks permission 'dupes.use' for duplication.", player.getName()));
                }
            });
        } else {
            attemptDuplication(player, frame, frameItem);
        }
    }

    private void attemptDuplication(Player player, ItemFrame frame, ItemStack frameItem) {
        // Run remaining logic on the main thread
        getServer().getScheduler().runTask(this, () -> {
            try {
                 // --- Move Chance Check Inside Task ---
                 if (ThreadLocalRandom.current().nextDouble() * 100.0 > dupeChance) {
                    // Optional: Add failure message if desired
                    // if (enableMessages) player.sendMessage(Component.text("[DupeS] Duplication failed (chance).", NamedTextColor.YELLOW));
                    return; // Duplication failed by chance
                 }

                // Check frame validity again *just before* dropping
                 if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) {
                     getLogger().warning("ItemFrame became invalid/unloaded before item drop for player " + player.getName());
                     return;
                 }
                 Location dropLoc = frame.getLocation();

                ItemStack duplicatedItem = frameItem.clone();
                if (duplicatedItem.getType().isAir()) { // Sanity check
                    getLogger().warning("Attempted to duplicate AIR, skipping drop.");
                    return;
                }

                dropLoc.getWorld().dropItemNaturally(dropLoc, duplicatedItem);

                if (player != null && player.isOnline()) {
                    if (enableMessages) {
                        player.sendMessage(Component.text("[DupeS] Item duplicated!", NamedTextColor.GREEN));
                    }
                    if (logSuccessfulDuplications) {
                        logDuplication(player.getName(), duplicatedItem.getType().toString(), dropLoc);
                    }
                } else if (logSuccessfulDuplications) {
                    logDuplication("(Offline Player?)", duplicatedItem.getType().toString(), dropLoc);
                }

            } catch (Exception e) {
                getLogger().severe("An error occurred during item duplication drop task: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // Updated logging format with timestamp
    private void logDuplication(String playerName, String itemType, Location location) {
         String timestamp = logTimestampFormat.format(new Date());
         String worldName = location.getWorld() != null ? location.getWorld().getName() : "UnknownWorld";
         log(String.format("[%s] Player %s duplicated %s at [%s, %d, %d, %d] (Chance: %.2f%%)",
                timestamp,
                playerName,
                itemType,
                worldName,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                dupeChance));
    }

    // Centralized logging
    protected void log(String message) {
        getLogger().info(message);
    }

    // --- Helper Methods ---
    private boolean isBlacklisted(ItemStack item) {
        return item != null && blacklist.contains(item.getType());
    }

    private boolean hasNbtData(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        // Check common NBT indicators. More specific checks (like custom tags) might require NBT API library.
        return meta.hasDisplayName() || meta.hasLore() || meta.hasCustomModelData() || meta.hasAttributeModifiers() || !meta.getPersistentDataContainer().isEmpty();
        // Note: Enchantments might be considered basic NBT, adjust if needed.
        // return meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelData() || meta.hasAttributeModifiers() || !meta.getPersistentDataContainer().isEmpty();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("dupe")) {
            return false;
        }
        if (!sender.hasPermission("dupes.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelpMessage(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (loadConfigValues()) { // Reload and validate
                sender.sendMessage(Component.text("[DupeS] Configuration reloaded successfully.", NamedTextColor.GREEN));
            } else {
                 sender.sendMessage(Component.text("[DupeS] Configuration reload failed. Check console for errors.", NamedTextColor.RED));
            }
            return true;
        }

        sender.sendMessage(Component.text("[DupeS] Unknown subcommand. Use '/dupe help'.", NamedTextColor.RED));
        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(Component.text("--- DupeS Admin Commands ---", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/dupe reload - Reloads the plugin configuration.", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/dupe help - Shows this help message.", NamedTextColor.AQUA));
    }
}