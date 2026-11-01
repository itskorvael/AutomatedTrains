package com.automatedtrains.util;

import com.automatedtrains.train.Train;
import org.bukkit.entity.Player;

public final class PermissionUtil {

    public static final String ADMIN_NODE = "automatedtrains.admin";
    public static final String ACCESS_DENIED = "§cError: You do not have permission to modify this train!";

    private PermissionUtil() {
    }

    public static boolean isAdmin(Player player) {
        return player.hasPermission(ADMIN_NODE);
    }

    public static boolean hasAccess(Player player, Train train) {
        return train.hasAccess(player.getUniqueId(), isAdmin(player));
    }

    public static boolean canManageTrust(Player player, Train train) {
        return train.canManageTrust(player.getUniqueId(), isAdmin(player));
    }
}
