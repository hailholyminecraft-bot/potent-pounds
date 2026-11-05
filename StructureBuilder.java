package net.potendpound;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Shared block-placing + undo logic for the chamber and the village. */
public abstract class StructureBuilder {

    public static final String TAG_BUILD = "potendpound_build";
    protected static final MiniMessage MM = MiniMessage.miniMessage();
    protected static boolean busy;

    private record Saved(int x, int y, int z, BlockData data) {}

    protected final PotendPoundPlugin plugin;
    protected World world;
    protected int cx, y0, cz;
    protected final Random rnd = new Random();
    private List<Saved> undo = new ArrayList<>();
    private Set<Long> seen = new HashSet<>();
    private int boxR, boxUp, boxDown;

    protected StructureBuilder(PotendPoundPlugin plugin) {
        this.plugin = plugin;
    }

    protected void begin(Location l, int radius, int up, int down) {
        world = l.getWorld();
        cx = l.getBlockX();
        y0 = l.getBlockY();
        cz = l.getBlockZ();
        boxR = radius;
        boxUp = up;
        boxDown = down;
        undo = new ArrayList<>();
        seen = new HashSet<>();
    }

    protected static Material firstOf(String... names) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) return m;
        }
        return null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    protected void set(int x, int y, int z, BlockData data) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        Block b = world.getBlockAt(x, y, z);
        if (seen.add(key(x, y, z))) undo.add(new Saved(x, y, z, b.getBlockData()));
        b.setBlockData(data, false);
    }

    protected void set(int x, int y, int z, Material m) {
        set(x, y, z, m.createBlockData());
    }

    protected void rel(int dx, int dy, int dz, Material m) {
        set(cx + dx, y0 + dy, cz + dz, m);
    }

    protected void rel(int dx, int dy, int dz, BlockData d) {
        set(cx + dx, y0 + dy, cz + dz, d);
    }

    protected Material at(int dx, int dy, int dz) {
        return world.getBlockAt(cx + dx, y0 + dy, cz + dz).getType();
    }

    protected Material absAt(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType();
    }

    /** Runs the tasks a few per tick so big builds never freeze the server. */
    protected void runTasks(List<Runnable> tasks, int perTick, Runnable done) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                for (int n = 0; n < perTick && i < tasks.size(); n++, i++) tasks.get(i).run();
                if (i >= tasks.size()) {
                    cancel();
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    protected void removeBuildEntities() {
        BoundingBox box = new BoundingBox(cx - boxR, y0 - boxDown, cz - boxR, cx + boxR + 1, y0 + boxUp, cz + boxR + 1);
        for (Entity en : world.getNearbyEntities(box)) {
            if (en.getScoreboardTags().contains(TAG_BUILD)) en.remove();
        }
    }

    public boolean hasUndo() {
        return world != null && !undo.isEmpty();
    }

    public void undo(CommandSender sender) {
        if (busy) {
            sender.sendMessage(MM.deserialize("<red>Busy - try again in a moment."));
            return;
        }
        if (!hasUndo()) {
            sender.sendMessage(MM.deserialize("<red>Nothing to undo."));
            return;
        }
        busy = true;
        removeBuildEntities();
        final List<Saved> list = undo;
        undo = new ArrayList<>();
        seen = new HashSet<>();
        new BukkitRunnable() {
            int i = list.size() - 1;

            @Override
            public void run() {
                for (int n = 0; n < 8000 && i >= 0; n++, i--) {
                    Saved s = list.get(i);
                    world.getBlockAt(s.x(), s.y(), s.z()).setBlockData(s.data(), false);
                }
                if (i < 0) {
                    cancel();
                    busy = false;
                    sender.sendMessage(MM.deserialize("<gray>Structure removed, the area is restored."));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
