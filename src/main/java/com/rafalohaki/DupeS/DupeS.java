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

    private double dupeChance;
    private boolean enableMessages;
    private boolean requirePermission;
    private boolean logSuccessfulDuplications;
    private boolean preventNbtDuplication;
    private Set<Material> blacklist;
    private PermissionManager permissionManager;
    // Use DateTimeFormatter for thread safety
    private final DateTimeFormatter logTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms API not found. DupeS requires LuckPerms. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LuckPerms luckPerms = provider.getProvider();
        log("Successfully hooked into LuckPerms.");

        permissionManager = new PermissionManager(this, luckPerms);

        saveDefaultConfig();
        if (!loadConfigValues()) {
            getLogger().severe("Failed to load configuration. Please check config.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        log("DupeS v" + this.getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        log("DupeS disabled.");
    }

    private boolean loadConfigValues() {
        // Config loading logic remains the same as your last provided version
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
            boolean changed = false;

            // Dupe Chance (Validation logic is fine)
            if (!config.isDouble("dupe-chance") && !config.isInt("dupe-chance")) {
                 getLogger().warning("Config Error: 'dupe-chance' is not a valid number. Using default 1.0.");
                 dupeChance = 1.0; config.set("dupe-chance", dupeChance); changed = true;
            } else {
                dupeChance = config.getDouble("dupe-chance", 1.0);
                if (dupeChance < 0.0) { getLogger().warning("Config Warning: 'dupe-chance' < 0. Setting to 0.0."); dupeChance = 0.0; config.set("dupe-chance", dupeChance); changed = true; }
                else if (dupeChance > 100.0) { getLogger().warning("Config Warning: 'dupe-chance' > 100. Setting to 100.0."); dupeChance = 100.0; config.set("dupe-chance", dupeChance); changed = true; }
            }
            // Enable Messages (Validation logic is fine)
             if (!config.isBoolean("enable-messages")) { getLogger().warning("Config Error: 'enable-messages' is not true/false. Using default true."); enableMessages = true; config.set("enable-messages", enableMessages); changed = true; }
             else { enableMessages = config.getBoolean("enable-messages", true); }
            // Require Permission (Validation logic is fine)
             if (!config.isBoolean("require-permission")) { getLogger().warning("Config Error: 'require-permission' is not true/false. Using default true."); requirePermission = true; config.set("require-permission", requirePermission); changed = true; }
             else { requirePermission = config.getBoolean("require-permission", true); }
            // Log Successful Duplications (Validation logic is fine)
             if (!config.isBoolean("log-successful-duplications")) { getLogger().warning("Config Error: 'log-successful-duplications' is not true/false. Using default true."); logSuccessfulDuplications = true; config.set("log-successful-duplications", logSuccessfulDuplications); changed = true; }
             else { logSuccessfulDuplications = config.getBoolean("log-successful-duplications", true); }
            // Prevent NBT Duplication (Validation logic is fine)
             if (!config.isBoolean("prevent-nbt-duplication")) { getLogger().warning("Config Error: 'prevent-nbt-duplication' is not true/false. Using default false."); preventNbtDuplication = false; config.set("prevent-nbt-duplication", preventNbtDuplication); changed = true; }
             else { preventNbtDuplication = config.getBoolean("prevent-nbt-duplication", false); }
            // Blacklist (Validation logic is fine)
            if (!config.isList("blacklisted-items")) {
                 getLogger().warning("Config Error: 'blacklisted-items' is not a list. Using default blacklist.");
                 blacklist = new HashSet<>(Set.of(Material.BEDROCK, Material.COMMAND_BLOCK, Material.STRUCTURE_BLOCK));
                 config.set("blacklisted-items", blacklist.stream().map(Enum::name).collect(Collectors.toList())); changed = true;
            } else {
                List<String> stringList = config.getStringList("blacklisted-items"); blacklist = new HashSet<>();
                for (String s : stringList) { try { Material mat = Material.matchMaterial(s.toUpperCase()); if (mat != null) blacklist.add(mat); else getLogger().warning("Config Warning: Invalid material '" + s + "' in blacklist."); }
                catch (Exception e) { getLogger().warning("Config Warning: Error parsing material '" + s + "' in blacklist."); } }
            }

            if (changed) { saveConfig(); log("Configuration values corrected/updated and saved."); }
            log(String.format("Config loaded: Chance=%.2f%%, Messages=%b, RequirePerm=%b, Logging=%b, PreventNBT=%b, BlacklistSize=%d", dupeChance, enableMessages, requirePermission, logSuccessfulDuplications, preventNbtDuplication, blacklist.size()));
            return true;
        } catch (Exception e) { getLogger().severe("FATAL: Could not load config.yml: " + e.getMessage()); e.printStackTrace(); return false; }
    }


    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !(event.getDamager() instanceof Player player)) {
            return;
        }

        if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) {
             return;
        }

        ItemStack frameItem = frame.getItem();
        // Add explicit null check for clarity alongside isAir()
        if (frameItem == null || frameItem.getType().isAir()) {
            return; // Allow breaking empty frames
        }

        if (isBlacklisted(frameItem) || (preventNbtDuplication && hasNbtData(frameItem))) {
            if (enableMessages) {
                player.sendMessage(MSG_CANNOT_DUPE_ITEM); // Use cached component
            }
            return;
        }

        if (requirePermission) {
            permissionManager.hasPermissionLuckPerms(player, "dupes.use", hasPermission -> {
                if (hasPermission) {
                    attemptDuplication(player, frame, frameItem);
                } else {
                    if (enableMessages) {
                        player.sendMessage(MSG_NO_PERMISSION_DUPE); // Use cached component
                    }
                    log(String.format("Player %s lacks permission 'dupes.use' for duplication.", player.getName()));
                }
            });
        } else {
            attemptDuplication(player, frame, frameItem);
        }
    }

    private void attemptDuplication(Player player, ItemFrame frame, ItemStack frameItem) {
        getServer().getScheduler().runTask(this, () -> {
            // Logic inside runTask remains the same as your last provided version
            try {
                 if (ThreadLocalRandom.current().nextDouble() * 100.0 > dupeChance) { return; } // Chance check
                 if (!frame.isValid() || frame.getLocation().getWorld() == null || !frame.getLocation().isChunkLoaded()) { getLogger().warning("ItemFrame became invalid/unloaded before item drop for player " + player.getName()); return; } // Validity check
                 Location dropLoc = frame.getLocation();
                 ItemStack duplicatedItem = frameItem.clone();
                 if (duplicatedItem.getType().isAir()) { getLogger().warning("Attempted to duplicate AIR, skipping drop."); return; } // Sanity check
                 dropLoc.getWorld().dropItemNaturally(dropLoc, duplicatedItem); // Drop item
                 if (player != null && player.isOnline()) { if (enableMessages) { player.sendMessage(MSG_DUPE_SUCCESS); } if (logSuccessfulDuplications) { logDuplication(player.getName(), duplicatedItem.getType().toString(), dropLoc); } } // Message/Log Online
                 else if (logSuccessfulDuplications) { logDuplication("(Offline Player?)", duplicatedItem.getType().toString(), dropLoc); } // Log Offline
            } catch (Exception e) { getLogger().severe("An error occurred during item duplication drop task: " + e.getMessage()); e.printStackTrace(); }
        });
    }

    private void logDuplication(String playerName, String itemType, Location location) {
         // Use DateTimeFormatter
         String timestamp = logTimestampFormat.format(LocalDateTime.now());
         String worldName = location.getWorld() != null ? location.getWorld().getName() : "UnknownWorld";
         log(String.format("[%s] Player %s duplicated %s at [%s, %d, %d, %d] (Chance: %.2f%%)",
                timestamp, playerName, itemType, worldName,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), dupeChance));
    }

    protected void log(String message) {
        getLogger().info(message);
    }

    private boolean isBlacklisted(ItemStack item) {
        return item != null && blacklist.contains(item.getType());
    }

    private boolean hasNbtData(ItemStack item) {
        // hasNbtData logic remains the same as your last provided version
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() || meta.hasLore() || meta.hasCustomModelData() || meta.hasAttributeModifiers() || !meta.getPersistentDataContainer().isEmpty();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("dupe")) {
            return false;
        }
        if (!sender.hasPermission("dupes.admin")) {
            sender.sendMessage(MSG_NO_PERMISSION); // Use cached component
            return true;
        }

        String subCommand = (args.length > 0) ? args[0].toLowerCase() : "help"; // Default to help

        // Use Switch Expression
        switch (subCommand) {
            case "reload" -> {
                if (loadConfigValues()) {
                    sender.sendMessage(MSG_RELOAD_SUCCESS); // Use cached component
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
                return true; // Indicate command was handled (with error msg)
            }
        }
        // Note: The return true/false from onCommand typically indicates if the usage message should be shown.
        // Returning true here prevents the default usage message from plugin.yml from showing.
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(HELP_HEADER);   // Use cached component
        sender.sendMessage(HELP_RELOAD);  // Use cached component
        sender.sendMessage(HELP_HELP);    // Use cached component
    }
}