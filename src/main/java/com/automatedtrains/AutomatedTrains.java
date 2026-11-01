package com.automatedtrains;

import com.automatedtrains.command.TrainCommand;
import com.automatedtrains.listener.AttachmentListener;
import com.automatedtrains.listener.CartBreakListener;
import com.automatedtrains.listener.ChunkLoadListener;
import com.automatedtrains.listener.CollisionListener;
import com.automatedtrains.listener.CouplingListener;
import com.automatedtrains.listener.PlayerDisconnectListener;
import com.automatedtrains.listener.TrainActivationListener;
import com.automatedtrains.train.AttachmentManager;
import com.automatedtrains.train.ChunkTicketManager;
import com.automatedtrains.train.Train;
import com.automatedtrains.train.TrainManager;
import com.automatedtrains.train.TrainTickTask;
import com.automatedtrains.util.Keys;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Wires together the train registry, the per-tick automation engine, chunk
 * ticketing, cosmetic attachments and every listener/command, then starts the 1-tick
 * repeating automation loop.
 */
public final class AutomatedTrains extends JavaPlugin {

    private Keys keys;
    private TrainManager trainManager;
    private ChunkTicketManager chunkTicketManager;
    private AttachmentManager attachmentManager;
    private TrainTickTask tickTask;
    private ChunkLoadListener chunkLoadListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        double speed = getConfig().getDouble("speed", 0.2);
        double spacing = getConfig().getDouble("spacing", 1.5);
        int stationWaitTicks = (int) Math.round(getConfig().getDouble("station-wait-seconds", 10.0) * 20.0);
        double trainStopDistance = getConfig().getDouble("train-stop-distance", 10.0);
        int junctionLockTimeoutTicks = (int) Math.round(getConfig().getDouble("junction-lock-timeout-seconds", 30.0) * 20.0);

        this.keys = new Keys(this);
        this.trainManager = new TrainManager(keys);
        this.chunkTicketManager = new ChunkTicketManager(this);
        this.attachmentManager = new AttachmentManager();
        this.chunkLoadListener = new ChunkLoadListener(trainManager);
        this.tickTask = new TrainTickTask(this, trainManager, chunkTicketManager, attachmentManager, keys, speed, spacing, stationWaitTicks,
                trainStopDistance, junctionLockTimeoutTicks);

        registerListeners();
        registerCommand();

        // Requirement #8: reconstruct any train sitting in a chunk that was already
        // loaded before this plugin finished starting up (e.g. spawn chunks on boot).
        chunkLoadListener.scanAlreadyLoadedChunks(this);

        tickTask.runTaskTimer(this, 1L, 1L);

        getLogger().info("AutomatedTrains enabled - " + trainManager.getAllTrains().size() + " train(s) resumed on boot. Speed=" + speed + " blocks/tick.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (trainManager != null) {
            for (Train train : new java.util.ArrayList<>(trainManager.getAllTrains())) {
                // Belt-and-suspenders final save: direction is already written every moving tick,
                // but this guarantees every field is confirmed current right before shutdown
                // rather than relying solely on whatever the last incremental save happened to be.
                org.bukkit.entity.Minecart lead = trainManager.getEntity(train.getLeadCartId());
                if (lead != null) {
                    train.saveTo(lead, keys);
                }
                // Release every held chunk ticket so nothing stays force-loaded after the plugin stops.
                if (chunkTicketManager != null) {
                    chunkTicketManager.releaseAll(train);
                }
            }
        }
    }

    private void registerListeners() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(chunkLoadListener, this);
        pm.registerEvents(new CouplingListener(trainManager, keys), this);
        pm.registerEvents(new TrainActivationListener(trainManager, keys), this);
        pm.registerEvents(new AttachmentListener(trainManager, attachmentManager), this);
        pm.registerEvents(new CartBreakListener(trainManager, attachmentManager, chunkTicketManager, keys), this);
        pm.registerEvents(new CollisionListener(trainManager), this);
        pm.registerEvents(new PlayerDisconnectListener(trainManager), this);
    }

    private void registerCommand() {
        TrainCommand trainCommand = new TrainCommand(trainManager, chunkTicketManager, keys, tickTask);
        var command = getCommand("train");
        if (command != null) {
            command.setExecutor(trainCommand);
            command.setTabCompleter(trainCommand);
        }
    }

    public TrainManager getTrainManager() {
        return trainManager;
    }

    public AttachmentManager getAttachmentManager() {
        return attachmentManager;
    }

    public ChunkTicketManager getChunkTicketManager() {
        return chunkTicketManager;
    }

    public Keys getKeys() {
        return keys;
    }
}
