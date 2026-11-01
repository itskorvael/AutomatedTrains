package com.automatedtrains.listener;

import com.automatedtrains.train.TrainManager;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Leaving a rider attached to a train cart through the normal server-shutdown/disconnect
 * teardown process was leaving the cart in a bad state - a train would come back from a
 * restart already derailed, or a running train would just sit there after its rider left,
 * looking exactly like it was "waiting" for them to come back. Neither is intended: a train
 * doesn't need anyone aboard to keep running, so a disconnecting rider is force-dismounted
 * the instant they leave, cleanly, on our own terms, well before vanilla's own disconnect
 * handling gets anywhere near the vehicle - the train itself is completely unaffected and
 * just keeps heading whichever way it already was.
 */
public class PlayerDisconnectListener implements Listener {

    private final TrainManager trainManager;

    public PlayerDisconnectListener(TrainManager trainManager) {
        this.trainManager = trainManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!(player.getVehicle() instanceof Minecart cart)) return;
        if (trainManager.findTrainForCart(cart.getUniqueId()) == null) return; // not a train cart - leave vanilla behaviour alone

        player.leaveVehicle();
    }
}
