package com.automatedtrains.listener;

import com.automatedtrains.train.Train;
import com.automatedtrains.train.TrainManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.util.Vector;

/**
 * "Cow catcher" behaviour for automated trains: a train must never lose speed or stop
 * because something wandered onto the tracks. Mobs get blasted clear (and take a little
 * damage so they learn to stay off the rails); players get a gentle safety shove instead
 * of being run over or wedged against the cart; and carts belonging to the same train
 * never register a collision with each other at all.
 */
public class CollisionListener implements Listener {

    private static final double MOB_DAMAGE = 6.0;
    private static final double MOB_PUSH_HORIZONTAL = 1.1;
    private static final double MOB_PUSH_VERTICAL = 0.5;
    private static final double PLAYER_PUSH_HORIZONTAL = 0.45;
    private static final double PLAYER_PUSH_VERTICAL = 0.2;

    private final TrainManager trainManager;

    public CollisionListener(TrainManager trainManager) {
        this.trainManager = trainManager;
    }

    @EventHandler
    public void onVehicleEntityCollision(VehicleEntityCollisionEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;

        Train train = trainManager.findTrainForCart(cart.getUniqueId());
        // Un-automated carts (not part of a train, or a train that hasn't been started yet)
        // keep normal vanilla collision behaviour - that's also what lets a player "push"
        // an idle train's lead cart to start it, see TrainActivationListener.
        if (train == null || !train.automationEnabled) return;

        Entity hit = event.getEntity();

        // Never register a collision between two carts of the exact same train.
        if (hit instanceof Minecart otherCart) {
            Train otherTrain = trainManager.findTrainForCart(otherCart.getUniqueId());
            if (otherTrain != null && otherTrain.getLeadCartId().equals(train.getLeadCartId())) {
                return;
            }
        }

        // Bypass vanilla's slowdown-on-collision logic entirely - the train keeps its speed.
        event.setCancelled(true);

        Vector forward = train.lastDirection.lengthSquared() > 1.0E-6
                ? train.lastDirection.clone().normalize()
                : new Vector(0, 0, 1);
        Vector side = new Vector(-forward.getZ(), 0, forward.getX()).normalize();

        Vector toEntity = hit.getLocation().toVector().subtract(cart.getLocation().toVector());
        double sideSign = side.dot(toEntity) >= 0 ? 1.0 : -1.0;
        Vector pushSide = side.clone().multiply(sideSign);

        if (hit instanceof Player player) {
            if (cart.getPassengers().isEmpty()) {
                // Cart is still empty - this may well be a player lining up to right-click and
                // board it, so don't shove them away; just let the vehicle glide through.
                return;
            }
            Vector knockback = pushSide.clone().multiply(PLAYER_PUSH_HORIZONTAL);
            knockback.setY(PLAYER_PUSH_VERTICAL);
            player.setVelocity(player.getVelocity().add(knockback));
            return;
        }

        if (hit instanceof LivingEntity living) {
            Vector knockback = pushSide.clone().multiply(MOB_PUSH_HORIZONTAL);
            knockback.setY(MOB_PUSH_VERTICAL);
            living.setVelocity(knockback);
            living.damage(MOB_DAMAGE);
            return;
        }

        // Any other obstruction (e.g. a foreign, un-owned minecart) - nudge it clear, no damage.
        Vector nudge = pushSide.clone().multiply(0.6);
        nudge.setY(0.2);
        hit.setVelocity(nudge);
    }
}
