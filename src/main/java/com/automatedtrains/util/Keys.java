package com.automatedtrains.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Central registry of every {@link NamespacedKey} the plugin reads/writes on
 * entity PersistentDataContainers. Constructed once from the plugin instance
 * so the namespace always matches plugin.yml's name.
 */
public final class Keys {

    public final NamespacedKey trainMembers;   // stored on lead cart: "uuid,uuid,uuid" ordered lead->tail
    public final NamespacedKey trainOwner;     // stored on lead cart: owner uuid string
    public final NamespacedKey trainTrusted;   // stored on lead cart: "uuid,uuid" trusted list
    public final NamespacedKey trainDestination; // stored on lead cart: destination string
    public final NamespacedKey trainActive;    // stored on lead cart: 1 = automation started, 0 = idle since coupling/last restart
    public final NamespacedKey trainDirection; // stored on lead cart: "x,y,z" of the train's current/last travel direction
    public final NamespacedKey trainLead;      // stored on EVERY member cart (including lead): lead cart uuid string

    public final NamespacedKey attachmentOwnerCart; // stored on cosmetic entities: owning cart uuid
    public final NamespacedKey attachmentSide;       // stored on cosmetic entities: LEFT/RIGHT/FRONT

    public Keys(Plugin plugin) {
        this.trainMembers = new NamespacedKey(plugin, "train_members");
        this.trainOwner = new NamespacedKey(plugin, "train_owner");
        this.trainTrusted = new NamespacedKey(plugin, "train_trusted");
        this.trainDestination = new NamespacedKey(plugin, "train_destination");
        this.trainActive = new NamespacedKey(plugin, "train_active");
        this.trainDirection = new NamespacedKey(plugin, "train_direction");
        this.trainLead = new NamespacedKey(plugin, "train_lead");
        this.attachmentOwnerCart = new NamespacedKey(plugin, "attachment_owner_cart");
        this.attachmentSide = new NamespacedKey(plugin, "attachment_side");
    }
}
