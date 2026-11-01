package com.automatedtrains.train;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a moving "carpet" of plugin chunk tickets under each automated train so it can
 * keep simulating across chunks nobody is standing in, and releases tickets the instant
 * they're no longer needed so idle/finished trains don't leak force-loaded chunks.
 */
public class ChunkTicketManager {

    private final Plugin plugin;

    public ChunkTicketManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reconciles a train's held chunk tickets against the set of chunks it currently
     * needs loaded (every chunk a cart occupies, plus predictive lookahead chunks).
     * Chunks no longer required have their ticket removed; newly required chunks are
     * ticketed via {@code chunk.addPluginChunkTicket(plugin)}.
     */
    public void sync(Train train, Set<Chunk> requiredChunks) {
        Set<String> requiredKeys = new HashSet<>();
        Map<String, Chunk> byKey = new HashMap<>();
        for (Chunk chunk : requiredChunks) {
            String key = keyOf(chunk);
            requiredKeys.add(key);
            byKey.put(key, chunk);
        }

        for (String key : requiredKeys) {
            if (train.heldChunkTickets.add(key)) {
                byKey.get(key).addPluginChunkTicket(plugin);
            }
        }

        Iterator<String> it = train.heldChunkTickets.iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (!requiredKeys.contains(key)) {
                release(key);
                it.remove();
            }
        }
    }

    public void releaseAll(Train train) {
        for (String key : new HashSet<>(train.heldChunkTickets)) {
            release(key);
        }
        train.heldChunkTickets.clear();
    }

    private void release(String key) {
        String[] parts = key.split(":", 3);
        if (parts.length != 3) return;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return;
        int x, z;
        try {
            x = Integer.parseInt(parts[1]);
            z = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            return;
        }
        if (world.isChunkLoaded(x, z)) {
            world.getChunkAt(x, z).removePluginChunkTicket(plugin);
        }
    }

    private String keyOf(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }
}
