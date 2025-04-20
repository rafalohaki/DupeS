package com.rafalohaki.DupeS;

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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
// Removed unused import: import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class DupeS extends JavaPlugin implements Listener {

    private static final int CURRENT_CONFIG_VERSION = 1;

    // --- Cached Components ---
    private static final Component MSG_PREFIX = Component.text("[DupeS] ", NamedTextColor.GRAY);
    private static final Component MSG_NO_PERMISSION = MSG_PREFIX.append(Component.text("You do not have permission!", NamedTextColor.RED));
    private static final Component MSG_NO_PERMISSION_DUPE = MSG_PREFIX.append(Component.text("You do not have permission to duplicate items.", NamedTextColor.RED));
    private static final Component MSG_CANNOT_DUPE_ITEM = MSG_PREFIX.append(Component.text("This item cannot be duplicated.", NamedTextColor.YELLOW));
    private static final Component MSG_DUPE_SUCCESS = MSG_PREFIX.append(Component.text("Item duplicated!", NamedTextColor.GREEN));
    private static final Component MSG_RELOAD_SUCCESS = MSG_PREFIX.append(Component.text("Configuration reloaded successfully.", NamedTextColor.GREEN));
    private static final Component MSG_RELOAD_FAIL = MSG_PREFIX.append(Component.text("Configuration reload failed. Check console for errors.", NamedTextColor.RED));
    private static final Component MSG_UNKNOWN_SUBCOMMAND = MSG_PREFIX.append(Component.text("Unknown subcommand. Use '/dupe help'.", NamedTextColor.RED));
    private static final Component HELP_HEADER = Component.text("--- DupeS Admin Commands ---", NamedTextColor.GOLD);
    private static final Component HELP_RELOAD = Component.text("/dupe reload - Reloads the plugin configuration.", NamedTextColor.AQUA);
    private static final Component HELP_HELP = Component.text("/dupe help - Shows this help message.", NamedTextColor.AQUA);
    // --- End Cached Components ---

    // Configuration values
    private double dupeChance;
    private boolean enableMessages;
    private boolean requirePermission;
    private boolean logSuccessfulDuplications;
    private boolean preventNbtDuplication;
    // Initialize blacklist to prevent potential NPE in getter if config fails early
    private Set<Material> blacklist = new HashSet<>();

    // Managers
    private PermissionManager permissionManager;
    private MetricsManager metricsManager; // Add field for MetricsManager

    // Utilities
    // Use DateTimeFormatter for thread safety
    private final DateTimeFormatter logTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        // --- LuckPerms Hook ---
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms API not found. DupeS requires LuckPerms. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LuckPerms luckPerms = provider.getProvider();
        log("Successfully hooked into LuckPerms.");

        permissionManager = new PermissionManager(this, luckPerms);

        // --- Configuration Loading ---
        saveDefaultConfig();
        if (!loadConfigValues()) {
            getLogger().severe("Failed to load configuration. Please check config.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return; // Stop enablement if config fails
        }

        // --- Initialize Metrics ---
        // Initialize MetricsManager *after* config is successfully loaded
        metricsManager = new MetricsManager(this);
        metricsManager.initializeMetrics(); // Call the initialization method

        // --- Event Registration ---
        getServer().getPluginManager().registerEvents(this, this);

        log("DupeS v" + this.getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        // Cancel any pending tasks
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
            } else if (configVersion > CURRENT_CONFIG_VERSION) {
                 getLogger().warning("Config version (v" + configVersion + ") is newer than plugin supports (v" + CURRENT_CONFIG_VERSION + "). Errors may occur.");
            }

            // --- Load Values with Validation ---
            boolean changed = false; // Flag to track if config needs saving

            // Dupe Chance
            if (!config.isDouble("dupe-chance") && !config.isInt("dupe-chance")) {
                 getLogger().warning("Config Error: 'dupe-chance' is not a valid number. Using default 1.0.");
                 dupeChance = 1.0; config.set("dupe-chance", dupeChance); changed = true;
            } else {
                dupeChance = config.getDouble("dupe-chance", 1.0);
                if (dupeChance < 0.0) { getLogger().warning("Config Warning: 'dupe-chance' < 0. Setting to 0.0."); dupeChance = 0.0; config.set("dupe-chance", dupeChance); changed = true; }
                else if (dupeChance > 100.0) { getLogger().warning("Config Warning: 'dupe-chance' > 100. Setting to 100.0."); dupeChance = 100.0; config.set("dupe-chance", dupeChance); changed = true; }
            }
            // Enable Messages
             if (!config.isBoolean("enable-messages")) { getLogger().warning("Config Error: 'enable-messages' is not true/false. Using default true."); enableMessages = true; config.set("enable-messages", enableMessages); changed = true; }
             else { enableMessages = config.getBoolean("enable-messages", true); }
            // Require Permission
             if (!config.isBoolean("require-permission")) { getLogger().warning("Config Error: 'require-permission' is not true/false. Using default true."); requirePermission = true; config.set("require-permission", requirePermission); changed = true; }
             else { requirePermission = config.getBoolean("require-permission", true); }
            // Log Successful Duplications
             if (!config.isBoolean("log-successful-duplications")) { getLogger().warning("Config Error: 'log-successful-duplications' is not true/false. Using default true."); logSuccessfulDuplications = true; config.set("log-successful-duplications", logSuccessfulDuplications); changed = true; }
             else { logSuccessfulDuplications = config.getBoolean("log-successful-duplications", true); }
            // Prevent NBT Duplication
             if (!config.isBoolean("prevent-nbt-duplication")) { getLogger().warning("Config Error: 'prevent-nbt-duplication' is not true/false. Using default false."); preventNbtDuplication = false; config.set("prevent-nbt-duplication", preventNbtDuplication); changed = true; }
             else { preventNbtDuplication = config.getBoolean("prevent-nbt-duplication", false); }
            // Blacklist
            if (!config.isList("blacklisted-items")) {
                 getLogger().warning("Config Error: 'blacklisted-items' is not a list. Using default blacklist.");
                 // Use default blacklist if loading fails
                 blacklist = new HashSet<>(Set.of(Material.BEDROCK, Material.COMMAND_BLOCK, Material.STRUCTURE_BLOCK));
                 config.set("blacklisted-items", blacklist.stream().map(Enum::name).collect(Collectors.toList())); changed = true;
            } else {
                List<String> stringList = config.getStringList("blacklisted-items");
                Set<Material> loadedBlacklist = new HashSet<>(); // Load into a temporary set
                for (String s : stringList) {
                    try {
                        Material mat = Material.matchMaterial(s.toUpperCase());
                        if (mat != null) {
                            loadedBlacklist.add(mat);
                        } else {
                            getLogger().warning("Config Warning: Invalid material '" + s + "' in blacklist.");
                        }
                    } catch (Exception e) {
                        getLogger().warning("Config Warning: Error parsing material '" + s + "' in blacklist.");
                    }
                 }
                 blacklist = loadedBlacklist; // Assign the loaded set
            }

            // Save config if corrections were made
            if (changed) {
                saveConfig();
                log("Configuration values corrected/updated and saved.");
            }

            log(String.format("Config loaded: Chance=%.2f%%, Messages=%b, RequirePerm=%b, Logging=%b, PreventNBT=%b, BlacklistSize=%d",
                  dupeChance, enableMessages, requirePermission, logSuccessfulDuplications, preventNbtDuplication, blacklist.size()));
            return true; // Indicate successful load
        } catch (Exception e) {
             getLogger().severe("FATAL: Could not load config.yml: " + e.getMessage());
             e.printStackTrace();
             return false; // Indicate failed load
        }
    }


    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Ensure the damaged entity is an ItemFrame and the damager is a Player
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return;
        }

        // Basic validity checks for the frame
        if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) {
             // Log warning if needed: getLogger().warning("Attempted dupe on invalid/unloaded frame.");
             return;
        }

        ItemStack frameItem = frame.getItem();
        // Add explicit null check alongside isAir() for clarity
        if (frameItem == null || frameItem.getType().isAir()) {
            return; // Allow breaking empty frames without triggering dupe logic
        }

        // Check blacklist and NBT settings
        if (isBlacklisted(frameItem) || (preventNbtDuplication && hasNbtData(frameItem))) {
            if (enableMessages) {
                player.sendMessage(MSG_CANNOT_DUPE_ITEM); // Use cached component
            }
            // No duplication attempt needed
            return;
        }

        // Check permission asynchronously if required
        if (requirePermission) {
            permissionManager.hasPermissionLuckPerms(player, "dupes.use", hasPermission -> {
                // This callback runs on the main thread (handled by PermissionManager)
                if (hasPermission) {
                    attemptDuplication(player, frame, frameItem);
                } else {
                    if (enableMessages) {
                        player.sendMessage(MSG_NO_PERMISSION_DUPE); // Use cached component
                    }
                    // Optional: Log permission denial for admins
                    // log(String.format("Player %s lacks permission 'dupes.use' for duplication.", player.getName()));
                }
            });
        } else {
            // No permission required, proceed directly
            attemptDuplication(player, frame, frameItem);
        }
    }

    private void attemptDuplication(Player player, ItemFrame frame, ItemStack frameItem) {
        // Run the duplication logic on the main server thread to ensure API safety
        getServer().getScheduler().runTask(this, () -> {
            try {
                 // Check dupe chance
                 if (ThreadLocalRandom.current().nextDouble() * 100.0 > dupeChance) {
                     // Optional: Send failure message if configured
                     // if (enableMessages) player.sendMessage(MSG_DUPE_FAIL);
                     return; // Chance failed
                 }

                 // Re-validate frame just before dropping item
                 if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) {
                     getLogger().warning("ItemFrame became invalid/unloaded before item drop for player " + player.getName());
                     return;
                 }

                 Location dropLoc = frame.getLocation(); // Get location before potentially modifying frame
                 ItemStack duplicatedItem = frameItem.clone(); // Clone the item

                 // Sanity check: Ensure we're not dropping AIR
                 if (duplicatedItem.getType().isAir()) {
                     getLogger().warning("Attempted to duplicate AIR, skipping drop for player " + player.getName());
                     return;
                 }

                 // Drop the duplicated item at the frame's location
                 dropLoc.getWorld().dropItemNaturally(dropLoc, duplicatedItem);

                 // Send message and log if applicable
                 if (player != null && player.isOnline()) {
                     if (enableMessages) {
                         player.sendMessage(MSG_DUPE_SUCCESS); // Use cached component
                     }
                     if (logSuccessfulDuplications) {
                         logDuplication(player.getName(), duplicatedItem.getType().toString(), dropLoc);
                     }
                 } else if (logSuccessfulDuplications) {
                     // Log even if player logged off between event and task execution
                     logDuplication("(Offline Player?)", duplicatedItem.getType().toString(), dropLoc);
                 }
            } catch (Exception e) {
                 getLogger().severe("An error occurred during item duplication drop task: " + e.getMessage());
                 e.printStackTrace();
            }
        });
    }

    // Logs successful duplication events if enabled in config
    private void logDuplication(String playerName, String itemType, Location location) {
         // Use the thread-safe DateTimeFormatter
         String timestamp = logTimestampFormat.format(LocalDateTime.now());
         String worldName = location.getWorld() != null ? location.getWorld().getName() : "UnknownWorld";
         log(String.format("[%s] Player %s duplicated %s at [%s, %d, %d, %d] (Chance: %.2f%%)",
                timestamp, playerName, itemType, worldName,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), dupeChance));
    }

    // Centralized logging method
    protected void log(String message) {
        getLogger().info(message);
    }

    // Checks if an item's material is in the blacklist
    private boolean isBlacklisted(ItemStack item) {
        // Check for null item and null/empty blacklist for safety
        return item != null && blacklist != null && !blacklist.isEmpty() && blacklist.contains(item.getType());
    }

    // Checks if an item has custom NBT data (name, lore, enchants, etc.)
    private boolean hasNbtData(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        // Check various common NBT tags
        return meta != null && (meta.hasDisplayName()
                || meta.hasLore()
                || meta.hasCustomModelData()
                || meta.hasAttributeModifiers()
                || meta.hasEnchants() // Check for enchantments specifically
                || !meta.getPersistentDataContainer().isEmpty()); // Check custom persistent data
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("dupe")) {
            return false; // Should not happen based on plugin.yml, but good practice
        }

        // Permission check for admin commands
        if (!sender.hasPermission("dupes.admin")) {
            sender.sendMessage(MSG_NO_PERMISSION); // Use cached component
            return true; // Command was handled (by denying permission)
        }

        String subCommand = (args.length > 0) ? args[0].toLowerCase() : "help"; // Default to help if no args

        // Use Switch Expression for cleaner command handling
        switch (subCommand) {
            case "reload" -> {
                if (loadConfigValues()) {
                    sender.sendMessage(MSG_RELOAD_SUCCESS); // Use cached component
                    log("Configuration reloaded by " + sender.getName());
                } else {
                    sender.sendMessage(MSG_RELOAD_FAIL); // Use cached component
                }
                return true; // Indicate command was handled
            }
            case "help" -> {
                sendHelpMessage(sender);
                return true; // Indicate command was handled
            }
            default -> {
                sender.sendMessage(MSG_UNKNOWN_SUBCOMMAND); // Use cached component
                sendHelpMessage(sender); // Show help on unknown command
                return true; // Indicate command was handled (with error msg)
            }
        }
        // Fallback, should technically not be reached with the default case
        // return false;
    }

    // Sends the help message to the command sender
    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(HELP_HEADER);   // Use cached component
        sender.sendMessage(HELP_RELOAD);  // Use cached component
        sender.sendMessage(HELP_HELP);    // Use cached component
    }

    // --- Getter methods for MetricsManager ---

    public boolean isRequirePermission() {
        return requirePermission;
    }

    public boolean isPreventNbtDuplication() {
        return preventNbtDuplication;
    }

    public boolean isEnableMessages() {
        return enableMessages;
    }

    public boolean isLogSuccessfulDuplications() {
        return logSuccessfulDuplications;
    }

    public double getDupeChance() {
        return dupeChance;
    }

    public int getBlacklistSize() {
        // Return 0 if blacklist is null (shouldn't happen with initialization) or empty
        return blacklist != null ? blacklist.size() : 0;
    }

    // --- End Getter methods ---
}