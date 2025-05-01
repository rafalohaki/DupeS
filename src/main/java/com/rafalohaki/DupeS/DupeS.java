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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean; // Used for mutable boolean flag
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
    private MetricsManager metricsManager;

    // Utilities
    private final DateTimeFormatter logTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        // --- LuckPerms Hook ---
        if (!hookLuckPerms()) {
            getLogger().severe("LuckPerms API not found or failed to hook. DupeS requires LuckPerms. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // --- Configuration Loading ---
        saveDefaultConfig();
        if (!loadConfigValues()) {
            getLogger().severe("Failed to load configuration. Please check config.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return; // Stop enablement if config fails
        }

        // --- Initialize Metrics ---
        metricsManager = new MetricsManager(this);
        metricsManager.initializeMetrics();

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

    private boolean hookLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            return false;
        }
        LuckPerms luckPerms = provider.getProvider();
        permissionManager = new PermissionManager(this, luckPerms);
        log("Successfully hooked into LuckPerms.");
        return true;
    }

    // --- Configuration Loading Refactored ---

    private boolean loadConfigValues() {
        try {
            reloadConfig();
            FileConfiguration config = getConfig();
            AtomicBoolean configChanged = new AtomicBoolean(false); // Track if saving is needed

            checkConfigVersion(config); // Check version first

            // Load individual settings using helper methods
            this.dupeChance = loadDupeChance(config, configChanged);
            this.enableMessages = loadBooleanSetting(config, "enable-messages", true, configChanged);
            this.requirePermission = loadBooleanSetting(config, "require-permission", true, configChanged);
            this.logSuccessfulDuplications = loadBooleanSetting(config, "log-successful-duplications", true, configChanged);
            this.preventNbtDuplication = loadBooleanSetting(config, "prevent-nbt-duplication", false, configChanged);
            this.blacklist = loadBlacklist(config, configChanged);

            // Save config if any corrections were made
            if (configChanged.get()) {
                saveConfig();
                log("Configuration values corrected/updated and saved.");
            }

            logLoadedConfig(); // Log summary
            return true; // Indicate successful load

        } catch (Exception e) {
            getLogger().severe("FATAL: Could not load config.yml: " + e.getMessage());
            e.printStackTrace();
            return false; // Indicate failed load
        }
    }

    private void checkConfigVersion(FileConfiguration config) {
        int configVersion = config.getInt("config-version", 0);
        if (configVersion < CURRENT_CONFIG_VERSION) {
            getLogger().warning("Old config version detected (v" + configVersion + "). Please consider regenerating your config.yml (backup first!). Attempting to load anyway...");
        } else if (configVersion > CURRENT_CONFIG_VERSION) {
            getLogger().warning("Config version (v" + configVersion + ") is newer than plugin supports (v" + CURRENT_CONFIG_VERSION + "). Errors may occur.");
        }
    }

    private double loadDupeChance(FileConfiguration config, AtomicBoolean changedFlag) {
        double loadedChance;
        String path = "dupe-chance";

        if (!config.isDouble(path) && !config.isInt(path)) {
            getLogger().warning("Config Error: '" + path + "' is not a valid number. Using default 1.0.");
            loadedChance = 1.0;
            config.set(path, loadedChance);
            changedFlag.set(true);
        } else {
            loadedChance = config.getDouble(path, 1.0);
            if (loadedChance < 0.0) {
                getLogger().warning("Config Warning: '" + path + "' < 0. Setting to 0.0.");
                loadedChance = 0.0;
                config.set(path, loadedChance);
                changedFlag.set(true);
            } else if (loadedChance > 100.0) {
                getLogger().warning("Config Warning: '" + path + "' > 100. Setting to 100.0.");
                loadedChance = 100.0;
                config.set(path, loadedChance);
                changedFlag.set(true);
            }
        }
        return loadedChance;
    }

    private boolean loadBooleanSetting(FileConfiguration config, String path, boolean defaultValue, AtomicBoolean changedFlag) {
        boolean value;
        if (!config.isBoolean(path)) {
            getLogger().warning("Config Error: '" + path + "' is not true/false. Using default " + defaultValue + ".");
            value = defaultValue;
            config.set(path, value);
            changedFlag.set(true);
        } else {
            value = config.getBoolean(path, defaultValue);
        }
        return value;
    }

    private Set<Material> loadBlacklist(FileConfiguration config, AtomicBoolean changedFlag) {
        String path = "blacklisted-items";
        Set<Material> loadedBlacklist = new HashSet<>();

        if (!config.isList(path)) {
            getLogger().warning("Config Error: '" + path + "' is not a list. Using default blacklist.");
            // Default blacklist
            loadedBlacklist.addAll(Set.of(Material.BEDROCK, Material.COMMAND_BLOCK, Material.STRUCTURE_BLOCK));
            config.set(path, loadedBlacklist.stream().map(Enum::name).collect(Collectors.toList()));
            changedFlag.set(true);
        } else {
            List<String> stringList = config.getStringList(path);
            for (String s : stringList) {
                try {
                    Material mat = Material.matchMaterial(s.toUpperCase());
                    if (mat != null) {
                        loadedBlacklist.add(mat);
                    } else {
                        getLogger().warning("Config Warning: Invalid material '" + s + "' in blacklist.");
                    }
                } catch (Exception e) {
                    getLogger().warning("Config Warning: Error parsing material '" + s + "' in blacklist: " + e.getMessage());
                }
            }
        }
        return loadedBlacklist;
    }

    private void logLoadedConfig() {
         log(String.format("Config loaded: Chance=%.2f%%, Messages=%b, RequirePerm=%b, Logging=%b, PreventNBT=%b, BlacklistSize=%d",
               dupeChance, enableMessages, requirePermission, logSuccessfulDuplications, preventNbtDuplication, getBlacklistSize())); // Use getter for blacklist size
    }

    // --- Event Handling ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        // Ensure the damaged entity is an ItemFrame and the damager is a Player
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return;
        }

        // Basic validity checks for the frame
        if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) {
            return;
        }

        ItemStack frameItem = frame.getItem();
        // Allow breaking empty frames without triggering dupe logic
        if (frameItem == null || frameItem.getType().isAir()) {
            return;
        }

        // Check blacklist and NBT settings
        if (isBlacklisted(frameItem) || (preventNbtDuplication && hasNbtData(frameItem))) {
            if (enableMessages) {
                player.sendMessage(MSG_CANNOT_DUPE_ITEM);
            }
            return;
        }

        // Handle permission check or proceed directly
        if (requirePermission) {
            checkPermissionAndAttemptDupe(player, frame, frameItem);
        } else {
            attemptDuplication(player, frame, frameItem);
        }
    }

    private void checkPermissionAndAttemptDupe(Player player, ItemFrame frame, ItemStack frameItem) {
        permissionManager.hasPermissionLuckPerms(player, "dupes.use", hasPermission -> {
            // This callback runs on the main thread (handled by PermissionManager)
            if (hasPermission) {
                attemptDuplication(player, frame, frameItem);
            } else {
                if (enableMessages) {
                    player.sendMessage(MSG_NO_PERMISSION_DUPE);
                }
                 // Optional: Log permission denial
                 // log(String.format("Player %s lacks 'dupes.use' permission.", player.getName()));
            }
        });
    }

    private void attemptDuplication(Player player, ItemFrame frame, ItemStack frameItem) {
        // Run the duplication logic on the main server thread to ensure API safety
        getServer().getScheduler().runTask(this, () -> {
            try {
                // Check dupe chance
                if (ThreadLocalRandom.current().nextDouble() * 100.0 > dupeChance) {
                    // Optional: Send failure message if configured
                    return; // Chance failed
                }

                // Re-validate frame just before dropping item
                if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) {
                    getLogger().warning("ItemFrame became invalid/unloaded before item drop for player " + player.getName());
                    return;
                }

                Location dropLoc = frame.getLocation();
                ItemStack duplicatedItem = frameItem.clone();

                // Sanity check: Ensure we're not dropping AIR
                if (duplicatedItem.getType().isAir()) {
                    getLogger().warning("Attempted to duplicate AIR, skipping drop for player " + player.getName());
                    return;
                }

                // Drop the duplicated item
                dropLoc.getWorld().dropItemNaturally(dropLoc, duplicatedItem);

                // Send message and log if applicable
                handleDuplicationSuccess(player, duplicatedItem, dropLoc);

            } catch (Exception e) {
                getLogger().severe("An error occurred during item duplication drop task: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void handleDuplicationSuccess(Player player, ItemStack duplicatedItem, Location dropLoc) {
         String playerName = (player != null && player.isOnline()) ? player.getName() : "(Offline Player?)";

         if (player != null && player.isOnline() && enableMessages) {
            player.sendMessage(MSG_DUPE_SUCCESS);
         }

         if (logSuccessfulDuplications) {
            logDuplication(playerName, duplicatedItem.getType().toString(), dropLoc);
         }
    }


    // --- Utility Methods ---

    private void logDuplication(String playerName, String itemType, Location location) {
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

    private boolean isBlacklisted(ItemStack item) {
        return item != null && blacklist != null && !blacklist.isEmpty() && blacklist.contains(item.getType());
    }

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
                || meta.hasEnchants()
                || !meta.getPersistentDataContainer().isEmpty());
    }

    // --- Command Handling ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("dupe")) {
            return false;
        }

        if (!sender.hasPermission("dupes.admin")) {
            sender.sendMessage(MSG_NO_PERMISSION);
            return true;
        }

        String subCommand = (args.length > 0) ? args[0].toLowerCase() : "help";

        switch (subCommand) {
            case "reload":
                handleReloadCommand(sender);
                return true;
            case "help":
                sendHelpMessage(sender);
                return true;
            default:
                sender.sendMessage(MSG_UNKNOWN_SUBCOMMAND);
                sendHelpMessage(sender);
                return true;
        }
    }

    private void handleReloadCommand(CommandSender sender) {
         if (loadConfigValues()) {
             sender.sendMessage(MSG_RELOAD_SUCCESS);
             log("Configuration reloaded by " + sender.getName());
         } else {
             sender.sendMessage(MSG_RELOAD_FAIL);
         }
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(HELP_HEADER);
        sender.sendMessage(HELP_RELOAD);
        sender.sendMessage(HELP_HELP);
    }

    // --- Getter methods for MetricsManager and internal use ---

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