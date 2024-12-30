package com.rafalohaki.dupe;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

import java.util.Optional;

public class PermissionManager {

    private final DupeS plugin;
    private final LuckPerms luckPerms;

    public PermissionManager(DupeS plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    public void hasPermissionLuckPerms(Player player, String permission, PermissionCallback callback) {
        if (luckPerms == null) {
            callback.onResult(player.hasPermission(permission));
            return;
        }

        luckPerms.getUserManager().loadUser(player.getUniqueId()).thenAcceptAsync(user -> {
            boolean hasPermission = Optional.ofNullable(user)
                    .map(u -> ((net.luckperms.api.model.user.User) u).getCachedData().getPermissionData().checkPermission(permission).asBoolean())
                    .orElseGet(() -> {
                        plugin.log("LuckPerms user data failed to load. Using Bukkit fallback");
                        return player.hasPermission(permission);
                    });
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                callback.onResult(hasPermission);
            });
        });
    }

    public interface PermissionCallback {
        void onResult(boolean hasPermission);
    }
}