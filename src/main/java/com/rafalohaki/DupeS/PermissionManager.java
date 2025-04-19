package com.rafalohaki.DupeS;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PermissionManager {

    private final DupeS plugin;
    private final LuckPerms luckPerms;

    public PermissionManager(DupeS plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    @FunctionalInterface
    public interface PermissionCallback {
        void onResult(boolean hasPermission);
    }

    public void hasPermissionLuckPerms(Player player, String permission, PermissionCallback callback) {
        if (luckPerms == null) {
            plugin.log("Error: LuckPerms API is null during permission check for " + player.getName());
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.onResult(player.hasPermission(permission)));
            return;
        }

        UUID playerUUID = player.getUniqueId();

        CompletableFuture<User> userFuture = luckPerms.getUserManager().loadUser(playerUUID);

        userFuture.thenAcceptAsync(user -> {
            boolean hasPermission = Optional.ofNullable(user)
                    .map(u -> u.getCachedData().getPermissionData().checkPermission(permission).asBoolean())
                    .orElseGet(() -> {
                        plugin.log("LuckPerms user data not found for " + player.getName() + ". Using Bukkit fallback for '" + permission + "'.");
                        return player.hasPermission(permission);
                    });
            // Schedule callback back to main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.onResult(hasPermission));
        }).exceptionally(throwable -> {
            plugin.getLogger().severe("Failed to query LuckPerms permission for " + player.getName() + " (" + playerUUID + "): " + throwable.getMessage());
            // Log stack trace only if debug mode is enabled? For now, always log.
            throwable.printStackTrace();
            // Fallback on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                 plugin.log("Using Bukkit fallback check for '" + permission + "' due to LuckPerms exception for " + player.getName());
                 callback.onResult(player.hasPermission(permission));
            });
            return null;
        });
    }
}