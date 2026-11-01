package com.automatedtrains.listener;

import com.automatedtrains.train.AttachmentManager;
import com.automatedtrains.train.Train;
import com.automatedtrains.train.TrainManager;
import com.automatedtrains.train.TrainTickTask;
import com.automatedtrains.util.PermissionUtil;
import com.automatedtrains.util.RailUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets an authorized player shift-right-click a coupled minecart to attach cosmetics: a
 * Map spawns a floating GlowItemFrame, and a Sign spawns an editable text headboard.
 * Either one appears exactly where you clicked - there's no FRONT/LEFT/RIGHT bucketing
 * to get wrong, it just sticks to whichever direction you were standing in relative to
 * the cart, and keeps that same relative spot as the cart moves and turns.
 *
 * <p>The headboard sign is a {@link TextDisplay} paired with an invisible, same-sized
 * {@link Interaction} entity riding along with it - Display entities aren't clickable in
 * vanilla, so the Interaction entity is what actually receives the shift-right-click that
 * opens the text editor, the same trick the original design doc suggested for passenger
 * layering. Neither attachment ever becomes a vehicle passenger of the cart (see
 * {@link AttachmentManager}), so a real player can always still shift-click the cart
 * itself to sit inside it.
 */
public class AttachmentListener implements Listener {

    // Vanilla minecart footprint is ~0.98 blocks wide (half-width ~0.49) - 0.7 clears it with
    // enough margin for the display panel's own thickness so it doesn't clip back into the cart.
    // Must match TrainTickTask's copy of these constants exactly, or the attachment will jump
    // between attach-time and the next tick's reposition.
    private static final double ATTACH_DISTANCE = 0.7;
    private static final double ATTACH_HEIGHT = 0.35;

    private final TrainManager trainManager;
    private final AttachmentManager attachmentManager;
    // AsyncChatEvent fires off the main thread, so this needs real thread-safety, not just a plain HashMap.
    private final Map<UUID, UUID> pendingSignEdits = new ConcurrentHashMap<>(); // player -> text display entity

