package net.potendpound;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A large, ruined trial chamber: a copper-inlaid plaza around a stepped altar, a collapsing
 * gallery ring with iron-bar railings, pillars with lit bulbs, wall niches, a chandelier,
 * holes, breaches, vines, cobwebs and rubble. The Potent Pound waits on the altar.
 */
public final class TrialChamber extends StructureBuilder {

    public static final String TAG_ALTAR = "potendpound_altar";
    public static final String TAG_ITEM = "potendpound_altar_item";
    public static final int ALTAR_HEIGHT = 7;                 // altar top = y0 + 7

    private static final int R = 22;      // interior half-size -> 45 x 45
    private static final int H = 18;      // interior height
    private static final int T = 3;       // wall / floor / ceiling thickness
    private static final int E = R + T;
    private static final int GALLERY = 7; // gallery floor level
    private static final Material CHAIN = firstOf("IRON_CHAIN", "CHAIN");

    private final PotendItem items;
    private float[][] noise;

    public TrialChamber(PotendPoundPlugin plugin, PotendItem items) {
        super(plugin);
        this.items = items;
    }

    // ================================================================== entry point

    public boolean build(Player p) {
        if (busy) return false;
        Location loc = p.getLocation();
        if (loc.getBlockY() - T < loc.getWorld().getMinHeight() + 1 || loc.getBlockY() + H + T + 8 >= loc.getWorld().getMaxHeight()) {
            p.sendMessage(MM.deserialize("<red>Not enough vertical room here."));
            return true;
        }
        busy = true;
        begin(loc, E + 1, H + T + 8, T + 5);
        noise = makeNoise(E * 2 + 1);
        p.sendMessage(MM.deserialize("<gray>Raising the ruined chamber... <dark_gray>(/potentpound undo reverts it)"));

        List<Runnable> tasks = new ArrayList<>();
        for (int dy = -T; dy < H + T; dy += 2) {
            final int d0 = dy;
            tasks.add(() -> {
                buildLayer(d0);
                if (d0 + 1 < H + T) buildLayer(d0 + 1);
            });
        }
        tasks.add(this::buildAltar);
        tasks.add(this::decay);
        tasks.add(this::lighting);
        tasks.add(this::spawnAltarEntities);
        runTasks(tasks, 1, () -> {
            p.teleport(new Location(world, cx + R - 3 + 0.5, y0, cz + 0.5, 90f, 0f));
            p.sendMessage(MM.deserialize("<gray>The chamber is ready. Right-click the <white>Potent Pound</white> on the altar."));
            busy = false;
        });
        return true;
    }

    // ================================================================== noise + materials

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

    private float nz(int dx, int dz) {
        return noise[dx + E][dz + E];
    }

    private static int cheb(int dx, int dz) {
        return Math.max(Math.abs(dx), Math.abs(dz));
    }

    private boolean isPillar(int dx, int dz) {
        boolean onX = Math.abs(dx) == R, onZ = Math.abs(dz) == R;
        if (!onX && !onZ) return false;
        if (onX && onZ) return true;
        int c = onX ? dz : dx;
        return Math.floorMod(c + 1, 8) <= 2;
    }

    private boolean isNiche(int dx, int dz) {
        boolean onX = Math.abs(dx) == R, onZ = Math.abs(dz) == R;
        if (onX == onZ) return false;
        int c = onX ? dz : dx;
        return Math.floorMod(c + 1, 8) >= 4 && Math.floorMod(c + 1, 8) <= 6;
    }

    private boolean isSupport(int dx, int dz) {
        if (cheb(dx, dz) != R - 2) return false;
        int c = Math.abs(dx) == R - 2 ? dz : dx;
        return Math.floorMod(c, 8) == 4 && Math.abs(c) < R - 2;
    }

    private Material pillarMat(int dy) {
        if (dy <= 1 || dy >= H - 2) return Material.CHISELED_TUFF_BRICKS;
        if (dy == 3 || dy == 4) return dy == 3 ? Material.WEATHERED_COPPER : Material.EXPOSED_COPPER;
        return dy % 4 == 0 ? Material.POLISHED_TUFF : Material.TUFF_BRICKS;
    }

