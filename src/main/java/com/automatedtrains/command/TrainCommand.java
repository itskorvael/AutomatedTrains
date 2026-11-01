package com.automatedtrains.command;

import com.automatedtrains.train.ChunkTicketManager;
import com.automatedtrains.train.Train;
import com.automatedtrains.train.TrainManager;
import com.automatedtrains.train.TrainTickTask;
import com.automatedtrains.util.Keys;
import com.automatedtrains.util.PermissionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements every {@code /train ...} subcommand plus the strict entity-targeting
 * hierarchy used to figure out which train a player means when they don't name one
 * explicitly: riding it beats looking at it, which beats simply standing near it.
 */
public class TrainCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("info", "setdest", "trust", "untrust", "clear", "move", "stop");
    private static final List<String> MOVE_DIRECTIONS = List.of("forward", "back");
    private static final double RAYTRACE_DISTANCE = 6.0;
    private static final double NEARBY_RADIUS = 3.0;

    private final TrainManager trainManager;
    private final ChunkTicketManager chunkTicketManager;
    private final Keys keys;
    private final TrainTickTask tickTask;

    public TrainCommand(TrainManager trainManager, ChunkTicketManager chunkTicketManager, Keys keys, TrainTickTask tickTask) {
        this.trainManager = trainManager;
        this.chunkTicketManager = chunkTicketManager;
        this.keys = keys;
        this.tickTask = tickTask;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /train <info|setdest|trust|untrust|clear|move|stop>", NamedTextColor.YELLOW));
            return true;
        }

        Minecart targetCart = findTargetCart(player);
        if (targetCart == null) {
            player.sendMessage(Component.text(
                    "Error: You must be looking at, sitting in, or standing right next to a train to use this command!",
                    NamedTextColor.RED));
            return true;
        }

        Train train = trainManager.findTrainForCart(targetCart.getUniqueId());
        if (train == null) {
            player.sendMessage(Component.text("Error: That minecart is not currently part of a train.", NamedTextColor.RED));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> handleInfo(player, train);
            case "setdest" -> handleSetDest(player, train, args);
            case "trust" -> handleTrust(player, train, args);
            case "untrust" -> handleUntrust(player, train, args);
            case "clear" -> handleClear(player, train);
            case "move" -> handleMove(player, train, args);
            case "stop" -> handleStop(player, train);
            default -> player.sendMessage(Component.text("Unknown subcommand. Try: " + String.join(", ", SUBCOMMANDS), NamedTextColor.RED));
        }
        return true;
    }

    // ----------------------------------------------------------------------------------
    // Subcommands
    // ----------------------------------------------------------------------------------

    private void handleInfo(Player player, Train train) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(train.getOwner());
        String ownerName = owner.getName() != null ? owner.getName() : train.getOwner().toString();

        String trustedNames = train.getTrusted().isEmpty()
                ? "none"
                : train.getTrusted().stream()
                    .map(Bukkit::getOfflinePlayer)
                    .map(p -> p.getName() != null ? p.getName() : p.getUniqueId().toString())
                    .collect(Collectors.joining(", "));

        String speed;
        if (!train.automationEnabled) {
            speed = "0.0 (not started - sneak-double-right-click the lead cart to start it)";
        } else if (train.paused) {
            speed = "0.0 (stopped at station)";
        } else {
            speed = tickTask.speed + " blocks/tick";
        }

        player.sendMessage(Component.text("=== Train Info ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Speed: ", NamedTextColor.GRAY).append(Component.text(speed, NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Carts: ", NamedTextColor.GRAY).append(Component.text(train.size(), NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Destination: ", NamedTextColor.GRAY).append(Component.text(train.getDestination(), NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(ownerName, NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Trusted: ", NamedTextColor.GRAY).append(Component.text(trustedNames, NamedTextColor.WHITE)));
    }

    private void handleSetDest(Player player, Train train, String[] args) {
        if (!PermissionUtil.hasAccess(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /train setdest <name>", NamedTextColor.YELLOW));
            return;
        }
        String destination = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        train.setDestination(destination);
        persist(train);
        player.sendMessage(Component.text("Destination set to '" + destination + "'.", NamedTextColor.GREEN));
    }

    private void handleTrust(Player player, Train train, String[] args) {
        if (!PermissionUtil.canManageTrust(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /train trust <player>", NamedTextColor.YELLOW));
            return;
        }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null || target.getUniqueId() == null) {
            player.sendMessage(Component.text("Error: Unknown player '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        train.getTrusted().add(target.getUniqueId());
        persist(train);
        player.sendMessage(Component.text("Trusted " + args[1] + " on this train.", NamedTextColor.GREEN));
    }

    private void handleUntrust(Player player, Train train, String[] args) {
        if (!PermissionUtil.canManageTrust(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /train untrust <player>", NamedTextColor.YELLOW));
            return;
        }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null || !train.getTrusted().remove(target.getUniqueId())) {
            player.sendMessage(Component.text("Error: " + args[1] + " was not trusted on this train.", NamedTextColor.RED));
            return;
        }
        persist(train);
        player.sendMessage(Component.text("Removed " + args[1] + " from this train's trusted list.", NamedTextColor.GREEN));
    }

    /**
     * The only deliberate way to stop a moving train (besides it running out of track) - halts
     * every cart immediately and turns automation off, exactly like it was before it was ever
     * started, so it stays stopped until sneak-double-right-clicked again.
     */
    private void handleStop(Player player, Train train) {
        if (!PermissionUtil.hasAccess(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }
        if (!train.automationEnabled) {
            player.sendMessage(Component.text("This train isn't moving.", NamedTextColor.YELLOW));
            return;
        }

        train.automationEnabled = false;
        train.paused = false;
        train.stationCountdownTicks = 0;

        for (UUID memberId : train.getMemberIds()) {
            Minecart cart = trainManager.getEntity(memberId);
            if (cart != null) {
                cart.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            }
        }

        Minecart lead = trainManager.getEntity(train.getLeadCartId());
        if (lead != null) {
            train.saveTo(lead, keys); // persist immediately so it stays stopped across a restart too
        }

        player.sendMessage(Component.text("Train stopped. Sneak-double-right-click the lead cart to start it again.", NamedTextColor.GREEN));
    }

    private void handleMove(Player player, Train train, String[] args) {
        if (!PermissionUtil.hasAccess(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }
        if (args.length < 2 || !MOVE_DIRECTIONS.contains(args[1].toLowerCase(Locale.ROOT))) {
            player.sendMessage(Component.text("Usage: /train move <forward|back>", NamedTextColor.YELLOW));
            return;
        }

        boolean wantReversed = args[1].equalsIgnoreCase("back");
        if (wantReversed == train.reversed) {
            player.sendMessage(Component.text("This train is already set to move " + args[1].toLowerCase(Locale.ROOT) + ".", NamedTextColor.YELLOW));
            return;
        }

        train.lastDirection = train.lastDirection.multiply(-1);
        train.reversed = wantReversed;

        Minecart lead = trainManager.getEntity(train.getLeadCartId());
        if (lead != null) {
            train.saveDirectionTo(lead, keys); // persist immediately, don't wait for the next moving tick
        }

        player.sendMessage(Component.text("Train will now move " + args[1].toLowerCase(Locale.ROOT) + ".", NamedTextColor.GREEN));
    }

    private void handleClear(Player player, Train train) {
        if (!PermissionUtil.canManageTrust(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }
        chunkTicketManager.releaseAll(train);
        trainManager.unregister(train);

        for (UUID memberId : train.getMemberIds()) {
            Minecart cart = trainManager.getEntity(memberId);
            if (cart != null) {
                cart.getPersistentDataContainer().remove(keys.trainLead);
                cart.getPersistentDataContainer().remove(keys.trainMembers);
                cart.getPersistentDataContainer().remove(keys.trainOwner);
                cart.getPersistentDataContainer().remove(keys.trainTrusted);
                cart.getPersistentDataContainer().remove(keys.trainDestination);
            }
        }
        player.sendMessage(Component.text("Train unlinked and its data wiped.", NamedTextColor.GREEN));
    }

    private void persist(Train train) {
        Minecart lead = trainManager.getEntity(train.getLeadCartId());
        if (lead != null) {
            train.saveTo(lead, keys);
        }
    }

    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() || offline.isOnline() ? offline : null;
    }

    // ----------------------------------------------------------------------------------
    // Targeting hierarchy
    // ----------------------------------------------------------------------------------

    private Minecart findTargetCart(Player player) {
        if (player.getVehicle() instanceof Minecart cart) {
            return cart;
        }

        RayTraceResult ray = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                RAYTRACE_DISTANCE,
                entity -> entity instanceof Minecart);
        if (ray != null && ray.getHitEntity() instanceof Minecart cart) {
            return cart;
        }

        Minecart closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(NEARBY_RADIUS, NEARBY_RADIUS, NEARBY_RADIUS)) {
            if (entity instanceof Minecart cart) {
                double distSq = cart.getLocation().distanceSquared(player.getLocation());
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = cart;
                }
            }
        }
        return closest;
    }

    // ----------------------------------------------------------------------------------
    // Tab completion
    // ----------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("move")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return MOVE_DIRECTIONS.stream().filter(d -> d.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
