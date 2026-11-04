package net.potendpound;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds an abandoned, half-destroyed trial chamber (tuff bricks + weathered copper, vines, cobwebs,
 * holes in the floor and walls) with an altar in the middle that holds the Potent Pound.
 * Everything it overwrites is remembered, so /potendpound undo restores the area.
 */
public final class TrialChamber {

    public static final String TAG_ALTAR = "potendpound_altar";
    public static final String TAG_ITEM = "potendpound_altar_item";

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int R = 15;        // interior half-size -> 31 x 31
    private static final int H = 12;        // interior height
    private static final int T = 2;         // wall / floor / ceiling thickness
    private static final int E = R + T;
    private static final Material CHAIN = firstOf("IRON_CHAIN", "CHAIN");

    private record Saved(int x, int y, int z, BlockData data) {}

    private final PotendPoundPlugin plugin;
    private final PotendItem items;
    private boolean busy;

    private World world;
    private int cx, y0, cz;
    private Random rnd;
    private float[][] noise;
    private List<Saved> undo = new ArrayList<>();
    private Set<Long> seen = new HashSet<>();

    public TrialChamber(PotendPoundPlugin plugin, PotendItem items) {
        this.plugin = plugin;
        this.items = items;
    }

    private static Material firstOf(String... names) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) return m;
        }
        return null;
    }

    // ================================================================== public API

    public boolean build(Player p) {
        if (busy) return false;
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (loc.getBlockY() - T < w.getMinHeight() + 1 || loc.getBlockY() + H + T + 6 >= w.getMaxHeight()) {
            p.sendMessage(MM.deserialize("<red>Not enough vertical room here."));
            return true;
        }
        busy = true;
        world = w;
        cx = loc.getBlockX();
        y0 = loc.getBlockY();
        cz = loc.getBlockZ();
        rnd = new Random();
        noise = makeNoise(E * 2 + 1);
        undo = new ArrayList<>();
        seen = new HashSet<>();
        p.sendMessage(MM.deserialize("<gray>Raising the ruined chamber... <dark_gray>(/potendpound undo reverts it)"));

        new BukkitRunnable() {
            int dy = -T;

            @Override
            public void run() {
                for (int n = 0; n < 2 && dy < H + T; n++, dy++) buildLayer(dy);
                if (dy >= H + T) {
                    cancel();
                    buildAltar();
                    decay();
                    spawnAltarEntities();
                    p.teleport(new Location(world, cx + R - 2 + 0.5, y0, cz + 0.5, 90f, 0f));
                    p.sendMessage(MM.deserialize("<gray>The chamber is ready. Right-click the <white>Potent Pound</white> on the altar."));
                    busy = false;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    public void undo(CommandSender sender) {
        if (busy) {
            sender.sendMessage(MM.deserialize("<red>Busy - try again in a moment."));
            return;
        }
        if (world == null || undo.isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Nothing to undo (only the last chamber since the last restart is remembered)."));
            return;
        }
        busy = true;
        // remove altar entities first
        BoundingBox box = new BoundingBox(cx - E, y0 - 4, cz - E, cx + E + 1, y0 + H + T + 6, cz + E + 1);
        for (Entity en : world.getNearbyEntities(box)) {
            if (en.getScoreboardTags().contains(TAG_ALTAR) || en.getScoreboardTags().contains(TAG_ITEM)) en.remove();
        }
        final List<Saved> list = undo;
        undo = new ArrayList<>();
        seen = new HashSet<>();
        new BukkitRunnable() {
            int i = list.size() - 1;

            @Override
            public void run() {
                for (int n = 0; n < 6000 && i >= 0; n++, i--) {
                    Saved s = list.get(i);
                    world.getBlockAt(s.x(), s.y(), s.z()).setBlockData(s.data(), false);
                }
                if (i < 0) {
                    cancel();
                    busy = false;
                    sender.sendMessage(MM.deserialize("<gray>Chamber removed, the area is restored."));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ================================================================== block helpers

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    private void set(int x, int y, int z, BlockData data) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        Block b = world.getBlockAt(x, y, z);
        if (seen.add(key(x, y, z))) undo.add(new Saved(x, y, z, b.getBlockData()));
        b.setBlockData(data, false);
    }

    private void set(int x, int y, int z, Material m) {
        set(x, y, z, m.createBlockData());
    }

    private void rel(int dx, int dy, int dz, Material m) {
        set(cx + dx, y0 + dy, cz + dz, m);
    }

    private Material at(int dx, int dy, int dz) {
        return world.getBlockAt(cx + dx, y0 + dy, cz + dz).getType();
    }

    private void bulb(int dx, int dy, int dz, boolean lit) {
        Material[] kinds = {Material.COPPER_BULB, Material.EXPOSED_COPPER_BULB, Material.WEATHERED_COPPER_BULB, Material.OXIDIZED_COPPER_BULB};
        BlockData d = kinds[rnd.nextInt(kinds.length)].createBlockData();
        if (d instanceof Lightable l) l.setLit(lit);
        set(cx + dx, y0 + dy, cz + dz, d);
    }

    // ================================================================== phase 1: shell, floor, pillars

    private float[][] makeNoise(int n) {
        float[][] a = new float[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) a[i][j] = rnd.nextFloat();
        for (int pass = 0; pass < 3; pass++) {
            float[][] b = new float[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    float sum = 0;
                    int c = 0;
                    for (int di = -1; di <= 1; di++) {
                        for (int dj = -1; dj <= 1; dj++) {
                            int x = i + di, y = j + dj;
                            if (x < 0 || y < 0 || x >= n || y >= n) continue;
                            sum += a[x][y];
                            c++;
                        }
                    }
                    b[i][j] = sum / c;
                }
            }
            a = b;
        }
        float min = 1, max = 0;
        for (float[] row : a) for (float v : row) { min = Math.min(min, v); max = Math.max(max, v); }
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) a[i][j] = (a[i][j] - min) / Math.max(1e-6f, max - min);
        return a;
    }

    private float n(int dx, int dz) {
        return noise[dx + E][dz + E];
    }

    private boolean isPillar(int dx, int dz) {
        boolean onX = Math.abs(dx) == R, onZ = Math.abs(dz) == R;
        if (!onX && !onZ) return false;
        if (onX && onZ) return true;
        int c = onX ? dz : dx;
        return Math.floorMod(c + 1, 6) <= 2;
    }

    private Material pillarMat(int dy) {
        return switch (dy % 4) {
            case 0 -> Material.CHISELED_TUFF_BRICKS;
            case 3 -> Material.POLISHED_TUFF;
            default -> Material.TUFF_BRICKS;
        };
    }

    private Material floorMat(float f, int dx, int dz) {
        boolean nearAltar = Math.max(Math.abs(dx), Math.abs(dz)) <= 7;
        if (nearAltar) {
            return f < 0.35f ? Material.OXIDIZED_CUT_COPPER : f < 0.6f ? Material.WEATHERED_COPPER
                    : f < 0.8f ? Material.EXPOSED_COPPER : Material.COPPER_BLOCK;
        }
        if (f < 0.18f) return Material.OXIDIZED_COPPER;
        if (f < 0.36f) return Material.OXIDIZED_CUT_COPPER;
        if (f < 0.50f) return Material.WEATHERED_COPPER;
        if (f < 0.58f) return Material.EXPOSED_COPPER;
        if (f < 0.64f) return Material.COPPER_BLOCK;
        if (f < 0.74f) return Material.TUFF_BRICKS;
        if (f < 0.82f) return Material.TUFF;
        if (f < 0.88f) return rnd.nextBoolean() ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE;
        if (f < 0.94f) return Material.MOSS_BLOCK;
        return Material.GRAVEL;
    }

    private Material shellMat(int dx, int dy, int dz) {
        float f = n(dx, dz);
        float g = (f + dy * 0.37f) % 1f;
        if (dy < 0) return dy == -1 ? floorMat(f, dx, dz) : (f < 0.5f ? Material.TUFF_BRICKS : Material.TUFF);
        if (dy >= H) return dy == H ? (((dx + dz) & 3) == 0 ? Material.CHISELED_TUFF_BRICKS : Material.TUFF_BRICKS) : Material.TUFF;
        if (dy == 2 || dy == 3) {                         // the weathered copper band around the walls
            return g < 0.25f ? Material.OXIDIZED_COPPER : g < 0.5f ? Material.WEATHERED_COPPER
                    : g < 0.8f ? Material.EXPOSED_COPPER : Material.COPPER_BLOCK;
        }
        if (dy == H - 1) return Material.CHISELED_TUFF_BRICKS;
        if (g < 0.07f) return Material.POLISHED_TUFF;
        if (g < 0.12f) return Material.TUFF;
        if (g > 0.97f) return Material.MOSS_BLOCK;
        return Material.TUFF_BRICKS;
    }

    private void buildLayer(int dy) {
        for (int dx = -E; dx <= E; dx++) {
            for (int dz = -E; dz <= E; dz++) {
                boolean inside = Math.abs(dx) <= R && Math.abs(dz) <= R && dy >= 0 && dy < H;
                if (inside) {
                    rel(dx, dy, dz, isPillar(dx, dz) ? pillarMat(dy) : Material.AIR);
                } else {
                    rel(dx, dy, dz, shellMat(dx, dy, dz));
                }
            }
        }
    }

    // ================================================================== phase 2: altar

    private void buildAltar() {
        int[] half = {4, 3, 2, 1, 0};
        for (int dy = 0; dy < 5; dy++) {
            int h = half[dy];
            for (int dx = -h; dx <= h; dx++) {
                for (int dz = -h; dz <= h; dz++) {
                    boolean edge = Math.abs(dx) == h || Math.abs(dz) == h;
                    boolean corner = Math.abs(dx) == h && Math.abs(dz) == h;
                    if (dy == 0 && corner && rnd.nextFloat() < 0.4f) { rel(dx, dy, dz, Material.AIR); continue; }   // broken corners
                    if (dy == 0 && edge && !corner && rnd.nextFloat() < 0.08f) { rel(dx, dy, dz, Material.AIR); continue; }
                    Material m;
                    switch (dy) {
                        case 0 -> {
                            boolean mid = (Math.abs(dx) == h && Math.abs(dz) <= 1) || (Math.abs(dz) == h && Math.abs(dx) <= 1);
                            m = mid ? Material.OXIDIZED_CHISELED_COPPER : edge ? Material.TUFF_BRICKS : Material.OXIDIZED_COPPER;
                        }
                        case 1 -> m = rnd.nextBoolean() ? Material.EXPOSED_CUT_COPPER : Material.WEATHERED_CUT_COPPER;
                        case 2 -> m = rnd.nextBoolean() ? Material.COPPER_BLOCK : Material.EXPOSED_COPPER;
                        case 3 -> m = rnd.nextFloat() < 0.7f ? Material.COPPER_BLOCK : Material.EXPOSED_COPPER;
                        default -> m = Material.POLISHED_ANDESITE;
                    }
                    rel(dx, dy, dz, m);
                }
            }
        }
        for (int sx : new int[]{-3, 3}) for (int sz : new int[]{-3, 3}) bulb(sx, 1, sz, true);   // glowing corners
    }

    // ================================================================== phase 3: decay

    private boolean nearAltar(int dx, int dz, int r) {
        return Math.max(Math.abs(dx), Math.abs(dz)) <= r;
    }

    private void carve(int dx0, int dy0, int dz0, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    int ax = dx0 + dx, ay = dy0 + dy, az = dz0 + dz;
                    if (ay < 0 || ay >= H + T) continue;
                    if (Math.abs(ax) > E || Math.abs(az) > E) continue;
                    if (nearAltar(ax, az, 6)) continue;
                    if (at(ax, ay, az) != Material.AIR) rel(ax, ay, az, Material.AIR);
                }
            }
        }
    }

    private void decay() {
        // --- holes in the floor (with rubble rims)
        for (int i = 0; i < 9; i++) {
            int hx = rnd.nextInt(2 * R - 1) - (R - 1), hz = rnd.nextInt(2 * R - 1) - (R - 1);
            if (nearAltar(hx, hz, 8)) continue;
            double rad = 1.5 + rnd.nextDouble() * 1.8;
            int depth = rnd.nextBoolean() ? 3 : 2;
            int r = (int) Math.ceil(rad + 1.3);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    int ax = hx + dx, az = hz + dz;
                    if (Math.abs(ax) > R || Math.abs(az) > R) continue;
                    if (d <= rad) {
                        for (int dy = -1; dy >= -depth; dy--) rel(ax, dy, az, Material.AIR);
                    } else if (d <= rad + 1.3 && rnd.nextFloat() < 0.55f) {
                        Material[] rim = {Material.COBBLESTONE, Material.TUFF, Material.GRAVEL, Material.COBBLED_DEEPSLATE};
                        rel(ax, -1, az, rim[rnd.nextInt(rim.length)]);
                    }
                }
            }
        }
        // --- breaches in the walls, pillars and ceiling
        for (int i = 0; i < 12; i++) {
            int side = rnd.nextInt(4), c = rnd.nextInt(2 * R - 1) - (R - 1), dy = 3 + rnd.nextInt(H - 2);
            int bx = side == 0 ? R + 1 : side == 1 ? -R - 1 : c;
            int bz = side == 2 ? R + 1 : side == 3 ? -R - 1 : c;
            carve(bx, dy, bz, 1.5 + rnd.nextDouble() * 1.8);
        }
        for (int i = 0; i < 5; i++) {
            carve(rnd.nextInt(2 * R - 1) - (R - 1), H, rnd.nextInt(2 * R - 1) - (R - 1), 1.5 + rnd.nextDouble() * 1.6);
        }
        // --- rubble on the floor
        Material[] rubble = {Material.TUFF, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.MOSSY_COBBLESTONE};
        for (int i = 0; i < 150; i++) {
            int dx = rnd.nextInt(2 * R - 1) - (R - 1), dz = rnd.nextInt(2 * R - 1) - (R - 1);
            if (nearAltar(dx, dz, 5)) continue;
            if (at(dx, 0, dz) == Material.AIR && at(dx, -1, dz) != Material.AIR) rel(dx, 0, dz, rubble[rnd.nextInt(rubble.length)]);
        }
        // --- moss
        for (int i = 0; i < 240; i++) {
            int dx = rnd.nextInt(2 * R - 1) - (R - 1), dz = rnd.nextInt(2 * R - 1) - (R - 1);
            if (at(dx, 0, dz) != Material.AIR || at(dx, -1, dz) == Material.AIR) continue;
            if (rnd.nextFloat() < 0.35f) {
                if (rnd.nextFloat() < 0.4f) rel(dx, -1, dz, Material.MOSS_BLOCK);
                rel(dx, 0, dz, Material.MOSS_CARPET);
            }
        }
        // --- glowing copper bulbs in the pillars
        int[] centres = {-12, -6, 0, 6, 12};
        for (int c : centres) {
            int[][] spots = {{R, c}, {-R, c}, {c, R}, {c, -R}};
            for (int[] s : spots) {
                if (at(s[0], 5, s[1]) != Material.AIR && rnd.nextFloat() < 0.75f) bulb(s[0], 5, s[1], rnd.nextFloat() < 0.8f);
            }
        }
        // --- vines creeping down the walls
        for (int i = 0; i < 110; i++) {
            int side = rnd.nextInt(4), c = rnd.nextInt(2 * R - 3) - (R - 2);
            int vx = side == 0 ? R : side == 1 ? -R : c;
            int vz = side == 2 ? R : side == 3 ? -R : c;
            BlockFace face = side == 0 ? BlockFace.EAST : side == 1 ? BlockFace.WEST : side == 2 ? BlockFace.SOUTH : BlockFace.NORTH;
            int dy = 4 + rnd.nextInt(H - 5), len = 2 + rnd.nextInt(5);
            for (int k = 0; k < len && dy - k >= 1; k++) {
                int y = dy - k;
                int wx = vx + face.getModX(), wz = vz + face.getModZ();
                if (at(vx, y, vz) != Material.AIR || at(wx, y, wz) == Material.AIR) break;
                BlockData d = Material.VINE.createBlockData();
                ((MultipleFacing) d).setFace(face, true);
                set(cx + vx, y0 + y, cz + vz, d);
            }
        }
        // --- cobwebs
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (int i = 0; i < 70; i++) {
            int dx = rnd.nextInt(2 * R - 1) - (R - 1), dz = rnd.nextInt(2 * R - 1) - (R - 1), dy = 1 + rnd.nextInt(H - 2);
            if (at(dx, dy, dz) != Material.AIR || nearAltar(dx, dz, 4)) continue;
            boolean solid = false;
            for (BlockFace f : faces) if (at(dx + f.getModX(), dy + f.getModY(), dz + f.getModZ()).isSolid()) solid = true;
            if (solid) rel(dx, dy, dz, Material.COBWEB);
        }
        // --- hanging chains with lanterns
        if (CHAIN != null) {
            for (int i = 0; i < 10; i++) {
                int dx = rnd.nextInt(2 * R - 5) - (R - 3), dz = rnd.nextInt(2 * R - 5) - (R - 3);
                if (nearAltar(dx, dz, 5) || !at(dx, H, dz).isSolid() || at(dx, H - 1, dz) != Material.AIR) continue;
                int len = 2 + rnd.nextInt(4);
                for (int k = 0; k < len; k++) rel(dx, H - 1 - k, dz, CHAIN);
                BlockData lantern = Material.LANTERN.createBlockData();
                if (lantern instanceof Lantern l) l.setHanging(true);
                set(cx + dx, y0 + H - 1 - len, cz + dz, lantern);
            }
        }
    }

    // ================================================================== phase 4: the weapon on the altar

    private void spawnAltarEntities() {
        Location top = new Location(world, cx + 0.5, y0 + 5.0, cz + 0.5);
        world.spawn(top.clone().add(0, 1.2, 0), ItemDisplay.class, d -> {
            d.setItemStack(items.create());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(2.4f), new AxisAngle4f()));
            d.addScoreboardTag(TAG_ITEM);
            d.setPersistent(true);
        });
        world.spawn(top, Interaction.class, i -> {
            i.setInteractionWidth(1.4f);
            i.setInteractionHeight(2.6f);
            i.addScoreboardTag(TAG_ALTAR);
            i.setPersistent(true);
        });
    }
}