    private Material floorMat(float f, int dx, int dz) {
        int d = cheb(dx, dz);
        if (d <= 8) {                                              // tiles around the altar
            return f < 0.4f ? Material.OXIDIZED_CUT_COPPER : f < 0.7f ? Material.WEATHERED_COPPER : Material.EXPOSED_COPPER;
        }
        if (d == 10 || d == 17) return (dx + dz) % 2 == 0 ? Material.OXIDIZED_CHISELED_COPPER : Material.COPPER_BLOCK;  // inlaid rings
        if (d == 9 || d == 18) return Material.TUFF_BRICKS;
        if (d >= 11 && d <= 16) return ((dx + dz) & 1) == 0 ? Material.OXIDIZED_CUT_COPPER : (f < 0.5f ? Material.WEATHERED_CUT_COPPER : Material.OXIDIZED_COPPER);
        if (f < 0.25f) return Material.OXIDIZED_COPPER;
        if (f < 0.45f) return Material.OXIDIZED_CUT_COPPER;
        if (f < 0.58f) return Material.WEATHERED_COPPER;
        if (f < 0.66f) return Material.EXPOSED_COPPER;
        if (f < 0.76f) return Material.TUFF_BRICKS;
        if (f < 0.84f) return Material.TUFF;
        if (f < 0.9f) return rnd.nextBoolean() ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE;
        if (f < 0.96f) return Material.MOSS_BLOCK;
        return Material.GRAVEL;
    }

    private Material shellMat(int dx, int dy, int dz) {
        float f = nz(dx, dz);
        float g = (f + dy * 0.37f) % 1f;
        if (dy < 0) return dy == -1 ? floorMat(f, dx, dz) : (f < 0.5f ? Material.TUFF_BRICKS : Material.TUFF);
        if (dy >= H) {                                                // coffered ceiling
            if (dy == H) return (Math.floorMod(dx, 6) == 0 || Math.floorMod(dz, 6) == 0) ? Material.CHISELED_TUFF_BRICKS : Material.TUFF_BRICKS;
            return Material.TUFF;
        }
        if (dy == 3 || dy == 4) {                                     // weathered copper band
            return g < 0.25f ? Material.OXIDIZED_COPPER : g < 0.5f ? Material.WEATHERED_COPPER : g < 0.8f ? Material.EXPOSED_COPPER : Material.COPPER_BLOCK;
        }
        if (dy == GALLERY - 1 || dy == H - 1) return Material.CHISELED_TUFF_BRICKS;
        if (dy == H - 4) return g < 0.5f ? Material.OXIDIZED_COPPER : Material.WEATHERED_COPPER;
        if (g < 0.07f) return Material.POLISHED_TUFF;
        if (g < 0.12f) return Material.TUFF;
        if (g > 0.97f) return Material.MOSS_BLOCK;
        return Material.TUFF_BRICKS;
    }

    private void bulb(int dx, int dy, int dz, boolean lit) {
        Material[] kinds = {Material.COPPER_BULB, Material.EXPOSED_COPPER_BULB, Material.WEATHERED_COPPER_BULB, Material.OXIDIZED_COPPER_BULB};
        BlockData d = kinds[rnd.nextInt(kinds.length)].createBlockData();
        if (d instanceof Lightable l) l.setLit(lit);
        rel(dx, dy, dz, d);
    }

    // ================================================================== shell, gallery, pillars

