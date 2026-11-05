package net.potendpound;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;

/**
 * A wooden village: plank plaza with a well, boardwalks, two rings of log-and-plank houses with
 * stepped roofs, market stalls, lamp posts and a watchtower. Villagers move in. /potentpound undo removes it.
 */
public final class Village extends StructureBuilder {

    private static final String[] WOODS = {"OAK", "SPRUCE", "DARK_OAK", "BIRCH", "ACACIA"};
    private static final Villager.Profession[] JOBS = {
            Villager.Profession.FARMER, Villager.Profession.LIBRARIAN, Villager.Profession.TOOLSMITH,
            Villager.Profession.CARTOGRAPHER, Villager.Profession.FLETCHER, Villager.Profession.CLERIC};

    public Village(PotendPoundPlugin plugin) {
        super(plugin);
    }

    public boolean build(Player p) {
        if (busy) return false;
        busy = true;
        Location loc = p.getLocation();
        begin(loc, 50, 40, 12);
        p.sendMessage(MM.deserialize("<gray>Building a wooden village around you... <dark_gray>(/potentpound undo removes it)"));

        List<Runnable> tasks = new ArrayList<>();
        tasks.add(this::plaza);
        tasks.add(this::well);

        List<int[]> doors = new ArrayList<>();            // {doorFrontX, doorFrontZ}
        int[][] rings = {{8, 21}, {6, 34}};
        for (int[] ring : rings) {
            for (int i = 0; i < ring[0]; i++) {
                double ang = Math.toRadians(i * (360.0 / ring[0]) + rnd.nextInt(17) - 8);
                int rr = ring[1] + rnd.nextInt(5) - 2;
                final int hx = cx + (int) Math.round(Math.cos(ang) * rr), hz = cz + (int) Math.round(Math.sin(ang) * rr);
                final int w = ring[1] > 25 ? 7 : 7 + 2 * rnd.nextInt(3), d = 7 + 2 * rnd.nextInt(2);
                final String wood = WOODS[rnd.nextInt(WOODS.length)], roof = WOODS[rnd.nextInt(WOODS.length)];
                tasks.add(() -> house(hx, hz, w, d, wood, roof, doors));
            }
        }
        tasks.add(() -> {
            for (int[] d : doors) boardwalk(d[0], d[1]);
        });
        tasks.add(() -> stall(cx + 10, cz + 2, "SPRUCE"));
        tasks.add(() -> stall(cx - 10, cz - 3, "DARK_OAK"));
        tasks.add(() -> tower(cx + Math.round((float) Math.cos(1.0) * 45), cz + Math.round((float) Math.sin(1.0) * 45)));
        runTasks(tasks, 1, () -> {
            p.sendMessage(MM.deserialize("<gray>The village is ready."));
            busy = false;
        });
        return true;
    }

    // ================================================================== helpers

    private int groundY(int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private Material wood(String type, String suffix) {
        return Material.valueOf(type + "_" + suffix);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, m);
    }

    private void lamp(int x, int z) {
        int gy = groundY(x, z);
        set(x, gy + 1, z, Material.OAK_FENCE);
        set(x, gy + 2, z, Material.OAK_FENCE);
        set(x, gy + 3, z, Material.LANTERN);
    }

    // ================================================================== plaza + well

