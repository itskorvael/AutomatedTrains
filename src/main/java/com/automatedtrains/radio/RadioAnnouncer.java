package com.automatedtrains.radio;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Optional bridge to the RadioNetwork plugin: turns a station stop into a free radio announcement
 * by running {@code /radio announce} as the console. There is no compile-time dependency; when
 * RadioNetwork is absent (or announcements are disabled) every call is a silent no-op.
 */
public final class RadioAnnouncer {

    private static final String RADIO_PLUGIN = "RadioNetwork";
    private static final String DEFAULT_ARRIVAL = "Train arriving at {station} for {destination}.";
    private static final String DEFAULT_ARRIVAL_NO_DESTINATION = "Train arriving at {station}.";
    private static final String DEFAULT_DEPARTURE = "Train departing {station} for {destination}.";
    private static final String DEFAULT_DEPARTURE_NO_DESTINATION = "Train departing {station}.";

    private final Plugin plugin;

    public RadioAnnouncer(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("announcements.enabled", true)
                && Bukkit.getPluginManager().isPluginEnabled(RADIO_PLUGIN);
    }

    /**
     * Announces a train arriving at the station whose sign is at {@code signLocation}. The sign's
     * radio channel doubles as the line name, so channel "Northern Line" announces
     * "Train arriving at Port for Harbor." The destination is the one the station
     * sign just gave the train; it may be blank or "None".
     */
    public void arrival(Location signLocation, String station, String channel, String destination) {
        boolean hasDestination = hasDestination(destination);
        String template = hasDestination
                ? config().getString("announcements.arrival", DEFAULT_ARRIVAL)
                : config().getString("announcements.arrival-no-destination", DEFAULT_ARRIVAL_NO_DESTINATION);
        send(signLocation, channel, fill(template, station, hasDestination ? destination : "", channel));
    }

    /** Announces departure; {@code destination} may be blank or "None" when the train has no route set. */
    public void departure(Location signLocation, String station, String channel, String destination) {
        boolean hasDestination = hasDestination(destination);
        String template = hasDestination
                ? config().getString("announcements.departure", DEFAULT_DEPARTURE)
                : config().getString("announcements.departure-no-destination", DEFAULT_DEPARTURE_NO_DESTINATION);
        send(signLocation, channel, fill(template, station, hasDestination ? destination : "", channel));
    }

    private static boolean hasDestination(String destination) {
        return destination != null && !destination.isBlank() && !destination.equalsIgnoreCase("None");
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    static String fill(String template, String station, String destination, String line) {
        return template.replace("{line}", line).replace("{station}", station).replace("{destination}", destination);
    }

    private void send(Location sign, String channel, String message) {
        if (!enabled() || sign.getWorld() == null) {
            return;
        }
        // Sign text goes into a console command line, so strip anything that could break the
        // "<channel> | <message>" format or smuggle formatting. Commands are never chained by Bukkit.
        String cleanChannel = clean(channel.replace("|", ""));
        String cleanMessage = clean(message);
        if (cleanChannel.isEmpty() || cleanMessage.isEmpty()) {
            return;
        }
        String command = "radio announce " + sign.getWorld().getKey() + " "
                + sign.getBlockX() + " " + sign.getBlockY() + " " + sign.getBlockZ() + " "
                + cleanChannel + " | " + cleanMessage;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private static String clean(String text) {
        return text.replace("§", "").replaceAll("\\p{Cntrl}", "").trim();
    }
}