    private void buildLayer(int dy) {
        for (int dx = -E; dx <= E; dx++) {
            for (int dz = -E; dz <= E; dz++) {
                boolean inside = Math.abs(dx) <= R && Math.abs(dz) <= R && dy >= 0 && dy < H;
                if (!inside) {
                    // wall niches carve into the wall behind the gaps between pillars
                    if (dy >= 1 && dy <= 6 && dy < H) {
                        int d = Math.max(Math.abs(dx), Math.abs(dz));
                        boolean wallSide = (Math.abs(dx) > R) != (Math.abs(dz) > R);
                        int along = Math.abs(dx) > R ? dz : dx;
                        int fl = Math.floorMod(along + 1, 8);
                        if (wallSide && (d == R + 1 || d == R + 2) && fl >= 4 && fl <= 6 && Math.abs(along) < R - 1) {
                            if (dy == 5 && d == R + 2 && fl == 5) bulb(dx, dy, dz, true); else rel(dx, dy, dz, Material.AIR);
                            continue;
                        }
                    }
                    rel(dx, dy, dz, shellMat(dx, dy, dz));
                    continue;
                }
                int d = cheb(dx, dz);
                if (isPillar(dx, dz)) {
                    rel(dx, dy, dz, pillarMat(dy));
                } else if (dy == GALLERY && d >= R - 2 && d <= R) {
                    float f = nz(dx, dz);
                    rel(dx, dy, dz, f < 0.5f ? Material.TUFF_BRICKS : Material.POLISHED_TUFF);
                } else if (dy == GALLERY + 1 && d == R - 2 && !isSupport(dx, dz)) {
                    rel(dx, dy, dz, Material.IRON_BARS);
                } else if (isSupport(dx, dz) && dy < GALLERY) {
                    rel(dx, dy, dz, dy % 4 == 0 ? Material.CHISELED_TUFF_BRICKS : Material.TUFF_BRICKS);
                } else {
                    rel(dx, dy, dz, Material.AIR);
                }
            }
        }
    }

    // ================================================================== altar

    private void buildAltar() {
        int[] half = {7, 6, 5, 4, 3, 1, 0};
        for (int dy = 0; dy < half.length; dy++) {
            int h = half[dy];
            for (int dx = -h; dx <= h; dx++) {
                for (int dz = -h; dz <= h; dz++) {
                    boolean edge = Math.abs(dx) == h || Math.abs(dz) == h;
                    boolean corner = Math.abs(dx) == h && Math.abs(dz) == h;
                    if (dy == 0 && corner && rnd.nextFloat() < 0.4f) { rel(dx, dy, dz, Material.AIR); continue; }
                    if (dy <= 1 && edge && !corner && rnd.nextFloat() < 0.07f) { rel(dx, dy, dz, Material.AIR); continue; }
                    Material m;
                    switch (dy) {
                        case 0 -> {
                            boolean mid = (Math.abs(dx) == h && Math.abs(dz) <= 2) || (Math.abs(dz) == h && Math.abs(dx) <= 2);
                            m = mid ? Material.OXIDIZED_CHISELED_COPPER : edge ? Material.TUFF_BRICKS : Material.OXIDIZED_COPPER;
                        }
                        case 1 -> m = edge ? Material.CHISELED_TUFF_BRICKS : (rnd.nextBoolean() ? Material.WEATHERED_CUT_COPPER : Material.OXIDIZED_CUT_COPPER);
                        case 2 -> m = rnd.nextBoolean() ? Material.EXPOSED_CUT_COPPER : Material.WEATHERED_CUT_COPPER;
                        case 3 -> m = rnd.nextBoolean() ? Material.COPPER_BLOCK : Material.EXPOSED_COPPER;
                        case 4 -> m = rnd.nextFloat() < 0.7f ? Material.COPPER_BLOCK : Material.EXPOSED_COPPER;
                        case 5 -> m = Material.CHISELED_TUFF_BRICKS;
                        default -> m = Material.POLISHED_ANDESITE;
                    }
                    rel(dx, dy, dz, m);
                }
            }
        }
        for (int sx : new int[]{-6, 6}) for (int sz : new int[]{-6, 6}) {      // braziers on the first step
            rel(sx, 1, sz, Material.TUFF_BRICKS);
            bulb(sx, 2, sz, true);
        }
    }

    // ================================================================== decay

    private boolean nearAltar(int dx, int dz, int r) {
        return cheb(dx, dz) <= r;
    }