    public AttachmentListener(TrainManager trainManager, AttachmentManager attachmentManager) {
        this.trainManager = trainManager;
        this.attachmentManager = attachmentManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();

        // Shift-right-clicking an already-placed sign's invisible click target opens the editor.
        if (event.getRightClicked() instanceof Interaction interaction) {
            UUID displayId = attachmentManager.getDisplayForCompanion(interaction.getUniqueId());
            if (displayId == null) return;
            if (!player.isSneaking()) return;

            event.setCancelled(true);
            UUID cartId = attachmentManager.getOwningCart(interaction.getUniqueId());
            Train train = cartId != null ? trainManager.findTrainForCart(cartId) : null;
            if (train != null && !PermissionUtil.hasAccess(player, train)) {
                player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
                return;
            }
            beginEdit(player, displayId);
            return;
        }

        if (!(event.getRightClicked() instanceof Minecart cart)) return;
        if (!player.isSneaking()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        boolean isMap = item.getType() == Material.MAP || item.getType() == Material.FILLED_MAP;
        boolean isSign = item.getType().name().endsWith("_SIGN") && !item.getType().name().endsWith("_HANGING_SIGN");
        if (!isMap && !isSign) return;

        Train train = trainManager.findTrainForCart(cart.getUniqueId());
        if (train == null) {
            player.sendMessage(Component.text("Error: Couple this cart into a train before attaching cosmetics!", NamedTextColor.RED));
            return;
        }
        if (!PermissionUtil.hasAccess(player, train)) {
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        Vector clickDirection = resolveClickDirection(event, cart, player);
        Vector localOffset = RailUtil.toLocalOffset(railForward(cart, train), clickDirection);

        if (isMap) {
            attachMap(cart, item, clickDirection, localOffset, player);
        } else {
            attachSign(cart, item, clickDirection, localOffset, player);
        }
    }

    /**
     * Must resolve to the exact same vector {@link TrainTickTask#resolveAttachmentForward}
     * would compute for this cart right now - it's the reference frame an attachment's
     * localOffset gets encoded against here at attach time, and decoded against on every
     * later tick. If the two ever disagree (e.g. one uses a neutral tie-break seed and the
     * other uses the train's actual travel direction), the attachment flips to the opposite
     * side of the cart the instant those seeds diverge - which happens any time the train's
     * real direction differs from the neutral default, i.e. very easily. Seeding both with
     * the train's own lastDirection keeps them permanently in agreement.
     */
    /**
     * Uses the actual clicked point on the cart's hitbox (from {@link PlayerInteractAtEntityEvent},
     * which fires alongside the base event for every entity right-click) to get a direction that
     * genuinely corresponds to whichever side was clicked. The previous approach approximated this
     * from the player's own standing position relative to the cart's center, which could collapse
     * to a near-zero vector - and did, often - whenever the player was standing close to directly
     * above/on the cart, placing the attachment almost exactly at the cart's own center instead of
     * on its side.
     */
    private Vector resolveClickDirection(PlayerInteractEntityEvent event, Minecart cart, Player player) {
        if (event instanceof PlayerInteractAtEntityEvent atEvent) {
            BoundingBox box = cart.getBoundingBox();
            double halfWidthX = (box.getMaxX() - box.getMinX()) / 2.0;
            double halfWidthZ = (box.getMaxZ() - box.getMinZ()) / 2.0;
            Vector clicked = atEvent.getClickedPosition(); // relative to the hitbox's own minimum corner
            Vector direction = new Vector(clicked.getX() - halfWidthX, 0, clicked.getZ() - halfWidthZ);
            if (direction.lengthSquared() > 1.0E-6) {
                return direction.normalize();
            }
        }

        // Fallback for the rare case the AT-entity variant isn't available: approximate from stance.
        Vector fallback = player.getLocation().toVector().subtract(cart.getLocation().toVector());
        fallback.setY(0);
        if (fallback.lengthSquared() < 1.0E-6) fallback = new Vector(0, 0, 1);
        return fallback.normalize();
    }

    private Vector railForward(Minecart cart, Train train) {
        Vector seed = train != null ? train.lastDirection : new Vector(0, 0, 1);
        Block railBlock = RailUtil.findRailBlock(cart.getLocation(), null);
        if (railBlock == null) return seed.lengthSquared() > 1.0E-6 ? seed.clone().normalize() : new Vector(0, 0, 1);
        Vector direction = RailUtil.computeForwardDirection(railBlock, seed);
        direction.setY(0);
        return direction.lengthSquared() > 1.0E-6 ? direction.normalize() : new Vector(0, 0, 1);
    }

    private void attachMap(Minecart cart, ItemStack mapItem, Vector direction, Vector localOffset, Player player) {
        Location spawnLoc = cart.getLocation().clone().add(direction.clone().multiply(ATTACH_DISTANCE)).add(0, ATTACH_HEIGHT, 0);
        spawnLoc.setDirection(direction);

        GlowItemFrame frame = cart.getWorld().spawn(spawnLoc, GlowItemFrame.class, f -> {
            f.setFacingDirection(nearestFace(direction), true);
            f.setItem(mapItem.clone().asOne());
            f.setFixed(true);
            f.setVisible(true);
            f.setInvulnerable(true);
        });

        attachmentManager.register(cart.getUniqueId(), frame.getUniqueId(), null, AttachmentManager.Kind.MAP, localOffset);
        consumeOne(player);
        player.sendMessage(Component.text("Attached map.", NamedTextColor.GREEN));
    }

    private void attachSign(Minecart cart, ItemStack signItem, Vector direction, Vector localOffset, Player player) {
        Location spawnLoc = cart.getLocation().clone().add(direction.clone().multiply(ATTACH_DISTANCE)).add(0, ATTACH_HEIGHT, 0);
        spawnLoc.setDirection(direction);

        TextDisplay display = cart.getWorld().spawn(spawnLoc, TextDisplay.class, d -> {
            d.text(Component.text("New Train", NamedTextColor.BLACK));
            d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setLineWidth(100);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(180, 172, 129, 83)); // oak-ish backdrop
            d.setInvulnerable(true);
            applyRotation(d, direction);
        });

        Interaction interaction = cart.getWorld().spawn(spawnLoc, Interaction.class, i -> {
            i.setInteractionWidth(0.8f);
            i.setInteractionHeight(0.5f);
            i.setInvulnerable(true);
        });

        attachmentManager.register(cart.getUniqueId(), display.getUniqueId(), interaction.getUniqueId(), AttachmentManager.Kind.SIGN, localOffset);
        consumeOne(player);
        player.sendMessage(Component.text("Attached headboard sign. Shift-right-click it to add text.", NamedTextColor.GREEN));
    }

    private void beginEdit(Player player, UUID displayId) {
        pendingSignEdits.put(player.getUniqueId(), displayId);
        player.sendMessage(Component.text("Type the sign's new text in chat (use | to separate up to 4 lines), or 'cancel'.", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID displayId = pendingSignEdits.get(player.getUniqueId());
        if (displayId == null) return;

        event.setCancelled(true);
        pendingSignEdits.remove(player.getUniqueId());

        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        Entity entity = org.bukkit.Bukkit.getEntity(displayId);
        if (!(entity instanceof TextDisplay display) || !display.isValid()) {
            player.sendMessage(Component.text("Error: That sign no longer exists.", NamedTextColor.RED));
            return;
        }

        if (raw.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Edit cancelled.", NamedTextColor.YELLOW));
            return;
        }

        String[] lines = raw.split("\\|", 4);
        List<Component> components = new ArrayList<>();
        for (String line : lines) {
            components.add(Component.text(line.strip(), NamedTextColor.BLACK));
        }

        Component builder = Component.empty();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) builder = builder.append(Component.newline());
            builder = builder.append(components.get(i));
        }
        final Component joined = builder;

        // Applying to a live entity must happen back on the main thread - chat events fire off it.
        org.bukkit.Bukkit.getScheduler().runTask(getOwningPlugin(), () -> {
            if (display.isValid()) {
                display.text(joined);
            }
        });

        player.sendMessage(Component.text("Sign updated.", NamedTextColor.GREEN));
    }

    private org.bukkit.plugin.Plugin getOwningPlugin() {
        return org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
    }

    private void applyRotation(TextDisplay display, Vector facing) {
        double angle = Math.atan2(facing.getX(), -facing.getZ()) - Math.PI / 2.0; // sign panel was showing edge-on; turn it a quarter turn to face outward
        Quaternionf rotation = new Quaternionf(new AxisAngle4f((float) angle, 0, 1, 0));
        display.setTransformation(new Transformation(new Vector3f(), rotation, new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
    }

    private void consumeOne(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
    }

    private org.bukkit.block.BlockFace nearestFace(Vector direction) {
        double ax = Math.abs(direction.getX());
        double az = Math.abs(direction.getZ());
        if (ax > az) {
            return direction.getX() > 0 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST;
        }
        return direction.getZ() > 0 ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
    }

    /** Our attachments float without a supporting block, so cancel the vanilla "no support" auto-break. */
    @EventHandler
    public void onHangingBreak(HangingBreakEvent event) {
        Entity entity = event.getEntity();
        if (attachmentManager.getOwningCart(entity.getUniqueId()) == null) return;
        if (event.getCause() == HangingBreakEvent.RemoveCause.PHYSICS
                || event.getCause() == HangingBreakEvent.RemoveCause.OBSTRUCTION) {
            event.setCancelled(true);
        }
    }

    /** Only the train's owner/trusted/admin may manually break an attached frame. */
    @EventHandler
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        Entity entity = event.getEntity();
        var cartId = attachmentManager.getOwningCart(entity.getUniqueId());
        if (cartId == null) return;
        if (!(event.getRemover() instanceof Player player)) return;

        Train train = trainManager.findTrainForCart(cartId);
        if (train != null && !PermissionUtil.hasAccess(player, train)) {
            event.setCancelled(true);
            player.sendMessage(Component.text(PermissionUtil.ACCESS_DENIED, NamedTextColor.RED));
        }
    }
}
