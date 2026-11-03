package net.potendpound;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grey + black nametags: the team colour paints the name black, the team prefix/suffix
 * are grey. A player's previous team (if any) is remembered and restored afterwards.
 */
public final class NametagManager {

    private static final String TEAM = "potendpound_holder";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PotendPoundPlugin plugin;
    private final PotendItem items;
    private final Map<String, String> previousTeam = new HashMap<>();
    private Scoreboard board;
    private Team team;
    private BukkitTask task;

    public NametagManager(PotendPoundPlugin plugin, PotendItem items) {
        this.plugin = plugin;
        this.items = items;
    }

    public void start() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("nametag.enabled", true)) return;
        board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team stale = board.getTeam(TEAM);
        if (stale != null) stale.unregister();
        team = board.registerNewTeam(TEAM);
        team.color(NamedTextColor.NAMES.valueOr(
                cfg.getString("nametag.name-color", "BLACK").toLowerCase(Locale.ROOT), NamedTextColor.BLACK));
        team.prefix(MM.deserialize(cfg.getString("nametag.prefix", "<gray>✦ ")));
        team.suffix(MM.deserialize(cfg.getString("nametag.suffix", " <gray>✦")));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 10L);
    }

    private void update() {
        if (team == null) return;
        boolean heldOnly = plugin.getConfig().getBoolean("nametag.holding-only", false);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean want = heldOnly ? items.holding(p) : items.hasAny(p);
            boolean in = team.hasEntry(p.getName());
            if (want && !in) enter(p);
            else if (!want && in) leave(p);
        }
    }

    public void enter(Player p) {
        if (team == null) return;
        String name = p.getName();
        Team prev = board.getEntryTeam(name);
        if (prev != null && !prev.equals(team)) previousTeam.put(name, prev.getName());
        team.addEntry(name);
    }

    public void leave(Player p) {
        if (team == null) return;
        String name = p.getName();
        if (!team.hasEntry(name)) return;
        team.removeEntry(name);
        String prev = previousTeam.remove(name);
        if (prev != null) {
            Team old = board.getTeam(prev);
            if (old != null) old.addEntry(name);
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        if (team == null) return;
        for (String entry : Set.copyOf(team.getEntries())) {
            team.removeEntry(entry);
            String prev = previousTeam.remove(entry);
            if (prev != null) {
                Team old = board.getTeam(prev);
                if (old != null) old.addEntry(entry);
            }
        }
        team.unregister();
        team = null;
    }
}
