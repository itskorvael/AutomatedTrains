package com.automatedtrains.train;

import com.automatedtrains.util.RailUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Minecart;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Destination routing at junctions. A "[Route]" sign two blocks under a plain rail block turns
 * that rail into a switch for the train about to cross it: the rail's shape is set so the train
 * leaves by the branch its destination maps to on the sign.
 *
 * <pre>
 *   [Route]
 *   Spawn: left
 *   Mine: right
 *   *: straight
 * </pre>
 *
 * Directions are relative to the way the train is heading (left, right or straight). "*" (or
 * "default") covers every destination not listed; with no match and no default the train goes
 * straight on. A branch with no rail behind it is skipped in favour of the default, then straight.
 *
 * Only one train may cross a junction at a time: the first to arrive locks it, and any other train
 * wanting to cross waits in front of it until the first has cleared.
 */
public class RouteManager {

    private static final String SIGN_TAG = "[Route]";
    private static final int LOOKAHEAD_BLOCKS = 3;
    private static final double PASSED_RADIUS_SQUARED = 1.5 * 1.5;
    private static final double CLEARED_RADIUS_SQUARED = 3.0 * 3.0;
    private static final Pattern ENTRY = Pattern.compile(
            "^\\s*(.+?)\\s*:\\s*(left|right|straight|forward|ahead)\\s*$", Pattern.CASE_INSENSITIVE);

    private enum Turn { LEFT, RIGHT, STRAIGHT }

    private static final class Lock {
        final UUID owner;
        final Location center;
        boolean passed;
        int ageTicks;

        Lock(UUID owner, Location center) {
            this.owner = owner;
            this.center = center;
        }
    }

    private final TrainManager trainManager;
    private final int lockTimeoutTicks;
    private final Map<String, Lock> locks = new HashMap<>();

    public RouteManager(TrainManager trainManager, int lockTimeoutTicks) {
        this.trainManager = trainManager;
        this.lockTimeoutTicks = lockTimeoutTicks;
    }

    /** Once per tick, before any train moves: frees junctions whose train has crossed, vanished or been locked too long. */
    public void releaseFinishedLocks() {
        Iterator<Map.Entry<String, Lock>> it = locks.entrySet().iterator();
        while (it.hasNext()) {
            Lock lock = it.next().getValue();
            lock.ageTicks++;
            Train owner = trainManager.getTrainByLead(lock.owner);
            if (owner == null || !owner.automationEnabled || lock.ageTicks > lockTimeoutTicks) {
                it.remove();
                continue;
            }
            boolean anyNear = false;
            for (UUID id : owner.getMemberIds()) {
                Minecart cart = trainManager.getEntity(id);
                if (cart == null || cart.getWorld() != lock.center.getWorld()) {
                    continue;
                }
                double d = cart.getLocation().distanceSquared(lock.center);
                if (id.equals(owner.getLeadCartId()) && d <= PASSED_RADIUS_SQUARED) {
                    lock.passed = true;
                }
                if (d <= CLEARED_RADIUS_SQUARED) {
                    anyNear = true;
                }
            }
            if (lock.passed && !anyNear) {
                it.remove();
            }
        }
    }

    /**
     * Sets up any junction the train is about to cross.
     *
     * @return true if the train must wait because another train is using that junction
     */
    public boolean prepareJunctionAhead(Train train, Minecart lead, Vector heading) {
        BlockFace facing = cardinal(heading);
        if (facing == null) {
            return false;
        }
        Location probe = lead.getLocation().clone();
        for (int step = 0; step <= LOOKAHEAD_BLOCKS; step++) {
            Block rail = RailUtil.findRailBlock(probe, null);
            if (rail != null && rail.getType() == Material.RAIL) {
                BlockState state = rail.getRelative(0, -2, 0).getState();
                if (state instanceof Sign sign && isRouteSign(sign)) {
                    return crossJunction(train, rail, sign, facing);
                }
            }
            probe.add(facing.getModX(), 0, facing.getModZ());
        }
        return false;
    }

