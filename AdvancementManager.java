package net.potendpound;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Custom advancement "The Weight of the Sky": awarded the first time a player has a Potent Pound in
 * their inventory (taking it from the altar, /give, trading... anything).
 */
public final class AdvancementManager {

    private static final String JSON = """
            {
              "display": {
                "icon": {"id": "minecraft:mace", "components": {"minecraft:item_model": "potendpound:potend_pound"}},
                "title": {"text": "The Weight of the Sky", "color": "gray"},
                "description": {"text": "Take the Potent Pound from the altar of the ruined trial chamber"},
                "background": "minecraft:textures/gui/advancements/backgrounds/stone.png",
                "frame": "challenge",
                "show_toast": true,
                "announce_to_chat": true,
                "hidden": false
              },
              "criteria": {"obtained": {"trigger": "minecraft:impossible"}},
              "requirements": [["obtained"]],
              "rewards": {"experience": 200}
            }
            """;

    private final PotendPoundPlugin plugin;
    private final PotendItem items;
    private NamespacedKey key;
    private BukkitTask task;

    public AdvancementManager(PotendPoundPlugin plugin, PotendItem items) {
        this.plugin = plugin;
        this.items = items;
    }

    @SuppressWarnings("deprecation")
    public void start() {
        key = new NamespacedKey(plugin, "take_the_pound");
        try {
            if (Bukkit.getAdvancement(key) == null) Bukkit.getUnsafe().loadAdvancement(key, JSON);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not load the custom advancement: " + t.getMessage());
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (items.hasAny(p)) grant(p);
        }, 40L, 20L);
    }

    public void grant(Player p) {
        Advancement adv = Bukkit.getAdvancement(key);
        if (adv == null) return;
        AdvancementProgress progress = p.getAdvancementProgress(adv);
        if (!progress.isDone()) progress.awardCriteria("obtained");
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
    }
}
