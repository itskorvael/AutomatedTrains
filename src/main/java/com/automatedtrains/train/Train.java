package com.automatedtrains.train;

import com.automatedtrains.util.Keys;
import org.bukkit.Location;
import org.bukkit.entity.Minecart;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory representation of a coupled train. Persistent fields (member list,
 * owner, trusted list, destination) live on the lead cart's PersistentDataContainer
 * so they survive a server restart; everything else here (path history, station
 * state, chunk tickets) is runtime-only and gets rebuilt the moment the train
 * is (re)discovered.
 */
public class Train {

    private static final String DELIMITER = ",";

    /** Ordered lead -> tail. Index 0 is always the lead cart. */
    private final List<UUID> memberIds = new ArrayList<>();
    private UUID leadCartId;
    private UUID owner;
    private final Set<UUID> trusted = new LinkedHashSet<>();
    private String destination = "None";

    /**
     * Whether this train's automation loop is actually driving it. False for a freshly
     * coupled train (or a train loaded from a save where it was never started) - the
     * carts sit as plain, un-powered coupled minecarts until a player gives the lead
     * cart a push or sneak-double-right-clicks it, at which point this flips true and
     * stays true (persisted) until the train is unlinked.
     */
    public boolean automationEnabled = false;

    // ----- runtime-only automation state (never persisted) -----
    public boolean paused = false;
    /** True while this train is holding still because a stopped train is close ahead of it on the track. */
    public boolean holdingForTrain = false;
    /** Lead cart of the train this one is currently waiting for (while {@link #holdingForTrain}); lets two trains waiting on each other be spotted. */
    public UUID waitingOn = null;
    public int stationCountdownTicks = 0;
    public Location ignoredStationLocation = null;
    /** Station the train is currently stopped at, for the radio departure announcement (null = none). */
    public String announceStation = null;
    public String announceChannel = null;
    public Location announceLocation = null;
    /** Breadcrumb trail of the lead cart's recent positions, newest last, used to make trailing carts follow. */
    public final Deque<Location> pathHistory = new ArrayDeque<>();
    /** Chunk keys ("world:x:z") currently held with a plugin chunk ticket for this train. */
    public final Set<String> heldChunkTickets = new LinkedHashSet<>();
    /** The lead cart's most recent travel direction, used to resolve which way to go at rail junctions. */
    public Vector lastDirection = new Vector(0, 0, 1);
    /** Tracks whether {@code /train move back} has flipped this train's direction, so repeat calls to forward/back stay idempotent. */
    public boolean reversed = false;

    public Train(UUID leadCartId, UUID owner) {
        this.leadCartId = leadCartId;
        this.owner = owner;
        this.memberIds.add(leadCartId);
    }

    public UUID getLeadCartId() {
        return leadCartId;
    }

    public List<UUID> getMemberIds() {
        return memberIds;
    }

    public int size() {
        return memberIds.size();
    }

    public UUID getOwner() {
        return owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public Set<UUID> getTrusted() {
        return trusted;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public boolean hasAccess(UUID playerId, boolean isAdmin) {
        return isAdmin || playerId.equals(owner) || trusted.contains(playerId);
    }

    public boolean canManageTrust(UUID playerId, boolean isAdmin) {
        return isAdmin || playerId.equals(owner);
    }

    /** Writes every persistent field of this train onto the given lead cart's PDC. */
    public void saveTo(Minecart leadCart, Keys keys) {
        PersistentDataContainer pdc = leadCart.getPersistentDataContainer();
        pdc.set(keys.trainMembers, PersistentDataType.STRING, join(memberIds));
        pdc.set(keys.trainOwner, PersistentDataType.STRING, owner.toString());
        pdc.set(keys.trainTrusted, PersistentDataType.STRING, join(trusted));
        pdc.set(keys.trainDestination, PersistentDataType.STRING, destination);
        pdc.set(keys.trainActive, PersistentDataType.BYTE, (byte) (automationEnabled ? 1 : 0));
        pdc.set(keys.trainLead, PersistentDataType.STRING, leadCartId.toString());
        saveDirectionTo(leadCart, keys);
    }

    /**
     * Persists just {@link #lastDirection}, cheap enough to call every tick a train actually
     * moves so the PDC never goes stale - on a server restart (clean or not) the train resumes
     * heading the exact way it was already going instead of guessing from a neutral default,
     * which could just as easily send it in reverse as continue forward.
     */
    public void saveDirectionTo(Minecart leadCart, Keys keys) {
        PersistentDataContainer pdc = leadCart.getPersistentDataContainer();
        pdc.set(keys.trainDirection, PersistentDataType.STRING,
                lastDirection.getX() + "," + lastDirection.getY() + "," + lastDirection.getZ());
    }

    /** Stamps the "which train do I belong to" pointer onto a (non-lead) member cart. */
    public static void stampMember(Minecart cart, UUID leadCartId, Keys keys) {
        cart.getPersistentDataContainer().set(keys.trainLead, PersistentDataType.STRING, leadCartId.toString());
    }

    /** Reads a Train back out of a lead cart's PDC, or null if it holds no train data. */
    public static Train loadFrom(Minecart leadCart, Keys keys) {
        PersistentDataContainer pdc = leadCart.getPersistentDataContainer();
        String membersRaw = pdc.get(keys.trainMembers, PersistentDataType.STRING);
        String ownerRaw = pdc.get(keys.trainOwner, PersistentDataType.STRING);
        if (membersRaw == null || ownerRaw == null) {
            return null;
        }
        List<UUID> members = split(membersRaw);
        if (members.isEmpty()) {
            return null;
        }
        Train train = new Train(members.get(0), UUID.fromString(ownerRaw));
        train.memberIds.clear();
        train.memberIds.addAll(members);

        String trustedRaw = pdc.get(keys.trainTrusted, PersistentDataType.STRING);
        if (trustedRaw != null && !trustedRaw.isEmpty()) {
            train.trusted.addAll(split(trustedRaw));
        }

        String destRaw = pdc.get(keys.trainDestination, PersistentDataType.STRING);
        train.destination = destRaw != null ? destRaw : "None";

        Byte activeRaw = pdc.get(keys.trainActive, PersistentDataType.BYTE);
        train.automationEnabled = activeRaw != null && activeRaw == 1;

        String directionRaw = pdc.get(keys.trainDirection, PersistentDataType.STRING);
        Vector savedDirection = parseVector(directionRaw);
        if (savedDirection != null) {
            train.lastDirection = savedDirection;
        }

        return train;
    }

    private static String join(Collection<UUID> ids) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : ids) {
            if (sb.length() > 0) sb.append(DELIMITER);
            sb.append(id.toString());
        }
        return sb.toString();
    }

    private static Vector parseVector(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(DELIMITER);
        if (parts.length != 3) return null;
        try {
            return new Vector(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<UUID> split(String raw) {
        List<UUID> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(DELIMITER)) {
            if (!part.isBlank()) {
                out.add(UUID.fromString(part.trim()));
            }
        }
        return out;
    }
}