    private void carve(int dx0, int dy0, int dz0, double radius, int minDy) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    int ax = dx0 + dx, ay = dy0 + dy, az = dz0 + dz;
                    if (ay < minDy || ay >= H + T) continue;
                    if (Math.abs(ax) > E || Math.abs(az) > E || nearAltar(ax, az, 9)) continue;
                    if (at(ax, ay, az) != Material.AIR) rel(ax, ay, az, Material.AIR);
                }
            }
        }
    }

    private int rpos(int span) {
        return rnd.nextInt(2 * span + 1) - span;
    }

    private void decay() {
        // holes in the floor with rubble rims
        for (int i = 0; i < 22; i++) {
            int hx = rpos(R - 2), hz = rpos(R - 2);
            if (nearAltar(hx, hz, 11)) continue;
            double rad = 1.6 + rnd.nextDouble() * 2.4;
            int depth = 2 + rnd.nextInt(3);
            int r = (int) Math.ceil(rad + 1.4);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    int ax = hx + dx, az = hz + dz;
                    if (Math.abs(ax) > R || Math.abs(az) > R) continue;
                    if (d <= rad) {
                        for (int dy = -1; dy >= -depth; dy--) rel(ax, dy, az, Material.AIR);
                    } else if (d <= rad + 1.4 && rnd.nextFloat() < 0.55f) {
                        Material[] rim = {Material.COBBLESTONE, Material.TUFF, Material.GRAVEL, Material.COBBLED_DEEPSLATE};
                        rel(ax, -1, az, rim[rnd.nextInt(rim.length)]);
                    }
                }
            }
        }
        // breaches in walls and the gallery, skylights in the ceiling, collapsed pillar sections
        for (int i = 0; i < 26; i++) {
            int side = rnd.nextInt(4), c = rpos(R - 1), dy = 3 + rnd.nextInt(H - 3);
            int bx = side == 0 ? R + 1 : side == 1 ? -R - 1 : c;
            int bz = side == 2 ? R + 1 : side == 3 ? -R - 1 : c;
            carve(bx, dy, bz, 1.6 + rnd.nextDouble() * 2.2, 0);
        }
        for (int i = 0; i < 9; i++) carve(rpos(R - 4), H, rpos(R - 4), 2.0 + rnd.nextDouble() * 2.4, H - 1);
        for (int i = 0; i < 7; i++) {                                                     // broken gallery sections
            int side = rnd.nextInt(4), c = rpos(R - 3);
            int gx = side == 0 ? R - 1 : side == 1 ? -R + 1 : c;
            int gz = side == 2 ? R - 1 : side == 3 ? -R + 1 : c;
            carve(gx, GALLERY, gz, 2.2 + rnd.nextDouble() * 1.5, GALLERY - 1);
        }
        // rubble piles (mounds) and scattered debris
        Material[] rubble = {Material.TUFF, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.MOSSY_COBBLESTONE, Material.TUFF_BRICKS};
        for (int i = 0; i < 16; i++) {
            int mx = rpos(R - 3), mz = rpos(R - 3);
            if (nearAltar(mx, mz, 10)) continue;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                int h = 2 - Math.max(Math.abs(dx), Math.abs(dz)) + (rnd.nextFloat() < 0.4f ? 1 : 0);
                for (int dy = 0; dy < h; dy++) if (at(mx + dx, dy, mz + dz) == Material.AIR && at(mx + dx, -1, mz + dz) != Material.AIR)
                    rel(mx + dx, dy, mz + dz, rubble[rnd.nextInt(rubble.length)]);
            }
        }
        for (int i = 0; i < 450; i++) {
            int dx = rpos(R - 1), dz = rpos(R - 1);
            if (nearAltar(dx, dz, 8)) continue;
            if (at(dx, 0, dz) == Material.AIR && at(dx, -1, dz) != Material.AIR) rel(dx, 0, dz, rubble[rnd.nextInt(rubble.length)]);
        }
        // moss
        for (int i = 0; i < 700; i++) {
            int dx = rpos(R - 1), dz = rpos(R - 1);
            if (at(dx, 0, dz) != Material.AIR || at(dx, -1, dz) == Material.AIR) continue;
            if (rnd.nextFloat() < 0.35f) {
                if (rnd.nextFloat() < 0.4f) rel(dx, -1, dz, Material.MOSS_BLOCK);
                rel(dx, 0, dz, Material.MOSS_CARPET);
            }
        }
        // vines creeping down the walls
        for (int i = 0; i < 320; i++) {
            int side = rnd.nextInt(4), c = rpos(R - 2);
            int vx = side == 0 ? R : side == 1 ? -R : c;
            int vz = side == 2 ? R : side == 3 ? -R : c;
            BlockFace face = side == 0 ? BlockFace.EAST : side == 1 ? BlockFace.WEST : side == 2 ? BlockFace.SOUTH : BlockFace.NORTH;
            int dy = 6 + rnd.nextInt(H - 7), len = 2 + rnd.nextInt(8);
            for (int k = 0; k < len && dy - k >= 1; k++) {
                int y = dy - k;
                int wx = vx + face.getModX(), wz = vz + face.getModZ();
                if (at(vx, y, vz) != Material.AIR || at(wx, y, wz) == Material.AIR) break;
                BlockData d = Material.VINE.createBlockData();
                ((MultipleFacing) d).setFace(face, true);
                rel(vx, y, vz, d);
            }
        }
        // cobwebs
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (int i = 0; i < 220; i++) {
            int dx = rpos(R - 1), dz = rpos(R - 1), dy = 1 + rnd.nextInt(H - 2);
            if (at(dx, dy, dz) != Material.AIR || nearAltar(dx, dz, 5)) continue;
            boolean solid = false;
            for (BlockFace f : faces) if (at(dx + f.getModX(), dy + f.getModY(), dz + f.getModZ()).isSolid()) solid = true;
            if (solid) rel(dx, dy, dz, Material.COBWEB);
        }
    }

    // ================================================================== lighting (bulbs, chains, chandelier)

    private void lighting() {
        for (int c = -16; c <= 16; c += 8) {                          // glowing bulbs high on the pillars
            int[][] spots = {{R, c}, {-R, c}, {c, R}, {c, -R}};
            for (int[] s : spots) {
                if (at(s[0], 5, s[1]) != Material.AIR && rnd.nextFloat() < 0.8f) bulb(s[0], 5, s[1], rnd.nextFloat() < 0.8f);
                if (at(s[0], GALLERY + 4, s[1]) != Material.AIR && rnd.nextFloat() < 0.6f) bulb(s[0], GALLERY + 4, s[1], rnd.nextFloat() < 0.7f);
            }
        }
        if (CHAIN == null) return;
        // great chandelier above the altar
        for (int dy = H - 1; dy >= 12; dy--) rel(0, dy, 0, CHAIN);
        int[][] ring = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] r : ring) bulb(r[0], 11, r[1], true);
        bulb(0, 11, 0, true);
        // smaller hanging lanterns
        for (int i = 0; i < 14; i++) {
            int dx = rpos(R - 4), dz = rpos(R - 4);
            if (nearAltar(dx, dz, 9) || !at(dx, H, dz).isSolid() || at(dx, H - 1, dz) != Material.AIR) continue;
            int len = 2 + rnd.nextInt(5);
            for (int k = 0; k < len; k++) rel(dx, H - 1 - k, dz, CHAIN);
            BlockData lantern = Material.LANTERN.createBlockData();
            if (lantern instanceof Lantern l) l.setHanging(true);
            rel(dx, H - 1 - len, dz, lantern);
        }
    }

    // ================================================================== the weapon on the altar

    private void spawnAltarEntities() {
        Location top = new Location(world, cx + 0.5, y0 + ALTAR_HEIGHT, cz + 0.5);
        world.spawn(top.clone().add(0, 1.2, 0), ItemDisplay.class, d -> {
            d.setItemStack(items.create());
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(2.4f), new AxisAngle4f()));
            d.addScoreboardTag(TAG_ITEM);
            d.addScoreboardTag(TAG_BUILD);
            d.setPersistent(true);
        });
        world.spawn(top, Interaction.class, i -> {
            i.setInteractionWidth(1.4f);
            i.setInteractionHeight(2.6f);
            i.addScoreboardTag(TAG_ALTAR);
            i.addScoreboardTag(TAG_BUILD);
            i.setPersistent(true);
        });
    }
}