    private boolean crossJunction(Train train, Block rail, Sign sign, BlockFace facing) {
        String key = rail.getWorld().getUID() + ":" + rail.getX() + ":" + rail.getY() + ":" + rail.getZ();
        Lock lock = locks.get(key);
        if (lock != null) {
            return !lock.owner.equals(train.getLeadCartId()); // ours already: switch is set; otherwise wait
        }

        Turn wanted = turnFor(sign, train.getDestination());
        Turn defaultTurn = turnFor(sign, null);
        for (Turn turn : new Turn[] { wanted, defaultTurn, Turn.STRAIGHT }) {
            BlockFace exit = apply(turn, facing);
            if (hasRailBeyond(rail, exit)) {
                setShape(rail, facing.getOppositeFace(), exit);
                break;
            }
        }
        locks.put(key, new Lock(train.getLeadCartId(), rail.getLocation().add(0.5, 0, 0.5)));
        return false;
    }

    private boolean isRouteSign(Sign sign) {
        String line = sign.getSide(org.bukkit.block.sign.Side.FRONT).getLine(0);
        return line != null && line.trim().equalsIgnoreCase(SIGN_TAG);
    }

    /** Looks the destination up on the sign's lines; a null destination asks for the default entry only. */
    private Turn turnFor(Sign sign, String destination) {
        var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        Turn match = null;
        Turn fallback = null;
        for (int i = 1; i <= 3; i++) {
            String line = side.getLine(i);
            if (line == null) {
                continue;
            }
            Matcher m = ENTRY.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(1).trim();
            Turn turn = parseTurn(m.group(2));
            if (name.equals("*") || name.equalsIgnoreCase("default")) {
                fallback = turn;
            } else if (destination != null && name.equalsIgnoreCase(destination.trim())) {
                match = turn;
            }
        }
        if (match != null) {
            return match;
        }
        return fallback != null ? fallback : Turn.STRAIGHT;
    }

    private Turn parseTurn(String word) {
        return switch (word.toLowerCase()) {
            case "left" -> Turn.LEFT;
            case "right" -> Turn.RIGHT;
            default -> Turn.STRAIGHT;
        };
    }

    /** The compass direction a train heading {@code facing} leaves by when it takes {@code turn}. */
    private BlockFace apply(Turn turn, BlockFace facing) {
        // Minecraft: +x east, +z south. Left of (x, z) is (z, -x).
        int x = facing.getModX();
        int z = facing.getModZ();
        return switch (turn) {
            case STRAIGHT -> facing;
            case LEFT -> face(z, -x);
            case RIGHT -> face(-z, x);
        };
    }

    private boolean hasRailBeyond(Block rail, BlockFace exit) {
        Block next = rail.getRelative(exit);
        return RailUtil.isRail(next) || RailUtil.isRail(next.getRelative(0, 1, 0)) || RailUtil.isRail(next.getRelative(0, -1, 0));
    }

    private void setShape(Block rail, BlockFace entry, BlockFace exit) {
        Rail.Shape shape = shapeFor(entry, exit);
        if (shape == null || !(rail.getBlockData() instanceof Rail data) || data.getShape() == shape) {
            return;
        }
        data.setShape(shape);
        rail.setBlockData(data, false);
    }

    private Rail.Shape shapeFor(BlockFace a, BlockFace b) {
        boolean north = a == BlockFace.NORTH || b == BlockFace.NORTH;
        boolean south = a == BlockFace.SOUTH || b == BlockFace.SOUTH;
        boolean east = a == BlockFace.EAST || b == BlockFace.EAST;
        boolean west = a == BlockFace.WEST || b == BlockFace.WEST;
        if (north && south) return Rail.Shape.NORTH_SOUTH;
        if (east && west) return Rail.Shape.EAST_WEST;
        if (north && east) return Rail.Shape.NORTH_EAST;
        if (north && west) return Rail.Shape.NORTH_WEST;
        if (south && east) return Rail.Shape.SOUTH_EAST;
        if (south && west) return Rail.Shape.SOUTH_WEST;
        return null;
    }

    private BlockFace cardinal(Vector v) {
        if (Math.abs(v.getX()) < 1.0E-6 && Math.abs(v.getZ()) < 1.0E-6) {
            return null;
        }
        return Math.abs(v.getX()) >= Math.abs(v.getZ())
                ? (v.getX() > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (v.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
    }

    private BlockFace face(int x, int z) {
        if (x > 0) return BlockFace.EAST;
        if (x < 0) return BlockFace.WEST;
        return z > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
}
