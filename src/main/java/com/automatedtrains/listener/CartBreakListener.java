package com.automatedtrains.listener;

import com.automatedtrains.train.AttachmentManager;
import com.automatedtrains.train.ChunkTicketManager;
import com.automatedtrains.train.Train;
import com.automatedtrains.train.TrainManager;
import com.automatedtrains.util.Keys;
import com.automatedtrains.util.PermissionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cleans up after a coupled minecart is destroyed: its attached cosmetics are removed
 * instantly (no ghost item frames/displays left floating), and the train it belonged to
 * either loses that member or, if the destroyed cart was the lead, hands lead status
 * (and all persisted train data) to the next cart in line.
 */
public class CartBreakListener implements Listener {

    private final TrainManager trainManager;
    private final AttachmentManager attachmentManager;
    private final ChunkTicketManager chunkTicketManager;
    private final Keys keys;

    public CartBreakListener(TrainManager trainManager, AttachmentManager attachmentManager,
                              ChunkTicketManager chunkTicketManager, Keys keys) {
        this.trainManager = trainManager;
        this.attachmentManager = attachmentManager;
        this.chunkTicketManager = chunkTicketManager;
        this.keys = keys;
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;

        Train train = trainManager.findTrainForCart(cart.getUniqueId());

        if (train != null && event.getAttacker() instanceof Player attacker && !PermissionUtil.hasAccess(attacker, train)) {
            event.setCancelled(true);
            attacker.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            return;
        }

        attachmentManager.removeAll(cart.getUniqueId(), true);

        if (train == null) return;

        if (cart.getUniqueId().equals(train.getLeadCartId())) {
            handleLeadDestroyed(train, cart);
        } else {
            train.getMemberIds().remove(cart.getUniqueId());
            Minecart lead = trainManager.getEntity(train.getLeadCartId());
            if (lead != null) {
                train.saveTo(lead, keys);
            }
            trainManager.reindexMembers(train);
        }
    }

    private void handleLeadDestroyed(Train train, Minecart destroyedLead) {
        chunkTicketManager.releaseAll(train);
        trainManager.unregister(train);

        List<UUID> remaining = new ArrayList<>(train.getMemberIds());
        remaining.remove(destroyedLead.getUniqueId());

        if (remaining.isEmpty()) {
            return; // last cart of the train just broke - nothing left to reconstruct
        }

        UUID newLeadId = remaining.get(0);
        Minecart newLead = trainManager.getEntity(newLeadId);

        Train newTrain = new Train(newLeadId, train.getOwner());
        newTrain.getMemberIds().clear();
        newTrain.getMemberIds().addAll(remaining);
        newTrain.getTrusted().addAll(train.getTrusted());
        newTrain.setDestination(train.getDestination());
        newTrain.lastDirection = train.lastDirection;
        newTrain.automationEnabled = train.automationEnabled; // don't force a re-start just because the old lead broke

        trainManager.register(newTrain);

        if (newLead != null) {
            newTrain.saveTo(newLead, keys);
            for (UUID memberId : remaining) {
                Minecart member = trainManager.getEntity(memberId);
                if (member != null) {
                    Train.stampMember(member, newLeadId, keys);
                }
            }
        }
    }
}
