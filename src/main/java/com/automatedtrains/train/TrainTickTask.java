package com.automatedtrains.train;

import com.automatedtrains.util.Keys;
import com.automatedtrains.util.RailUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import com.automatedtrains.radio.RadioAnnouncer;
import org.bukkit.block.Sign;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Minecart;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The heart of the plugin: a synchronous 1-tick repeating task that drives every
 * registered train's movement, station handling, chunk-ticket lifecycle and
 * cosmetic-attachment positions. Runs entirely on the main thread since it touches
 * live entities and chunk loading every call.
 */
public class TrainTickTask extends BukkitRunnable {

    public static final int LOOKAHEAD_CHUNKS = 2;

    private static final double PROBE_STEP = 0.25;
    /** How close a probe point must come to a cart's centre to count as reaching it. */
    private static final double PROBE_HIT_RADIUS = 0.3;

    private static final Pattern DEST_LINE_PATTERN = Pattern.compile("^\\s*Dest:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private final Plugin plugin;
    private final TrainManager trainManager;
    private final ChunkTicketManager chunkTicketManager;
    private final AttachmentManager attachmentManager;
    private final Keys keys;
    private final RadioAnnouncer radioAnnouncer;
    private final RouteManager routeManager;

    /** blocks/tick - configurable via config.yml "speed" */
    public final double speed;
    /** blocks between coupled carts - configurable via config.yml "spacing" */
    public final double spacing;
    /** ticks to sit at a station sign - configurable via config.yml "station-wait-seconds" */
    public final int stationWaitTicks;
    /** blocks - a train stops when another train that has stopped is this close ahead (0 turns the check off) - config "train-stop-distance" */
    public final double trainStopDistance;

    public TrainTickTask(Plugin plugin, TrainManager trainManager, ChunkTicketManager chunkTicketManager,
                          AttachmentManager attachmentManager, Keys keys, double speed, double spacing, int stationWaitTicks,
                          double trainStopDistance, int junctionLockTimeoutTicks) {
        this.plugin = plugin;
        this.trainManager = trainManager;
        this.chunkTicketManager = chunkTicketManager;
        this.attachmentManager = attachmentManager;
        this.keys = keys;
        this.radioAnnouncer = new RadioAnnouncer(plugin);
        this.speed = speed;
        this.spacing = spacing;
        this.stationWaitTicks = stationWaitTicks;
        this.trainStopDistance = trainStopDistance;
        this.routeManager = new RouteManager(trainManager, junctionLockTimeoutTicks);
    }

    @Override
    public void run() {
        // Copy to a snapshot list since a train may unregister itself mid-iteration (e.g. derail/dissolve).
        routeManager.releaseFinishedLocks();
        List<Train> snapshot = new ArrayList<>(trainManager.getAllTrains());
        for (Train train : snapshot) {
            try {
                tickTrain(train);
            } catch (Exception ex) {
                plugin.getLogger().warning("[AutomatedTrains] Error ticking train " + train.getLeadCartId() + ": " + ex);
            }
        }
    }

    private void tickTrain(Train train) {
        if (!train.automationEnabled) {
            train.holdingForTrain = false;
            return;
        }

        Minecart lead = trainManager.getEntity(train.getLeadCartId());
        if (lead == null) {
            return; // lead itself isn't loaded - nothing we can do until something else loads its chunk
        }

        List<Minecart> carts = resolveCarts(train);
        if (carts == null) {
            // A trailing cart isn't loaded yet. Force a safety net of chunks around the lead so
            // it gets a chance to load, instead of stalling forever: without this, a single
            // not-yet-loaded trailing cart starves the WHOLE train - including the lead - of
            // chunk tickets, since normal ticketing below only ever runs once every member has
            // already resolved. That's the "coupled but frozen until you walk up and touch them"
            // bug - the player's own proximity was the only thing loading the missing chunk.
            ensureLeadNeighborhoodLoaded(train, lead);
            return; // wait for next tick - the missing member(s) should be loaded by then
        }

        if (train.paused) {
            train.holdingForTrain = false;
            for (Minecart cart : carts) {
                cart.setVelocity(new Vector(0, 0, 0));
            }
            train.stationCountdownTicks--;
            if (train.stationCountdownTicks <= 0) {
                train.paused = false;
                announceDeparture(train);
            }
            syncChunkTickets(train, carts, lead.getLocation().getDirection());
            syncAttachments(train, carts);
            return;
        }

        // Running (not parked at a station): any station announcement left over from a manual
        // pause is stale, so drop it rather than announcing a departure much later.
        train.announceStation = null;
        train.announceChannel = null;
        train.announceLocation = null;

        checkStationSign(train, lead);
        if (train.paused) {
            return; // just tripped a station; skip movement this tick
        }

        Block railBlock = RailUtil.findRailBlock(lead.getLocation(), null);
        if (railBlock == null) {
            railBlock = bounceAtDeadEnd(train, lead);
            if (railBlock == null) {
                // No recorded history to recover onto yet (this train has never successfully
                // moved a single tick) - nothing safe to back up to; just hold still and retry.
                for (Minecart cart : carts) {
                    cart.setVelocity(new Vector(0, 0, 0));
                }
                return;
            }
        }

        Vector direction = RailUtil.computeForwardDirection(railBlock, train.lastDirection);
        train.lastDirection = direction.clone();
        // Keep the persisted direction fresh every moving tick, not just at sparse save points,
        // so a restart (clean or not) always resumes heading the way it was actually going.
        train.saveDirectionTo(lead, keys);

        AheadStatus ahead = checkTrainAhead(train, lead, railBlock, direction);
        boolean waitForJunction = ahead == AheadStatus.CLEAR && routeManager.prepareJunctionAhead(train, lead, direction);
        if (ahead == AheadStatus.HOLD || waitForJunction) {
            // Hold position (no station countdown - this ends the moment the way is clear again).
            train.holdingForTrain = true;
            for (Minecart cart : carts) {
                cart.setVelocity(new Vector(0, 0, 0));
            }
            syncChunkTickets(train, carts, direction);
            syncAttachments(train, carts);
            return;
        }
        train.holdingForTrain = false;

        Location newLeadLoc = lead.getLocation().clone().add(direction.clone().multiply(speed));
        newLeadLoc = RailUtil.applyCenterlineCorrection(newLeadLoc, railBlock);
        newLeadLoc.setDirection(direction);

        lead.teleport(newLeadLoc);
        lead.setVelocity(direction.clone().multiply(speed)); // keeps redstone/hoppers/client interpolation happy

        recordPathPoint(train, newLeadLoc);
        moveTrailingCarts(train, carts);

        syncChunkTickets(train, carts, direction);
        syncAttachments(train, carts);
    }

    /**
     * Hit the end of usable track - ran off the last rail, or the next block is blocked/missing
     * - so instead of gliding into empty space or stopping there, back the lead up to the last
     * position we know for certain was still on a valid rail (recorded every successful tick)
     * and flip its direction, so it's already heading back the way it came by the time this
     * method returns. The caller falls straight through into normal movement using the returned
     * rail block, all in this same tick - the bounce is instant, not a stop then a restart.
     *
     * @return the rail block under the recovered position, or null if there's no path history
     *         yet to recover from (the train has never successfully completed a tick)
     */
    private Block bounceAtDeadEnd(Train train, Minecart lead) {
        // The newest recorded point isn't necessarily still valid: the tick that stepped off
        // the end of the track was itself recorded as "successful" (the check that runs is
        // against the position *before* that tick's move, not after), so the very last history
        // entry can be the first off-track position. Walk backward until we find one that's
        // genuinely still on a rail.
        java.util.Iterator<Location> it = train.pathHistory.descendingIterator();
        while (it.hasNext()) {
            Location candidate = it.next();
            Block railBlock = RailUtil.findRailBlock(candidate, null);
            if (railBlock != null) {
                lead.teleport(candidate);
                train.lastDirection = train.lastDirection.multiply(-1);
                return railBlock;
            }
        }
        return null; // no valid rail anywhere in recorded history - genuinely nothing to recover to
    }

    // ----------------------------------------------------------------------------------
    // Train ahead detection
    // ----------------------------------------------------------------------------------

    private enum AheadStatus {
        /** Nothing in the way. */
        CLEAR,
        /** A stopped train, or one coming the other way, is close ahead: wait. */
        HOLD
    }

    /** A train counts as stopped when it isn't started, is waiting at a station, or is itself held up by another train. */
    private boolean isStopped(Train train) {
        return !train.automationEnabled || train.paused || train.holdingForTrain;
    }

    /**
     * Walks the rails ahead of the lead cart for {@link #trainStopDistance} blocks (following
     * curves and slopes the same way real movement does, so a parked train on a parallel track
     * is never mistaken for one in the way) and works out what, if anything, is there:
     * a stopped train means wait; a moving train coming the other way means wait too (both
     * trains stop, which is the safe outcome on a single track); a moving train heading the
     * same way is ignored.
     *
     * "Oncoming" is judged along the track, not in the world: the other cart's travel direction is
     * compared with the direction the probe is travelling at that very spot. Comparing it with the
     * lead's own heading made two trains following each other round a U-turn or a compact loop look
     * head-on (they point opposite ways in the world), so both stopped for good.
     */
    private AheadStatus checkTrainAhead(Train train, Minecart lead, Block railBlock, Vector direction) {
        train.waitingOn = null;
        if (trainStopDistance <= 0) {
            return AheadStatus.CLEAR;
        }
        Location start = lead.getLocation();
        double cullSquared = (trainStopDistance + 2) * (trainStopDistance + 2);

        List<Minecart> candidates = new ArrayList<>();
        List<Train> owners = new ArrayList<>();
        for (Train other : trainManager.getAllTrains()) {
            if (other == train) {
                continue;
            }
            for (UUID id : other.getMemberIds()) {
                Minecart cart = trainManager.getEntity(id);
                if (cart != null && cart.getWorld() == start.getWorld()
                        && cart.getLocation().distanceSquared(start) <= cullSquared) {
                    candidates.add(cart);
                    owners.add(other);
                }
            }
        }
        if (candidates.isEmpty()) {
            return AheadStatus.CLEAR;
        }

        double hitSquared = PROBE_HIT_RADIUS * PROBE_HIT_RADIUS;
        Location probe = start.clone();
        Vector dir = direction.clone();
        for (double travelled = 0; travelled <= trainStopDistance; travelled += PROBE_STEP) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getLocation().distanceSquared(probe) > hitSquared) {
                    continue;
                }
                Train other = owners.get(i);
                Vector heading = travelDirection(other, candidates.get(i));
                if (heading == null) {
                    heading = horizontal(other.lastDirection); // no recorded path yet (never moved)
                }
                if (heading != null && heading.dot(horizontal(dir, direction)) < -0.3) {
                    train.waitingOn = other.getLeadCartId();
                    return AheadStatus.HOLD; // oncoming: both trains see each other and stop
                }
                if (isStopped(other)) {
                    if (breaksCircularWait(train, other)) {
                        continue; // we are waiting on each other: one of us has to go first
                    }
                    train.waitingOn = other.getLeadCartId();
                    return AheadStatus.HOLD;
                }
                // Moving the same way: not our problem.
            }
            probe = probe.add(dir.clone().multiply(PROBE_STEP));
            Block next = RailUtil.findRailBlock(probe, null);
            if (next == null) {
                return AheadStatus.CLEAR; // track ends before reaching anything
            }
            dir = RailUtil.computeForwardDirection(next, dir);
            probe = RailUtil.applyCenterlineCorrection(probe, next);
        }
        return AheadStatus.CLEAR;
    }

    /** The flat (no Y) unit vector of {@code v}, or {@code fallback}'s when {@code v} has no horizontal part. */
    private static Vector horizontal(Vector v, Vector fallback) {
        Vector flat = horizontal(v);
        return flat != null ? flat : horizontal(fallback);
    }

    private static Vector horizontal(Vector v) {
        Vector flat = new Vector(v.getX(), 0, v.getZ());
        return flat.lengthSquared() > 1.0E-8 ? flat.normalize() : null;
    }

    /**
     * Which way a member cart of {@code owner} is travelling along the track, read off the train's own
     * recorded path (the cart sits {@code index * spacing} behind the lead on it), as a flat unit vector.
     * Null when the train has no recorded path yet. A cart's yaw can't be used: vanilla flips it by 180
     * degrees to avoid spinning, so it does not reliably say which way the cart is going.
     */
    private Vector travelDirection(Train owner, Minecart cart) {
        int index = owner.getMemberIds().indexOf(cart.getUniqueId());
        if (index < 0 || owner.pathHistory.size() < 2) {
            return null;
        }
        Location[] points = owner.pathHistory.toArray(new Location[0]); // oldest first
        double target = index * spacing;
        double accumulated = 0;
        Vector found = null;
        for (int i = points.length - 1; i > 0; i--) {
            Location a = points[i];
            Location b = points[i - 1];
            if (a.getWorld() != b.getWorld()) {
                break;
            }
            double length = a.distance(b);
            if (length < 1.0E-6) {
                continue;
            }
            Vector segment = horizontal(a.toVector().subtract(b.toVector()));
            if (segment != null) {
                found = segment;
            }
            if (accumulated + length >= target) {
                break;
            }
            accumulated += length;
        }
        return found;
    }

    /**
     * True when {@code stopped} is itself held up by a chain of trains that ends at {@code train}, so the two
     * would wait on each other for ever (trains chasing each other round a short loop). The one with the lower
     * lead id goes first, which always frees at least one train in the ring.
     */
    private boolean breaksCircularWait(Train train, Train stopped) {
        Train t = stopped;
        for (int hops = 0; hops < 16 && t != null; hops++) {
            if (!t.holdingForTrain || t.waitingOn == null) {
                return false;
            }
            Train next = trainManager.getTrainByLead(t.waitingOn);
            if (next == train) {
                return train.getLeadCartId().compareTo(stopped.getLeadCartId()) < 0;
            }
            t = next;
        }
        return false;
    }

    // ----------------------------------------------------------------------------------
    // Movement
    // ----------------------------------------------------------------------------------

    private void moveTrailingCarts(Train train, List<Minecart> carts) {
        for (int i = 1; i < carts.size(); i++) {
            Minecart cart = carts.get(i);
            double targetDistance = i * spacing;
            Location target = pointAtDistanceBehind(train.pathHistory, targetDistance);
            if (target == null) {
                continue; // not enough recorded history yet (train was just coupled) - hold position
            }
            Vector toTarget = target.clone().subtract(cart.getLocation()).toVector();
            Vector facing = toTarget.lengthSquared() > 1.0E-6 ? toTarget.clone().normalize() : train.lastDirection;
            target.setDirection(facing);
            cart.teleport(target);
            // Zero, not a matching forward velocity: this cart's position is fully re-derived
            // every tick from the lead's recorded path, so it doesn't need vanilla's own physics
            // contributing anything on top of that - keeping velocity at zero avoids it fighting
            // with our own authoritative placement.
            cart.setVelocity(new Vector(0, 0, 0));
        }

        int maxPoints = (int) Math.ceil((carts.size() * spacing + 5) / speed) + 5;
        while (train.pathHistory.size() > maxPoints) {
            train.pathHistory.pollFirst();
        }
    }

    private void recordPathPoint(Train train, Location leadLocation) {
        train.pathHistory.addLast(leadLocation.clone());
    }

    /** Walks the breadcrumb trail backward from the newest point, interpolating the point exactly {@code distance} behind it. */
    private Location pointAtDistanceBehind(java.util.Deque<Location> path, double distance) {
        if (path.isEmpty()) return null;
        Location[] points = path.toArray(new Location[0]); // oldest first, newest last
        if (points.length == 1) return points[0].clone();

        double accumulated = 0;
        for (int i = points.length - 1; i > 0; i--) {
            Location a = points[i];
            Location b = points[i - 1];
            double segmentLength = a.distance(b);
            if (accumulated + segmentLength >= distance) {
                double remaining = distance - accumulated;
                double t = segmentLength < 1.0E-6 ? 0 : remaining / segmentLength;
                return a.clone().add(b.clone().subtract(a).toVector().multiply(t));
            }
            accumulated += segmentLength;
        }
        return points[0].clone();
    }

    // ----------------------------------------------------------------------------------
    // Station signs
    // ----------------------------------------------------------------------------------

    private void checkStationSign(Train train, Minecart lead) {
        Block below = lead.getLocation().getBlock().getRelative(0, -2, 0);
        BlockState state = below.getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        String line1 = sign.getSide(org.bukkit.block.sign.Side.FRONT).getLine(0);
        if (line1 == null || !line1.trim().equalsIgnoreCase("[Station]")) {
            return;
        }

        Location stationLoc = below.getLocation();
        if (train.ignoredStationLocation != null
                && train.ignoredStationLocation.getWorld() == stationLoc.getWorld()
                && train.ignoredStationLocation.distanceSquared(stationLoc) < 1.0) {
            return; // already serviced this exact station, still parked on/near it
        }

        String line2 = sign.getSide(org.bukkit.block.sign.Side.FRONT).getLine(1);
        if (line2 != null) {
            Matcher matcher = DEST_LINE_PATTERN.matcher(line2.trim());
            if (matcher.matches()) {
                train.setDestination(matcher.group(1).trim());
                Minecart leadCart = trainManager.getEntity(train.getLeadCartId());
                if (leadCart != null) {
                    train.saveTo(leadCart, keys);
                }
            }
        }

        train.paused = true;
        train.stationCountdownTicks = stationWaitTicks;
        train.ignoredStationLocation = stationLoc;
        announceArrival(train, sign, stationLoc);
    }

    /** Sign lines 3 and 4 name the station and its radio channel; no channel means no announcement. */
    private void announceArrival(Train train, Sign sign, Location stationLoc) {
        var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        String station = side.getLine(2) == null ? "" : side.getLine(2).trim();
        String channel = side.getLine(3) == null ? "" : side.getLine(3).trim();
        if (channel.isEmpty()) {
            return;
        }
        if (station.isEmpty()) {
            station = "this station";
        }
        train.announceStation = station;
        train.announceChannel = channel;
        train.announceLocation = stationLoc;
        radioAnnouncer.arrival(stationLoc, station, channel, train.getDestination());
    }

    private void announceDeparture(Train train) {
        if (train.announceChannel != null) {
            radioAnnouncer.departure(train.announceLocation, train.announceStation, train.announceChannel,
                    train.getDestination());
        }
        train.announceStation = null;
        train.announceChannel = null;
        train.announceLocation = null;
    }

    // ----------------------------------------------------------------------------------
    // Chunk ticketing
    // ----------------------------------------------------------------------------------

    private void syncChunkTickets(Train train, List<Minecart> carts, Vector leadDirection) {
        Set<Chunk> required = new HashSet<>();
        for (Minecart cart : carts) {
            required.add(cart.getLocation().getChunk());
        }
        Minecart lead = carts.get(0);
        Location cursor = lead.getLocation().clone();
        Vector step = leadDirection.lengthSquared() > 1.0E-6 ? leadDirection.clone().normalize().multiply(16) : new Vector(0, 0, 0);
        for (int i = 1; i <= LOOKAHEAD_CHUNKS; i++) {
            cursor = cursor.add(step);
            if (cursor.getWorld() != null) {
                required.add(cursor.getChunk());
            }
        }
        chunkTicketManager.sync(train, required);
    }

    /**
     * Force-loads a generous neighborhood around the lead cart - its own chunk, enough chunks
     * behind it (opposite {@link Train#lastDirection}) to cover the train's full coupled length,
     * and the usual lookahead in front - used only when a trailing cart couldn't be resolved yet.
     * Ordinary {@link #syncChunkTickets} can't help in that situation since it's computed from
     * every cart's position, which is exactly what we don't have; this guesses from the lead
     * alone, which is enough to catch every trailing cart since they're never more than
     * {@code (size-1) * spacing} blocks behind it.
     */
    private void ensureLeadNeighborhoodLoaded(Train train, Minecart lead) {
        Set<Chunk> required = new HashSet<>();
        Location leadLoc = lead.getLocation();
        required.add(leadLoc.getChunk());

        Vector behind = train.lastDirection.lengthSquared() > 1.0E-6
                ? train.lastDirection.clone().normalize().multiply(-16)
                : new Vector(0, 0, 0);
        int chunksBehindNeeded = (int) Math.ceil((train.size() * spacing) / 16.0) + 2; // +2 buffer chunks
        Location behindCursor = leadLoc.clone();
        for (int i = 1; i <= chunksBehindNeeded; i++) {
            behindCursor = behindCursor.add(behind);
            if (behindCursor.getWorld() != null) {
                required.add(behindCursor.getChunk());
            }
        }

        Vector ahead = train.lastDirection.lengthSquared() > 1.0E-6
                ? train.lastDirection.clone().normalize().multiply(16)
                : new Vector(0, 0, 0);
        Location aheadCursor = leadLoc.clone();
        for (int i = 1; i <= LOOKAHEAD_CHUNKS; i++) {
            aheadCursor = aheadCursor.add(ahead);
            if (aheadCursor.getWorld() != null) {
                required.add(aheadCursor.getChunk());
            }
        }

        chunkTicketManager.sync(train, required);
    }

    // ----------------------------------------------------------------------------------
    // Cosmetic attachments
    // ----------------------------------------------------------------------------------

    // Must match AttachmentListener's copy of these constants exactly - see its comment.
    private static final double ATTACH_DISTANCE = 0.7;
    private static final double ATTACH_HEIGHT = 0.35;

    private void syncAttachments(Train train, List<Minecart> carts) {
        for (Minecart cart : carts) {
            List<AttachmentManager.Attachment> attachments = attachmentManager.getAttachments(cart.getUniqueId());
            if (attachments.isEmpty()) continue;

            Vector forward = resolveAttachmentForward(cart, train);

            for (AttachmentManager.Attachment attachment : attachments) {
                Entity entity = org.bukkit.Bukkit.getEntity(attachment.id);
                if (entity == null) {
                    continue; // will be pruned on next break/unlink pass
                }

                Vector facing = RailUtil.fromLocalOffset(forward, attachment.localOffset);
                Location target = cart.getLocation().clone().add(facing.clone().multiply(ATTACH_DISTANCE)).add(0, ATTACH_HEIGHT, 0);
                double yaw = Math.toDegrees(Math.atan2(-facing.getX(), facing.getZ()));
                target.setYaw((float) yaw);

                entity.teleport(target);
                if (entity instanceof ItemFrame frame) {
                    frame.setFacingDirection(yawToNearestFace(facing), true);
                } else if (entity instanceof Display display) {
                    applyYawTransform(display, facing);
                }

                if (attachment.companionId != null) {
                    Entity companion = org.bukkit.Bukkit.getEntity(attachment.companionId);
                    if (companion != null) {
                        companion.teleport(target);
                    }
                }
            }
        }
    }

    /**
     * The direction the cart's rail actually runs, independent of velocity/yaw. A minecart's
     * entity yaw isn't a reliable "which way am I facing" signal (especially for a train that
     * hasn't started moving and so has zero velocity), so attachments are oriented from the
     * rail shape instead - same approach as {@link com.automatedtrains.listener.AttachmentListener}.
     */
    private Vector resolveAttachmentForward(Minecart cart, Train train) {
        Block railBlock = RailUtil.findRailBlock(cart.getLocation(), null);
        if (railBlock != null) {
            Vector direction = RailUtil.computeForwardDirection(railBlock, train.lastDirection);
            direction.setY(0);
            if (direction.lengthSquared() > 1.0E-6) {
                return direction.normalize();
            }
        }
        return train.lastDirection.lengthSquared() > 1.0E-6 ? train.lastDirection.clone().normalize() : new Vector(0, 0, 1);
    }

    private org.bukkit.block.BlockFace yawToNearestFace(Vector direction) {
        double ax = Math.abs(direction.getX());
        double az = Math.abs(direction.getZ());
        if (ax > az) {
            return direction.getX() > 0 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST;
        } else {
            return direction.getZ() > 0 ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
        }
    }

    private void applyYawTransform(Display display, Vector direction) {
        double angle = Math.atan2(direction.getX(), -direction.getZ()) - Math.PI / 2.0; // keep in sync with AttachmentListener#applyRotation
        AxisAngle4f axisAngle = new AxisAngle4f((float) angle, 0, 1, 0);
        org.joml.Quaternionf rotation = new org.joml.Quaternionf(axisAngle);
        Transformation transformation = display.getTransformation();
        display.setTransformation(new Transformation(
                transformation.getTranslation(),
                rotation,
                new Vector3f(1, 1, 1),
                new org.joml.Quaternionf()
        ));
        display.setBillboard(Display.Billboard.FIXED);
    }

    // ----------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------

    /** Resolves every member cart entity for a train; returns null if any is not currently loaded. */
    private List<Minecart> resolveCarts(Train train) {
        List<Minecart> carts = new ArrayList<>(train.size());
        for (UUID id : train.getMemberIds()) {
            Minecart cart = trainManager.getEntity(id);
            if (cart == null) {
                return null;
            }
            carts.add(cart);
        }
        return carts;
    }
}
