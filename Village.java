package net.potendpound;

import org.bukkit.Axis;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;

/**
 * A half-timbered medieval town: cream plaster houses with dark timber frames and steep dark gable roofs
 * around a cobbled square with a well and striped market stalls, four tall spire towers, farm plots,
 * a pond with a dock, cherry trees and log piles. Villagers move in. /potentpound undo removes it.
 */
public final class Village extends StructureBuilder {

    private static final Villager.Profession[] JOBS = {
            Villager.Profession.FARMER, Villager.Profession.LIBRARIAN, Villager.Profession.TOOLSMITH,
            Villager.Profession.CARTOGRAPHER, Villager.Profession.FLETCHER, Villager.Profession.CLERIC};

    public Village(PotendPoundPlugin plugin) {
        super(plugin);
    }

    public boolean build(Player p) {
        if (busy) return false;
        busy = true;
        begin(p.getLocation(), 64, 40, 14);
        p.sendMessage(MM.deserialize("<gray>Building a medieval timber town around you... <dark_gray>(/potentpound undo removes it)"));

        List<Runnable> tasks = new ArrayList<>();
        tasks.add(this::plaza);
        // houses facing the square: north row faces south, south row faces north
        for (int i = -2; i <= 2; i++) {
            final int hx = cx + i * 12;
            tasks.add(() -> house(hx, cz - 18, 11, 9, BlockFace.SOUTH));
            if (i != 0) tasks.add(() -> house(hx, cz + 18, 11, 9, BlockFace.NORTH));
        }
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            final int tx = cx + sx * 34, tz = cz + sz * 22;
            tasks.add(() -> tower(tx, tz));
        }
        tasks.add(() -> stall(cx - 6, cz - 4));
        tasks.add(() -> stall(cx + 6, cz - 4));
        tasks.add(() -> stall(cx - 6, cz + 5));
        tasks.add(() -> stall(cx + 6, cz + 5));
        tasks.add(() -> farm(cx - 56, cz - 14, 11, 9, Material.WHEAT));
        tasks.add(() -> farm(cx - 56, cz + 1, 11, 9, Material.CARROTS));
        tasks.add(() -> farm(cx - 42, cz + 36, 9, 9, Material.POTATOES));
        tasks.add(() -> pond(cx + 46, cz + 2, 7));
        tasks.add(() -> cherryTree(cx - 14, cz + 38));
        tasks.add(() -> cherryTree(cx + 22, cz - 36));
        tasks.add(() -> cherryTree(cx + 36, cz + 34));
        for (int[] l : new int[][]{{-30, -30}, {-4, -32}, {28, 32}, {40, -22}, {-30, 33}}) tasks.add(() -> logPile(cx + l[0], cz + l[1]));
        tasks.add(() -> paths());
        runTasks(tasks, 1, () -> {
            p.sendMessage(MM.deserialize("<gray>The town is ready."));
            busy = false;
        });
        return true;
    }

    // ================================================================== helpers

    private int groundY(int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, m);
    }

    private void clear(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++)
            for (int y = y1; y <= y2; y++) if (absAt(x, y, z) != Material.AIR) set(x, y, z, Material.AIR);
    }

    private void log(int x, int y, int z, Material mat, Axis axis) {
        BlockData d = mat.createBlockData();
        if (d instanceof Orientable o) o.setAxis(axis);
        set(x, y, z, d);
    }

    private void stair(int x, int y, int z, Material mat, BlockFace facing) {
        BlockData d = mat.createBlockData();
        if (d instanceof Stairs s) {
            s.setFacing(facing);          // facing = the direction the stair ascends toward
            s.setHalf(Bisected.Half.BOTTOM);
        }
        set(x, y, z, d);
    }

    private void lamp(int x, int z) {
        int gy = groundY(x, z);
        set(x, gy + 1, z, Material.DARK_OAK_FENCE);
        set(x, gy + 2, z, Material.DARK_OAK_FENCE);
        set(x, gy + 3, z, Material.LANTERN);
    }

    // ================================================================== square

    private void plaza() {
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                int x = cx + dx, z = cz + dz, gy = groundY(x, z);
                clear(x, gy + 1, z, x, gy + 7, z);
                int edge = Math.max(Math.abs(dx), Math.abs(dz));
                Material m = edge == 9 ? Material.STONE_BRICKS : (edge + dx + dz) % 5 == 0 ? Material.GRAVEL : (((dx + dz) & 1) == 0 ? Material.COBBLESTONE : Material.STONE_BRICKS);
                set(x, gy, z, m);
            }
        }
        // well in the middle
        int gy = groundY(cx, cz);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            boolean rim = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
            set(cx + dx, gy, cz + dz, Material.STONE_BRICKS);
            set(cx + dx, gy + 1, cz + dz, rim ? Material.STONE_BRICK_SLAB : Material.AIR);
        }
        fill(cx - 1, gy, cz - 1, cx + 1, gy, cz + 1, Material.WATER);
        for (int sx : new int[]{-2, 2}) for (int sz : new int[]{-2, 2}) {
            for (int y = 2; y <= 4; y++) set(cx + sx, gy + y, cz + sz, Material.DARK_OAK_LOG);
        }
        fill(cx - 3, gy + 5, cz - 3, cx + 3, gy + 5, cz + 3, Material.DARK_OAK_SLAB);
        set(cx, gy + 4, cz, Material.LANTERN);
        for (int[] l : new int[][]{{8, 8}, {-8, 8}, {8, -8}, {-8, -8}, {0, 8}, {0, -8}}) lamp(cx + l[0], cz + l[1]);
    }

    // ================================================================== timber houses

    private void house(int hx, int hz, int w, int d, BlockFace doorFace) {
        int x0 = hx - w / 2, x1 = x0 + w - 1, z0 = hz - d / 2, z1 = z0 + d - 1;
        int floorY = groundY(x0, z0);
        for (int[] c : new int[][]{{x1, z0}, {x0, z1}, {x1, z1}, {hx, hz}}) floorY = Math.max(floorY, groundY(c[0], c[1]));
        clear(x0 - 2, floorY + 1, z0 - 2, x1 + 2, floorY + 19, z1 + 2);

        // foundation + floor
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            for (int y = floorY - 1; y >= floorY - 8; y--) {
                Material m = absAt(x, y, z);
                if (m.isSolid() && !Tag.LEAVES.isTagged(m) && !Tag.LOGS.isTagged(m)) break;
                set(x, y, z, Material.COBBLESTONE);
            }
            set(x, floorY, z, Material.SPRUCE_PLANKS);
        }
        int doorX = hx, doorZ = doorFace == BlockFace.SOUTH ? z1 : z0;

        // walls: plaster panels between dark timber posts and beams
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean edgeX = x == x0 || x == x1, edgeZ = z == z0 || z == z1;
            if (!edgeX && !edgeZ) continue;
            boolean corner = edgeX && edgeZ;
            int along = edgeX ? z - z0 : x - x0;
            Axis axis = edgeX ? Axis.Z : Axis.X;
            boolean post = corner || along % 4 == 0;
            for (int y = 1; y <= 9; y++) {
                if (y == 5 || y == 9) {
                    log(x, floorY + y, z, Material.DARK_OAK_LOG, axis);
                    continue;
                }
                Material m;
                if (post) m = Material.DARK_OAK_LOG;
                else if (y == 1) m = Material.STONE_BRICKS;
                else if (along % 4 == 2 && (y == 2 || y == 3 || y == 7)) m = Material.GLASS;
                else m = Material.WHITE_TERRACOTTA;
                set(x, floorY + y, z, m);
            }
        }
        // upper floor
        for (int x = x0 + 1; x <= x1 - 1; x++) for (int z = z0 + 1; z <= z1 - 1; z++) set(x, floorY + 5, z, Material.SPRUCE_PLANKS);
        // door
        BlockData lo = Material.DARK_OAK_DOOR.createBlockData(), hi = Material.DARK_OAK_DOOR.createBlockData();
        if (lo instanceof Door dl && hi instanceof Door dh) {
            dl.setFacing(doorFace);
            dl.setHalf(Bisected.Half.BOTTOM);
            dh.setFacing(doorFace);
            dh.setHalf(Bisected.Half.TOP);
        }
        set(doorX, floorY + 1, doorZ, lo);
        set(doorX, floorY + 2, doorZ, hi);
        // steep dark roof, plaster gable ends, chimney
        gableRoof(x0, x1, z0, z1, floorY + 10, Material.DARK_OAK_STAIRS, Material.DARK_OAK_PLANKS, Material.WHITE_TERRACOTTA);
        fill(x1 - 2, floorY + 8, z0 + 2, x1 - 2, floorY + 17, z0 + 2, Material.STONE_BRICKS);
        // furnishings
        set(x0 + 1, floorY + 1, z0 + 1, Material.CRAFTING_TABLE);
        set(x1 - 1, floorY + 1, z1 - 1, Material.DARK_OAK_FENCE);
        set(x1 - 1, floorY + 2, z1 - 1, Material.LANTERN);
        world.spawn(new Location(world, hx + 0.5, floorY + 1, hz + 0.5), Villager.class, v -> {
            v.setProfession(JOBS[rnd.nextInt(JOBS.length)]);
            v.addScoreboardTag(TAG_BUILD);
            v.setPersistent(true);
        });
        lamp(doorX + 2, doorZ + (doorFace == BlockFace.SOUTH ? 2 : -2));
    }

    /** Roof ridge along X: stairs ascend toward the ridge; the gaps and gable ends are filled solid. */
    private void gableRoof(int x0, int x1, int z0, int z1, int baseY, Material stairs, Material fill, Material endFill) {
        for (int k = 0; ; k++) {
            int za = z0 - 1 + k, zb = z1 + 1 - k;
            if (za > zb) break;
            int y = baseY + k;
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                Material in = (x == x0 || x == x1) ? endFill : fill;
                if (za == zb) {
                    set(x, y, za, in);
                } else {
                    stair(x, y, za, stairs, BlockFace.SOUTH);
                    stair(x, y, zb, stairs, BlockFace.NORTH);
                    for (int z = za + 1; z < zb; z++) set(x, y, z, in);
                }
            }
        }
    }

    // ================================================================== spire towers

    private void tower(int tx, int tz) {
        int gy = groundY(tx, tz);
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}, {0, 0}}) gy = Math.max(gy, groundY(tx + c[0], tz + c[1]));
        clear(tx - 4, gy + 1, tz - 4, tx + 4, gy + 30, tz + 4);
        // base shaft 5x5, 9 tall
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            for (int y = gy - 1; y >= gy - 8; y--) {
                Material m = absAt(tx + dx, y, tz + dz);
                if (m.isSolid() && !Tag.LEAVES.isTagged(m) && !Tag.LOGS.isTagged(m)) break;
                set(tx + dx, y, tz + dz, Material.COBBLESTONE);
            }
            set(tx + dx, gy, tz + dz, Material.SPRUCE_PLANKS);
            boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == 2, corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
            if (!edge) continue;
            for (int y = 1; y <= 9; y++) {
                Material m = corner ? Material.DARK_OAK_LOG : y == 1 ? Material.STONE_BRICKS
                        : ((dx == 0 || dz == 0) && (y == 4 || y == 5 || y == 7)) ? Material.GLASS : Material.DARK_OAK_PLANKS;
                set(tx + dx, gy + y, tz + dz, m);
            }
        }
        // flared ring (stairs pointing outward under the upper tier)
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != 3) continue;
            boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
            set(tx + dx, gy + 10, tz + dz, corner ? Material.DARK_OAK_LOG : Material.DARK_OAK_PLANKS);
        }
        fill(tx - 2, gy + 10, tz - 2, tx + 2, gy + 10, tz + 2, Material.DARK_OAK_PLANKS);
        // upper shaft 3x3, 6 tall
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
            for (int y = 11; y <= 16; y++) {
                Material m = corner ? Material.DARK_OAK_LOG : (y == 13 || y == 14) ? Material.GLASS : Material.DARK_OAK_PLANKS;
                set(tx + dx, gy + y, tz + dz, m);
            }
        }
        // hip-roof spire
        int y = gy + 17;
        for (int half = 3; half >= 0; half--, y++) {
            for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
                boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == half;
                if (!ring) { set(tx + dx, y, tz + dz, Material.DARK_OAK_PLANKS); continue; }
                boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                if (half == 0 || corner) set(tx + dx, y, tz + dz, Material.DARK_OAK_PLANKS);
                else if (dz == -half) stair(tx + dx, y, tz + dz, Material.DARK_OAK_STAIRS, BlockFace.SOUTH);
                else if (dz == half) stair(tx + dx, y, tz + dz, Material.DARK_OAK_STAIRS, BlockFace.NORTH);
                else if (dx == -half) stair(tx + dx, y, tz + dz, Material.DARK_OAK_STAIRS, BlockFace.EAST);
                else stair(tx + dx, y, tz + dz, Material.DARK_OAK_STAIRS, BlockFace.WEST);
            }
        }
        for (int k = 0; k < 3; k++) set(tx, y + k, tz, Material.DARK_OAK_FENCE);                // finial
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}})                       // corner pinnacles
            for (int k = 1; k <= 2; k++) set(tx + c[0], gy + 10 + k, tz + c[1], Material.DARK_OAK_FENCE);
    }

    // ================================================================== market, farms, pond, trees, logs

    private void stall(int sx, int sz) {
        int gy = groundY(sx, sz);
        for (int[] c : new int[][]{{-2, -1}, {2, -1}, {-2, 1}, {2, 1}}) {
            for (int y = 1; y <= 3; y++) set(sx + c[0], gy + y, sz + c[1], Material.DARK_OAK_FENCE);
        }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            set(sx + dx, gy + 4, sz + dz, ((dx + 2) & 1) == 0 ? Material.RED_WOOL : Material.WHITE_WOOL);   // striped awning
        }
        fill(sx - 1, gy + 1, sz, sx + 1, gy + 1, sz, Material.SPRUCE_PLANKS);                              // counter
        set(sx, gy + 2, sz, Material.BARREL);
        set(sx - 1, gy + 2, sz, Material.FLOWER_POT);
        set(sx + 1, gy + 2, sz, Material.LANTERN);
    }

    private void farm(int fx, int fz, int w, int d, Material crop) {
        int gy = groundY(fx + w / 2, fz + d / 2);
        clear(fx - 1, gy + 1, fz - 1, fx + w, gy + 3, fz + d);
        for (int x = fx - 1; x <= fx + w; x++) for (int z = fz - 1; z <= fz + d; z++) {
            boolean border = x == fx - 1 || x == fx + w || z == fz - 1 || z == fz + d;
            if (border) {
                set(x, gy, z, Material.DARK_OAK_LOG);
                if (!(x == fx + w / 2 && z == fz - 1)) set(x, gy + 1, z, Material.DARK_OAK_FENCE);
                continue;
            }
            if ((z - fz) % 3 == 1) {                           // irrigation channel
                set(x, gy, z, Material.WATER);
                continue;
            }
            BlockData soil = Material.FARMLAND.createBlockData();
            if (soil instanceof Farmland f) f.setMoisture(f.getMaximumMoisture());
            set(x, gy, z, soil);
            BlockData plant = crop.createBlockData();
            if (plant instanceof Ageable a) a.setAge(a.getMaximumAge());
            set(x, gy + 1, z, plant);
        }
    }

    private void pond(int px, int pz, int r) {
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz * 1.4);
            int x = px + dx, z = pz + dz, gy = groundY(x, z);
            if (dist <= r) {
                clear(x, gy + 1, z, x, gy + 4, z);
                set(x, gy - 1, z, Material.CLAY);
                set(x, gy, z, Material.WATER);
            } else if (dist <= r + 1.5) {
                set(x, gy, z, Material.SAND);
            }
        }
        int gy = groundY(px - r, pz);
        for (int i = 0; i < 6; i++) {                                                          // little dock
            set(px - r + i, gy + 1, pz, Material.SPRUCE_PLANKS);
            if (i % 2 == 0) { set(px - r + i, gy + 2, pz - 1, Material.DARK_OAK_FENCE); set(px - r + i, gy + 2, pz + 1, Material.DARK_OAK_FENCE); }
        }
    }

    private void cherryTree(int tx, int tz) {
        int gy = groundY(tx, tz);
        for (int y = 1; y <= 6; y++) set(tx, gy + y, tz, Material.CHERRY_LOG);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy <= 4; dy++) {
            double dist = Math.sqrt(dx * dx + dz * dz + (dy - 2) * (dy - 2) * 1.6);
            if (dist > 3.1 || (dx == 0 && dz == 0 && dy < 2)) continue;
            if (absAt(tx + dx, gy + 5 + dy, tz + dz) != Material.AIR) continue;
            BlockData leaves = Material.CHERRY_LEAVES.createBlockData();
            if (leaves instanceof Leaves l) l.setPersistent(true);
            set(tx + dx, gy + 5 + dy, tz + dz, leaves);
        }
    }

    private void logPile(int lx, int lz) {
        int gy = groundY(lx, lz);
        for (int row = 0; row < 2; row++) for (int i = 0; i < 4 - row; i++) {
            log(lx + i, gy + 1 + row, lz, Material.SPRUCE_LOG, Axis.Z);
            log(lx + i, gy + 1 + row, lz + 1, Material.SPRUCE_LOG, Axis.Z);
            log(lx + i, gy + 1 + row, lz + 2, Material.SPRUCE_LOG, Axis.Z);
        }
    }

    // ================================================================== dirt paths

    private void paths() {
        int[][] targets = {{-30, -26}, {30, -26}, {-30, 26}, {30, 26}, {-46, -4}, {-40, 34}, {42, 2}, {0, -18}, {0, 18}};
        for (int[] t : targets) {
            double dx = t[0], dz = t[1], len = Math.sqrt(dx * dx + dz * dz);
            for (int i = 0; i <= (int) len; i++) {
                int x = cx + (int) Math.round(dx * i / len), z = cz + (int) Math.round(dz * i / len);
                if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) <= 9) continue;
                for (int[] o : new int[][]{{0, 0}, {1, 0}, {0, 1}}) {
                    int px = x + o[0], pz = z + o[1], gy = groundY(px, pz);
                    Material top = absAt(px, gy, pz);
                    if (!top.isSolid() || top == Material.SPRUCE_PLANKS || top == Material.COBBLESTONE || top == Material.STONE_BRICKS
                            || top == Material.WATER || Tag.LOGS.isTagged(top)) continue;
                    set(px, gy, pz, ((px * 7 + pz * 3) % 4 == 0) ? Material.GRAVEL : ((px + pz) % 5 == 0 ? Material.COARSE_DIRT : Material.DIRT_PATH));
                }
            }
        }
    }
}