    private void plaza() {
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 8) continue;
                int x = cx + dx, z = cz + dz, gy = groundY(x, z);
                for (int y = gy + 1; y <= gy + 6; y++) if (absAt(x, y, z) != Material.AIR) set(x, y, z, Material.AIR);
                Material m = d >= 7 ? Material.DARK_OAK_PLANKS : (((dx + dz) & 1) == 0 ? Material.SPRUCE_PLANKS : Material.OAK_PLANKS);
                set(x, gy, z, m);
            }
        }
        for (int[] l : new int[][]{{6, 0}, {-6, 0}, {0, 6}, {0, -6}}) lamp(cx + l[0], cz + l[1]);
    }

    private void well() {
        int gy = groundY(cx, cz);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            set(cx + dx, gy, cz + dz, Material.OAK_PLANKS);
            set(cx + dx, gy + 1, cz + dz, (dx == 0 && dz == 0) ? Material.AIR : Material.OAK_PLANKS);
        }
        set(cx, gy, cz, Material.WATER);
        set(cx, gy - 1, cz, Material.OAK_PLANKS);
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            for (int y = 2; y <= 4; y++) set(cx + sx, gy + y, cz + sz, Material.OAK_LOG);
        }
        fill(cx - 1, gy + 5, cz - 1, cx + 1, gy + 5, cz + 1, Material.OAK_SLAB);
        set(cx, gy + 4, cz, Material.LANTERN);
    }

    // ================================================================== houses

    private void house(int hx, int hz, int w, int d, String woodType, String roofType, List<int[]> doors) {
        int x0 = hx - w / 2, x1 = x0 + w - 1, z0 = hz - d / 2, z1 = z0 + d - 1;
        int floorY = groundY(x0, z0);
        for (int[] c : new int[][]{{x1, z0}, {x0, z1}, {x1, z1}, {hx, hz}}) floorY = Math.max(floorY, groundY(c[0], c[1]));
        int wallH = 4;
        Material log = wood(woodType, "LOG"), planks = wood(woodType, "PLANKS"), roofPlanks = wood(roofType, "PLANKS");

        // clear the volume (trees, bumps)
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z1 + 1; z++)
            for (int y = floorY + 1; y <= floorY + wallH + 6; y++) if (absAt(x, y, z) != Material.AIR) set(x, y, z, Material.AIR);
        // foundation
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            for (int y = floorY - 1; y >= floorY - 8; y--) {
                Material m = absAt(x, y, z);
                if (m.isSolid() && !Tag.LEAVES.isTagged(m) && !Tag.LOGS.isTagged(m)) break;
                set(x, y, z, (x == x0 || x == x1) && (z == z0 || z == z1) ? log : planks);
            }
            set(x, floorY, z, planks);
        }
        // door side faces the plaza
        int dxc = cx - hx, dzc = cz - hz;
        boolean xSide = Math.abs(dxc) > Math.abs(dzc);
        BlockFace face = xSide ? (dxc > 0 ? BlockFace.EAST : BlockFace.WEST) : (dzc > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        int doorX = xSide ? (dxc > 0 ? x1 : x0) : hx;
        int doorZ = xSide ? hz : (dzc > 0 ? z1 : z0);

        // walls
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edgeX = x == x0 || x == x1, edgeZ = z == z0 || z == z1;
            if (!edgeX && !edgeZ) continue;
            boolean corner = edgeX && edgeZ;
            for (int y = 1; y <= wallH; y++) {
                Material m = corner ? log : planks;
                if (!corner && y == 2 && ((edgeX ? z - z0 : x - x0) % 3 == 1) && !(x == doorX && z == doorZ)) m = Material.GLASS;
                set(x, floorY + y, z, m);
            }
            set(x, floorY + wallH, z, corner ? log : wood(roofType, "PLANKS"));
        }
        // door
        set(doorX, floorY + 1, doorZ, Material.AIR);
        set(doorX, floorY + 2, doorZ, Material.AIR);
        BlockData lo = wood(woodType, "DOOR").createBlockData();
        BlockData hi = wood(woodType, "DOOR").createBlockData();
        if (lo instanceof Door dl && hi instanceof Door dh) {
            dl.setFacing(face);
            dl.setHalf(Bisected.Half.BOTTOM);
            dh.setFacing(face);
            dh.setHalf(Bisected.Half.TOP);
        }
        set(doorX, floorY + 1, doorZ, lo);
        set(doorX, floorY + 2, doorZ, hi);
        // stepped roof
        for (int k = 0; ; k++) {
            int rx0 = x0 - 1 + k, rx1 = x1 + 1 - k, rz0 = z0 - 1 + k, rz1 = z1 + 1 - k;
            if (rx0 > rx1 || rz0 > rz1) break;
            fill(rx0, floorY + wallH + 1 + k, rz0, rx1, floorY + wallH + 1 + k, rz1, roofPlanks);
        }
        // furnishings
        set(x0 + 1, floorY + 1, z0 + 1, Material.CRAFTING_TABLE);
        set(x1 - 1, floorY + 1, z0 + 1, Material.BOOKSHELF);
        set(x1 - 1, floorY + 1, z1 - 1, Material.OAK_FENCE);
        set(x1 - 1, floorY + 2, z1 - 1, Material.LANTERN);
        // a villager moves in
        final int vx = hx, vz = hz, vy = floorY + 1;
        world.spawn(new Location(world, vx + 0.5, vy, vz + 0.5), Villager.class, v -> {
            v.setProfession(JOBS[rnd.nextInt(JOBS.length)]);
            v.addScoreboardTag(TAG_BUILD);
            v.setPersistent(true);
        });
        doors.add(new int[]{doorX + face.getModX(), doorZ + face.getModZ()});
        lamp(doorX + face.getModX() * 2 + (xSide ? 0 : 2), doorZ + face.getModZ() * 2 + (xSide ? 2 : 0));
    }

    // ================================================================== boardwalks

    private void boardwalk(int sx, int sz) {
        double dx = cx - sx, dz = cz - sz, len = Math.sqrt(dx * dx + dz * dz);
        int steps = (int) len;
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(sx + dx * i / len), z = (int) Math.round(sz + dz * i / len);
            if (Math.hypot(x - cx, z - cz) <= 8) break;
            for (int[] o : new int[][]{{0, 0}, {1, 0}, {0, 1}}) {
                int px = x + o[0], pz = z + o[1];
                int gy = groundY(px, pz);
                if (!absAt(px, gy, pz).isSolid()) continue;
                set(px, gy, pz, ((px + pz) & 1) == 0 ? Material.SPRUCE_PLANKS : Material.OAK_PLANKS);
            }
        }
    }

    // ================================================================== market stall + watchtower

    private void stall(int sx, int sz, String type) {
        int gy = groundY(sx, sz);
        for (int x = sx - 2; x <= sx + 2; x++) for (int z = sz - 2; z <= sz + 2; z++)
            for (int y = gy + 1; y <= gy + 5; y++) if (absAt(x, y, z) != Material.AIR) set(x, y, z, Material.AIR);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int y = 1; y <= 3; y++) set(sx + c[0], gy + y, sz + c[1], wood(type, "FENCE"));
        }
        fill(sx - 2, gy + 4, sz - 2, sx + 2, gy + 4, sz + 2, wood(type, "SLAB"));
        fill(sx - 1, gy + 1, sz - 2, sx + 1, gy + 1, sz - 2, wood(type, "PLANKS"));      // counter
        set(sx, gy + 2, sz - 2, Material.BARREL);
        set(sx, gy + 3, sz, Material.LANTERN);
        set(sx, gy + 1, sz, Material.OAK_FENCE);
    }

    private void tower(int tx, int tz) {
        int gy = groundY(tx, tz);
        for (int x = tx - 3; x <= tx + 3; x++) for (int z = tz - 3; z <= tz + 4; z++)
            for (int y = gy + 1; y <= gy + 19; y++) if (absAt(x, y, z) != Material.AIR) set(x, y, z, Material.AIR);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int y = gy - 3; y <= gy + 12; y++) set(tx + c[0], y, tz + c[1], Material.DARK_OAK_LOG);
        }
        for (int lvl : new int[]{4, 8}) {                          // cross braces
            for (int i = -2; i <= 2; i++) {
                set(tx + i, gy + lvl, tz - 2, Material.DARK_OAK_FENCE);
                set(tx + i, gy + lvl, tz + 2, Material.DARK_OAK_FENCE);
                set(tx - 2, gy + lvl, tz + i, Material.DARK_OAK_FENCE);
                set(tx + 2, gy + lvl, tz + i, Material.DARK_OAK_FENCE);
            }
        }
        fill(tx - 2, gy + 12, tz - 2, tx + 2, gy + 12, tz + 2, Material.SPRUCE_PLANKS);          // platform
        set(tx, gy + 12, tz + 3, Material.SPRUCE_PLANKS);
        for (int i = -2; i <= 2; i++) {                                                         // railing
            set(tx + i, gy + 13, tz - 2, Material.DARK_OAK_FENCE);
            set(tx - 2, gy + 13, tz + i, Material.DARK_OAK_FENCE);
            set(tx + 2, gy + 13, tz + i, Material.DARK_OAK_FENCE);
            if (i != 0) set(tx + i, gy + 13, tz + 2, Material.DARK_OAK_FENCE);
        }
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) for (int y = 14; y <= 16; y++) set(tx + c[0], gy + y, tz + c[1], Material.DARK_OAK_FENCE);
        fill(tx - 3, gy + 17, tz - 3, tx + 3, gy + 17, tz + 3, Material.DARK_OAK_SLAB);
        set(tx, gy + 16, tz, Material.LANTERN);
        for (int y = 1; y <= 12; y++) set(tx, gy + y, tz + 2, Material.SPRUCE_PLANKS);              // ladder spine
        BlockData ladder = Material.LADDER.createBlockData();
        if (ladder instanceof Ladder l) l.setFacing(BlockFace.SOUTH);
        for (int y = 1; y <= 12; y++) set(tx, gy + y, tz + 3, ladder);
    }
}
