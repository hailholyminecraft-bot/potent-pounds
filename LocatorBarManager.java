package net.potendpound;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * The locator bar is only shown to players carrying a Dragon Egg.
 *
 * Everyone else gets waypoint_receive_range = 0 (their locator bar is off). Egg carriers get an
 * explicit huge range. Note: AttributeInstance#getDefaultValue() is NOT the player's normal range
 * (it is 0 for this attribute), so the range is set explicitly - that was why the bar never showed.
 * Everybody's transmit range is also raised so egg carriers can actually see them.
 */
public final class LocatorBarManager {

    private final PotendPoundPlugin plugin;
    private BukkitTask task;

    public LocatorBarManager(PotendPoundPlugin plugin) {
        this.plugin = plugin;
    }

    private double range() {
        return plugin.getConfig().getDouble("locator-bar.range", 60_000_000.0);
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("locator-bar.enabled", true)) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) apply(p);
        }, 10L, 10L);
    }

    public void apply(Player p) {
        if (!plugin.getConfig().getBoolean("locator-bar.enabled", true)) return;
        AttributeInstance receive = p.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (receive != null) {
            boolean hasEgg = p.getInventory().contains(Material.DRAGON_EGG)
                    || p.getItemOnCursor().getType() == Material.DRAGON_EGG;
            double target = hasEgg ? range() : 0.0;
            if (receive.getBaseValue() != target) receive.setBaseValue(target);
        }
        AttributeInstance transmit = p.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (transmit != null && transmit.getBaseValue() < range()) transmit.setBaseValue(range());
    }

    /** Gives the player their normal locator bar back (quit / shutdown) so nothing stays switched off. */
    public void reset(Player p) {
        AttributeInstance receive = p.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (receive != null) receive.setBaseValue(range());
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (Player p : Bukkit.getOnlinePlayers()) reset(p);
    }
}
