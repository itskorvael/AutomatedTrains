package com.automatedtrains.util;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;

/**
 * Pure math helpers for driving a minecart along vanilla rail shapes without
 * relying on vanilla's own (friction-bearing) movement physics. The tick task
 * treats the cart's position as a plain 3D vector that it integrates itself;
 * this class only answers two questions each tick: "what rail am I standing
 * on" and "given the direction I was already heading, which way does this
 * rail shape send me next".
 */
public final class RailUtil {

    /** How high above the rail block's Y level a cart visually rides on a flat rail. */
    public static final double RAIL_RIDE_HEIGHT = 0.0625;

    private RailUtil() {
    }

    public static boolean isRail(Block block) {
        return block != null && Tag.RAILS.isTagged(block.getType());
    }

    /**
     * Locates the rail block a cart currently sitting at {@code loc} should be following,
     * searching a small vertical window around {@code previousRailBlock}'s Y level (or the
     * cart's own block Y if there is no previous block yet) to account for ascending rails.
     * Returns null if no rail block can be found nearby (the train has derailed).
     */
    public static Block findRailBlock(Location loc, Block previousRailBlock) {
        World world = loc.getWorld();
        if (world == null) return null;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int baseY = previousRailBlock != null ? previousRailBlock.getY() : loc.getBlockY();

        for (int dy = -1; dy <= 1; dy++) {
            Block b = world.getBlockAt(x, baseY + dy, z);
            if (isRail(b)) return b;
        }
        for (int dy = -2; dy <= 2; dy++) {
            Block b = world.getBlockAt(x, baseY + dy, z);
            if (isRail(b)) return b;
        }
        return null;
    }

    /**
     * Given the rail block currently under the cart and the direction it was already
     * travelling, returns the unit vector (including any 45-degree slope component)
     * the cart should continue travelling in across this rail block.
     */
    public static Vector computeForwardDirection(Block railBlock, Vector previousDirection) {
        BlockData data = railBlock.getBlockData();
        if (!(data instanceof Rail rail)) {
            return previousDirection.clone().normalize();
        }

        Rail.Shape shape = rail.getShape();
        Vector[] candidates = candidateDirections(shape);

        Vector best = candidates[0];
        double bestDot = -Double.MAX_VALUE;
        Vector reference = previousDirection.lengthSquared() > 1.0E-6 ? previousDirection.clone().normalize() : new Vector(0, 0, 1);

        for (Vector candidate : candidates) {
            double dot = candidate.dot(reference);
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best.clone();
    }

    /**
     * The two directions a cart may legally travel across a given rail shape,
     * as unit vectors (with a Y component baked in for ascending shapes).
     */
    private static Vector[] candidateDirections(Rail.Shape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> new Vector[]{unit(0, 0, -1), unit(0, 0, 1)};
            case EAST_WEST -> new Vector[]{unit(1, 0, 0), unit(-1, 0, 0)};
            case ASCENDING_NORTH -> new Vector[]{unit(0, 1, -1), unit(0, -1, 1)};
            case ASCENDING_SOUTH -> new Vector[]{unit(0, 1, 1), unit(0, -1, -1)};
            case ASCENDING_EAST -> new Vector[]{unit(1, 1, 0), unit(-1, -1, 0)};
            case ASCENDING_WEST -> new Vector[]{unit(-1, 1, 0), unit(1, -1, 0)};
            case SOUTH_EAST -> new Vector[]{unit(0, 0, 1), unit(1, 0, 0)};
            case SOUTH_WEST -> new Vector[]{unit(0, 0, 1), unit(-1, 0, 0)};
            case NORTH_WEST -> new Vector[]{unit(0, 0, -1), unit(-1, 0, 0)};
            case NORTH_EAST -> new Vector[]{unit(0, 0, -1), unit(1, 0, 0)};
        };
    }

    private static Vector unit(double x, double y, double z) {
        Vector v = new Vector(x, y, z);
        return v.normalize();
    }

    /**
     * Nudges the cart's off-axis coordinate back toward the rail centerline so straight
     * track doesn't drift sideways and curves/slopes don't visually cut corners.
     */
    public static Location applyCenterlineCorrection(Location loc, Block railBlock) {
        BlockData data = railBlock.getBlockData();
        if (!(data instanceof Rail rail)) {
            return loc;
        }
        Rail.Shape shape = rail.getShape();
        double targetX = loc.getX();
        double targetZ = loc.getZ();
        double centerX = railBlock.getX() + 0.5;
        double centerZ = railBlock.getZ() + 0.5;

        switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> targetX = centerX;
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> targetZ = centerZ;
            default -> {
                // Curves: gently pull toward the block center on both axes.
                targetX = lerp(loc.getX(), centerX, 0.15);
                targetZ = lerp(loc.getZ(), centerZ, 0.15);
            }
        }

        double targetY = loc.getY();
        if (shape == Rail.Shape.NORTH_SOUTH || shape == Rail.Shape.EAST_WEST
                || shape == Rail.Shape.SOUTH_EAST || shape == Rail.Shape.SOUTH_WEST
                || shape == Rail.Shape.NORTH_WEST || shape == Rail.Shape.NORTH_EAST) {
            targetY = lerp(loc.getY(), railBlock.getY() + RAIL_RIDE_HEIGHT, 0.5);
        }

        Location corrected = loc.clone();
        corrected.setX(targetX);
        corrected.setY(targetY);
        corrected.setZ(targetZ);
        return corrected;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** The horizontal-right vector for a given forward direction, in the cart's own frame. */
    public static Vector rightOf(Vector forward) {
        return new Vector(forward.getZ(), 0, -forward.getX()).normalize();
    }

    /**
     * Decomposes a world-space direction into the cart's own (forward, right) frame at the
     * moment it's captured, so cosmetic attachments can be re-projected back into world space
     * on every later tick as the cart's rail-facing direction changes on curves - see
     * {@link #fromLocalOffset}, which must stay the exact inverse of this.
     */
    public static Vector toLocalOffset(Vector forward, Vector worldDirection) {
        Vector right = rightOf(forward);
        return new Vector(worldDirection.dot(forward), 0, worldDirection.dot(right));
    }

    /** Inverse of {@link #toLocalOffset}: turns a stored cart-local offset back into a world-space direction for the cart's current orientation. */
    public static Vector fromLocalOffset(Vector forward, Vector localOffset) {
        Vector right = rightOf(forward);
        Vector world = forward.clone().multiply(localOffset.getX()).add(right.multiply(localOffset.getZ()));
        return world.lengthSquared() > 1.0E-6 ? world.normalize() : forward.clone();
    }
}
