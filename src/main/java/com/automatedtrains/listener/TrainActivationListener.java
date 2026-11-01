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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A freshly coupled (or freshly loaded) train sits idle - {@link Train#automationEnabled}
 * starts false - until a player deliberately starts it by sneak-right-clicking the lead
 * cart twice in quick succession with an empty hand.
 *
 * <p>An earlier version also started the train from any physical player-cart collision
 * (a "give it a push" mechanic), but that turned out to fire far too easily: simply
 * standing close enough to a cart to sneak-right-click it (which coupling itself requires)
 * can overlap its hitbox, so trains were auto-starting mid-coupling, before a direction had
 * even been chosen - which looked exactly like "moves off in a random direction on its
 * own." Removed in favor of the one deliberate, unambiguous trigger.
 */
public class TrainActivationListener implements Listener {

    private static final long DOUBLE_CLICK_WINDOW_MS = 500L;

    private final TrainManager trainManager;
    private final Keys keys;
    private final Map<UUID, PendingClick> pendingClicks = new HashMap<>();

    public TrainActivationListener(TrainManager trainManager, Keys keys) {
        this.trainManager = trainManager;
        this.keys = keys;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!(event.getRightClicked() instanceof Minecart clicked)) return;
        if (!player.isSneaking()) return;
        if (player.getInventory().getItemInMainHand().getType() != Material.AIR) return; // chain/map/sign clicks are handled elsewhere

        Train train = trainManager.findTrainForCart(clicked.getUniqueId());
        if (train == null) return;

        event.setCancelled(true);

        if (train.automationEnabled) {
            player.sendMessage(Component.text("This train is already moving.", NamedTextColor.YELLOW));
            return;
        }
        if (!clicked.getUniqueId().equals(train.getLeadCartId())) {
            player.sendMessage(Component.text("Error: Double-click the lead (front) cart to start this train!", NamedTextColor.RED));
            return;
        }
        if (!PermissionUtil.hasAccess(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }

        PendingClick pending = pendingClicks.get(player.getUniqueId());
        if (pending == null || pending.isExpired() || !pending.cartId.equals(clicked.getUniqueId())) {
            pendingClicks.put(player.getUniqueId(), new PendingClick(clicked.getUniqueId()));
            return;
        }

        pendingClicks.remove(player.getUniqueId());
        activate(train, clicked);
        player.sendMessage(Component.text("Train started!", NamedTextColor.GREEN));
    }

    private void activate(Train train, Minecart lead) {
        train.automationEnabled = true;
        // Deliberately NOT touching train.lastDirection here: a minecart's entity yaw isn't a
        // meaningful "which way am I facing" signal (see AttachmentListener's javadoc for why),
        // and overwriting it would silently discard whatever /train move forward|back had set
        // before the train was started.
        train.saveTo(lead, keys);
    }

    private static final class PendingClick {
        final UUID cartId;
        final long createdAt;

        PendingClick(UUID cartId) {
            this.cartId = cartId;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > DOUBLE_CLICK_WINDOW_MS;
        }
    }
}
