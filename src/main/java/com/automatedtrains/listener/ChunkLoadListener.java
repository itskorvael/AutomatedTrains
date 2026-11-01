package com.automatedtrains.listener;

import com.automatedtrains.train.Train;
import com.automatedtrains.train.TrainManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Auto-resume: whenever a chunk loads (a player wanders nearby, a plugin force-loads it,
 * or the world finishes booting), scan its entities for a minecart carrying this plugin's
 * "lead cart" PersistentDataContainer marker. If found and not already tracked, its Train
 * is reconstructed in memory and handed to the tick task, which immediately resumes
 * driving it at 0.4 blocks/tick - no manual re-linking needed after a restart.
 */
public class ChunkLoadListener implements Listener {

    private final TrainManager trainManager;

    public ChunkLoadListener(TrainManager trainManager) {
        this.trainManager = trainManager;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Minecart cart) {
                discover(cart);
            }
        }
    }

    private void discover(Minecart cart) {
        // Note: discoverFromCart also returns non-null for a train that was already registered
        // (it's called on every chunk load, not just the first), so nothing here may touch
        // train.lastDirection - a minecart's entity yaw isn't a meaningful "which way am I
        // facing" signal anyway, and doing so on every reload would repeatedly discard
        // whatever direction the train is actually moving in, or /train move set.
        trainManager.discoverFromCart(cart);
    }

    /** Called once from {@code onEnable} to catch trains sitting in chunks that were already loaded before this plugin finished starting up. */
    public void scanAlreadyLoadedChunks(Plugin plugin) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Minecart cart) {
                        discover(cart);
                    }
                }
            }
        }
    }
}
