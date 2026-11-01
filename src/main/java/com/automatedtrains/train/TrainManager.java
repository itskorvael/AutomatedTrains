package com.automatedtrains.train;

import com.automatedtrains.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime registry of every currently-active train. Trains are discovered lazily:
 * the moment a lead cart's chunk loads (see the chunk-load listener), its PDC is
 * parsed back into a {@link Train} and registered here. From that point on the
 * chunk ticket system keeps its chunks force-loaded so it never falls back out
 * of this registry, which is what lets automation genuinely run "forever".
 */
public class TrainManager {

    private final Keys keys;
    private final Map<UUID, Train> trainsByLeadId = new HashMap<>();
    private final Map<UUID, UUID> cartToLeadIndex = new HashMap<>();

    public TrainManager(Keys keys) {
        this.keys = keys;
    }

    public Keys getKeys() {
        return keys;
    }

    public Collection<Train> getAllTrains() {
        return trainsByLeadId.values();
    }

    public Train getTrainByLead(UUID leadId) {
        return trainsByLeadId.get(leadId);
    }

    public Train findTrainForCart(UUID cartId) {
        UUID leadId = cartToLeadIndex.get(cartId);
        if (leadId == null) return null;
        return trainsByLeadId.get(leadId);
    }

    public boolean isRegistered(UUID leadId) {
        return trainsByLeadId.containsKey(leadId);
    }

    public void register(Train train) {
        trainsByLeadId.put(train.getLeadCartId(), train);
        for (UUID member : train.getMemberIds()) {
            cartToLeadIndex.put(member, train.getLeadCartId());
        }
    }

    public void unregister(Train train) {
        trainsByLeadId.remove(train.getLeadCartId());
        for (UUID member : train.getMemberIds()) {
            cartToLeadIndex.remove(member);
        }
    }

    public void reindexMembers(Train train) {
        cartToLeadIndex.entrySet().removeIf(e -> e.getValue().equals(train.getLeadCartId()));
        for (UUID member : train.getMemberIds()) {
            cartToLeadIndex.put(member, train.getLeadCartId());
        }
    }

    /**
     * Called for every minecart found in a newly loaded chunk. If it is the lead cart of a
     * train (its own PDC carries full train data and train_lead == its own id) and that
     * train is not already registered, parses and registers it so the tick task and chunk
     * ticket system can take over. Safe to call redundantly.
     */
    public Train discoverFromCart(Minecart cart) {
        UUID leadPointer = readLeadPointer(cart);
        if (leadPointer == null || !leadPointer.equals(cart.getUniqueId())) {
            return null; // not a lead cart (either untracked, or a trailing member - lead handles registration)
        }
        if (isRegistered(leadPointer)) {
            return trainsByLeadId.get(leadPointer);
        }
        Train train = Train.loadFrom(cart, keys);
        if (train == null) return null;
        register(train);
        return train;
    }

    public UUID readLeadPointer(Minecart cart) {
        String raw = cart.getPersistentDataContainer().get(keys.trainLead, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Minecart getEntity(UUID id) {
        Entity entity = Bukkit.getEntity(id);
        if (entity instanceof Minecart cart) {
            return cart;
        }
        return null;
    }
}
