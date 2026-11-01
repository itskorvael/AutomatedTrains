package com.automatedtrains.train;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks cosmetic entities (item frames / headboard signs) attached to minecarts.
 * Attachments are deliberately NEVER added as vehicle passengers of the cart - vanilla
 * minecarts only ever allow a single passenger slot, so mounting anything there would
 * block a real player from riding. Instead each attachment is a free-standing entity
 * that the tick task re-positions next to its owning cart every tick, which keeps the
 * passenger seat completely free.
 *
 * <p>An attachment sticks to wherever the player right-clicked, not a fixed FRONT/LEFT/
 * RIGHT slot - {@link #localOffset} is a unit vector in the cart's own (forward, right)
 * plane captured at attach time, so the tick task can keep re-deriving the correct world
 * position as the cart's rail-facing direction changes on curves.
 */
public class AttachmentManager {

    public enum Kind { MAP, SIGN }

    public static final class Attachment {
        public final UUID id;
        public final UUID companionId; // invisible Interaction entity for click-to-edit; null for maps
        public final Kind kind;
        public final Vector localOffset;

        Attachment(UUID id, UUID companionId, Kind kind, Vector localOffset) {
            this.id = id;
            this.companionId = companionId;
            this.kind = kind;
            this.localOffset = localOffset;
        }
    }

    private final Map<UUID, List<Attachment>> attachmentsByCart = new HashMap<>();
    private final Map<UUID, UUID> cartByAttachment = new HashMap<>(); // primary or companion id -> cart id
    private final Map<UUID, UUID> displayByCompanion = new HashMap<>(); // companion (Interaction) id -> primary (display) id

    public void register(UUID cartId, UUID attachmentId, UUID companionId, Kind kind, Vector localOffset) {
        attachmentsByCart.computeIfAbsent(cartId, k -> new ArrayList<>())
                .add(new Attachment(attachmentId, companionId, kind, localOffset.clone()));
        cartByAttachment.put(attachmentId, cartId);
        if (companionId != null) {
            cartByAttachment.put(companionId, cartId);
            displayByCompanion.put(companionId, attachmentId);
        }
    }

    public List<Attachment> getAttachments(UUID cartId) {
        return attachmentsByCart.getOrDefault(cartId, Collections.emptyList());
    }

    public UUID getOwningCart(UUID anyEntityId) {
        return cartByAttachment.get(anyEntityId);
    }

    /** Given a companion Interaction entity's id, finds the sign display it belongs to. */
    public UUID getDisplayForCompanion(UUID companionId) {
        return displayByCompanion.get(companionId);
    }

    /** Removes bookkeeping for every attachment on a cart, optionally despawning the entities too. */
    public void removeAll(UUID cartId, boolean removeEntities) {
        List<Attachment> list = attachmentsByCart.remove(cartId);
        if (list == null) return;
        for (Attachment attachment : list) {
            cartByAttachment.remove(attachment.id);
            if (attachment.companionId != null) {
                cartByAttachment.remove(attachment.companionId);
                displayByCompanion.remove(attachment.companionId);
            }
            if (removeEntities) {
                remove(attachment.id);
                remove(attachment.companionId);
            }
        }
    }

    private void remove(UUID id) {
        if (id == null) return;
        Entity entity = Bukkit.getEntity(id);
        if (entity != null) {
            entity.remove();
        }
    }
}
