package com.automatedtrains.listener;

import com.automatedtrains.train.Train;
import com.automatedtrains.train.TrainManager;
import com.automatedtrains.util.Keys;
import com.automatedtrains.util.PermissionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the sneak-right-click-with-a-chain coupling flow: click one cart to select it,
 * then click a second (or third, fourth...) cart to link it onto the same train. Works
 * for both "start a brand new train" and "extend an existing train" - either way the
 * clicking player needs coupling permission, and extending an existing train additionally
 * requires the standard owner/trusted/admin access check.
 */
public class CouplingListener implements Listener {

    private static final long SELECTION_TIMEOUT_MS = 30_000L;

    private final TrainManager trainManager;
    private final Keys keys;
    private final Map<UUID, PendingSelection> pendingSelections = new HashMap<>();

    public CouplingListener(TrainManager trainManager, Keys keys) {
        this.trainManager = trainManager;
        this.keys = keys;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!(event.getRightClicked() instanceof Minecart clicked)) return;
        if (!player.isSneaking()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.IRON_CHAIN) return; // "Chain" was renamed IRON_CHAIN once copper chain variants were added

        if (!player.hasPermission("automatedtrains.couple")) {
            player.sendMessage(Component.text("Error: You do not have permission to couple trains!", NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        PendingSelection pending = pendingSelections.get(player.getUniqueId());

        if (pending == null || pending.isExpired() || pending.cartId.equals(clicked.getUniqueId())) {
            pendingSelections.put(player.getUniqueId(), new PendingSelection(clicked.getUniqueId()));
            player.sendMessage(Component.text("Selected minecart. Sneak-right-click another cart with a chain to couple them.", NamedTextColor.YELLOW));
            return;
        }

        Minecart first = trainManager.getEntity(pending.cartId);
        pendingSelections.remove(player.getUniqueId());
        if (first == null || !first.isValid()) {
            player.sendMessage(Component.text("Error: The first selected cart is no longer available.", NamedTextColor.RED));
            return;
        }

        if (first.getLocation().distanceSquared(clicked.getLocation()) > 100.0) {
            player.sendMessage(Component.text("Error: Those carts are too far apart to couple!", NamedTextColor.RED));
            return;
        }

        coupleCarts(first, clicked, player);
    }

    private void coupleCarts(Minecart first, Minecart second, Player player) {
        Train firstTrain = trainManager.findTrainForCart(first.getUniqueId());
        Train secondTrain = trainManager.findTrainForCart(second.getUniqueId());

        if (firstTrain != null && secondTrain != null) {
            if (firstTrain.getLeadCartId().equals(secondTrain.getLeadCartId())) {
                player.sendMessage(Component.text("Those carts are already coupled together.", NamedTextColor.YELLOW));
            } else {
                player.sendMessage(Component.text("Error: Both carts already belong to different trains - merging two trains isn't supported.", NamedTextColor.RED));
            }
            return;
        }

        // Whichever of the two is already part of a train is the one being extended - this
        // makes coupling order-independent: click the new cart first and an existing member
        // second (or the other way around), either works the same way.
        Train train;
        Minecart newCart;
        if (firstTrain != null) {
            train = firstTrain;
            newCart = second;
        } else if (secondTrain != null) {
            train = secondTrain;
            newCart = first;
        } else {
            // Neither cart is coupled yet: brand new train, "first" becomes the lead cart.
            train = new Train(first.getUniqueId(), player.getUniqueId());
            Train.stampMember(first, first.getUniqueId(), keys);
            trainManager.register(train);
            newCart = second;
        }

        if (!PermissionUtil.hasAccess(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }

        train.getMemberIds().add(newCart.getUniqueId());
        Train.stampMember(newCart, train.getLeadCartId(), keys);

        Minecart lead = trainManager.getEntity(train.getLeadCartId());
        if (lead != null) {
            train.saveTo(lead, keys);
        }
        trainManager.reindexMembers(train);

        // The chain isn't consumed - the same one can be used to couple as many carts as you like.
        player.sendMessage(Component.text("Coupled! This train now has " + train.size() + " cart(s).", NamedTextColor.GREEN));
    }

    private static final class PendingSelection {
        final UUID cartId;
        final long createdAt;

        PendingSelection(UUID cartId) {
            this.cartId = cartId;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > SELECTION_TIMEOUT_MS;
        }
    }
}
