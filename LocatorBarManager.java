package net.potendpound;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * The locator bar is only shown to players carrying a Dragon Egg. Everyone else gets
 * waypoint_receive_range = 0, which turns their locator bar off; egg carriers get the
 * default (huge) range back so they see every other player.
 */
public final class LocatorBarManager {

    private final PotendPoundPlugin plugin;
    private BukkitTask task;

    public LocatorBarManager(PotendPoundPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("locator-bar.enabled", true)) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) apply(p);
        }, 20L, 20L);
    }

    public void apply(Player p) {
        if (!plugin.getConfig().getBoolean("locator-bar.enabled", true)) return;
        AttributeInstance range = p.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (range == null) return;
        boolean hasEgg = p.getInventory().contains(Material.DRAGON_EGG)
                || p.getItemOnCursor().getType() == Material.DRAGON_EGG;
        double target = hasEgg ? range.getDefaultValue() : 0.0;
        if (range.getBaseValue() != target) range.setBaseValue(target);
    }

    /** Gives the player their normal locator bar back (used on quit / shutdown so nothing stays switched off). */
    public void reset(Player p) {
        AttributeInstance range = p.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (range != null) range.setBaseValue(range.getDefaultValue());
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (Player p : Bukkit.getOnlinePlayers()) reset(p);
    }
}
