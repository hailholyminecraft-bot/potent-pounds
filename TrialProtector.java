package net.potendpound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Breeze;
import org.bukkit.entity.BreezeWindCharge;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Trial Protector / Big Breeze: a huge, indigo, horned hurricane guardian that never stops moving.
 * The hitbox is a real (invisible, scaled, AI-less) Breeze; the body is item displays using the pack's models.
 *
 * Attacks: Hurricane Punch (dash + arm swing blast), Wind Burst (wind charges in every direction),
 * Levitate (vortex that lifts players), Tornado Summon (small tornadoes that chase players).
 */
public final class TrialProtector {

    public static final String TAG = "potendpound_boss";
    public static final String TAG_PART = "potendpound_boss_part";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private enum State { IDLE, PUNCH_WINDUP, BURST_WINDUP, LEVITATE_CAST, TORNADO_CAST, RECOVER }

    private final PotendPoundPlugin plugin;
    private final BossManager manager;
    private final World world;
    public final Breeze body;
    private final ItemDisplay torso, head, armL, armR, wind;
    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private final List<Tornado> tornadoes = new ArrayList<>();
    private BukkitTask task;

    private final double scale;
    private final float yawOffset;
    private final double homeX, homeZ;
    private final Vector vel = new Vector();
    private State state = State.IDLE;
    private int t, stateTicks, cooldown = 50, lastAttack = -1, orbitDir = 1;
    private boolean phase2, punchRight = true;
    private float yaw;
    private double angL, angR, tgtL, tgtR, splay, splayTgt = 0.12, hoverBonus;
    private Player target;

    public TrialProtector(PotendPoundPlugin plugin, BossManager manager, Location loc) {
        this.plugin = plugin;
        this.manager = manager;
        this.world = loc.getWorld();
        var cfg = plugin.getConfig();
        this.scale = cfg.getDouble("boss.scale", 3.6);
        this.yawOffset = (float) cfg.getDouble("boss.model-yaw-offset", 0.0);
        double hp = cfg.getDouble("boss.health", 300.0);
        this.homeX = loc.getX();
        this.homeZ = loc.getZ();

        body = world.spawn(loc, Breeze.class, b -> {
            b.setAI(false);
            b.setGravity(false);
            b.setSilent(true);
            b.setInvisible(true);
            b.setPersistent(false);
            b.setRemoveWhenFarAway(false);
            b.addScoreboardTag(TAG);
            b.customName(MM.deserialize("Trial Protector"));
            b.setCustomNameVisible(false);
            var sc = b.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(scale);
            var mh = b.getAttribute(Attribute.MAX_HEALTH);
            if (mh != null) mh.setBaseValue(hp);
            var ar = b.getAttribute(Attribute.ARMOR);
            if (ar != null) ar.setBaseValue(cfg.getDouble("boss.defense", 12.0));
            b.setHealth(hp);
        });
        torso = part("boss_torso", scale, 14);
        head = part("boss_head", scale, 15);
        armL = part("boss_arm_l", scale, 14);
        armR = part("boss_arm_r", scale, 14);
        wind = part("boss_wind", scale, 15);

        bar = BossBar.bossBar(MM.deserialize("<gradient:#c9bdff:#7a6cff><b>Trial Protector</b></gradient>"),
                1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        world.playSound(loc, Sound.ENTITY_WARDEN_EMERGE, 4f, 0.7f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc.clone().add(0, 3, 0), 2, 1, 1, 1, 0);
        sync();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ================================================================== model parts

    private ItemDisplay part(String model, double sc, int brightness) {
        return partAt(body.getLocation(), model, sc, brightness);
    }

    private ItemDisplay partAt(Location at, String model, double sc, int brightness) {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("potendpound:" + model));
        it.setItemMeta(m);
        return world.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBrightness(new Display.Brightness(brightness, brightness));
            d.setPersistent(false);
            d.addScoreboardTag(TAG_PART);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f((float) sc), new Quaternionf()));
            d.setInterpolationDuration(2);
        });
    }

    private void place(ItemDisplay d, double ox, double oy, double oz) {
        Location b = body.getLocation();
        double k = scale / 3.2;
        ox *= k;
        oy *= k;
        oz *= k;
        double r = Math.toRadians(yaw + yawOffset);
        d.teleport(new Location(world,
                b.getX() + ox * Math.cos(r) - oz * Math.sin(r), b.getY() + oy,
                b.getZ() + ox * Math.sin(r) + oz * Math.cos(r), yaw + yawOffset, 0f));
    }

    private void pose(ItemDisplay d, Quaternionf q, double sc) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f((float) sc), new Quaternionf()));
    }

    private void sync() {
        double bob = Math.sin(t * 0.12) * 0.12;
        place(wind, 0, 1.2, 0);
        place(torso, 0, 3.0 + bob, 0);
        place(head, 0, 5.4 + bob * 1.4, 0);
        place(armR, 2.5, 4.2 + bob, 0);
        place(armL, -2.5, 4.2 + bob, 0);
        pose(armR, new Quaternionf().rotateX((float) angR).rotateZ((float) -splay), scale);
        pose(armL, new Quaternionf().rotateX((float) angL).rotateZ((float) splay), scale);
        pose(wind, new Quaternionf().rotateY(t * 0.45f), scale);
    }

    // ================================================================== helpers

    private static float turn(float cur, float want, float max) {
        float diff = ((want - cur + 540f) % 360f) - 180f;
        diff = Math.max(-max, Math.min(max, diff));
        return cur + diff;
    }

    private double floorY(Location l) {
        int x = l.getBlockX(), z = l.getBlockZ();
        for (int y = l.getBlockY(); y > l.getBlockY() - 40 && y > world.getMinHeight(); y--) {
            if (world.getBlockAt(x, y, z).isSolid()) return y + 1;
        }
        return l.getY() - 3;
    }

    private boolean blocked(double x, double y, double z) {
        for (int dy : new int[]{1, 3, 5}) {
            if (world.getBlockAt((int) Math.floor(x), (int) Math.floor(y) + dy, (int) Math.floor(z)).isSolid()) return true;
        }
        return false;
    }

    private boolean valid(Player p) {
        return p.isOnline() && !p.isDead() && p.getWorld().equals(world)
                && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    private List<Player> playersWithin(double horizontal) {
        List<Player> out = new ArrayList<>();
        Location b = body.getLocation();
        for (Player p : world.getPlayers()) {
            if (!valid(p)) continue;
            if (Math.hypot(p.getLocation().getX() - b.getX(), p.getLocation().getZ() - b.getZ()) <= horizontal) out.add(p);
        }
        return out;
    }

    private void pickTarget() {
        if (target != null && valid(target) && target.getLocation().distanceSquared(body.getLocation()) < 60 * 60 && t % 40 != 0) return;
        Player best = null;
        double bd = 48 * 48;
        for (Player p : world.getPlayers()) {
            if (!valid(p)) continue;
            double d = p.getLocation().distanceSquared(body.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        target = best;
    }

    // ================================================================== main loop

    private void tick() {
        if (!body.isValid() || body.isDead()) {
            remove();
            return;
        }
        t++;
        pickTarget();
        Location bl = body.getLocation();
        double floor = floorY(bl);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (t % 110 == 0) orbitDir = rnd.nextBoolean() ? 1 : -1;       // change strafing direction now and then

        // ---- steering: orbit the target (or wander around home), dash for the punch, crawl while casting
        double top = phase2 ? 0.52 : 0.4;
        double cxT = target != null ? target.getLocation().getX() : homeX;
        double czT = target != null ? target.getLocation().getZ() : homeZ;
        double rx = bl.getX() - cxT, rz = bl.getZ() - czT;
        double dist = Math.max(0.01, Math.hypot(rx, rz));
        double wx, wz;
        if (state == State.PUNCH_WINDUP && target != null) {
            wx = -rx / dist * (phase2 ? 0.95 : 0.8);
            wz = -rz / dist * (phase2 ? 0.95 : 0.8);
        } else {
            double ang = Math.atan2(rz, rx) + orbitDir * 0.07;
            double radius = (target != null ? 10.5 : 8.0) + Math.sin(t * 0.03) * 3.0;
            double gx = cxT + Math.cos(ang) * radius - bl.getX(), gz = czT + Math.sin(ang) * radius - bl.getZ();
            double gd = Math.max(0.01, Math.hypot(gx, gz));
            double sp = top * Math.min(1.0, gd / 4.0);
            if (state == State.BURST_WINDUP || state == State.LEVITATE_CAST || state == State.TORNADO_CAST) sp *= 0.25;
            wx = gx / gd * sp;
            wz = gz / gd * sp;
        }
        vel.setX(vel.getX() * 0.85 + wx * 0.15);
        vel.setZ(vel.getZ() * 0.85 + wz * 0.15);

        double wantY = floor + 3.4 + Math.sin(t * 0.05) * 1.3 + hoverBonus;
        if (state == State.PUNCH_WINDUP && target != null) wantY = Math.max(floor + 2.0, target.getLocation().getY() + 1.0);
        double ny = bl.getY() + Math.max(-0.6, Math.min(0.6, (wantY - bl.getY()) * 0.12));
        double nx = bl.getX() + vel.getX(), nz = bl.getZ() + vel.getZ();
        if (blocked(nx, ny, nz)) {
            vel.multiply(-0.4);
            nx = bl.getX();
            nz = bl.getZ();
        }
        if (target != null) {
            double dx = target.getLocation().getX() - nx, dz = target.getLocation().getZ() - nz;
            yaw = turn(yaw, (float) Math.toDegrees(Math.atan2(-dx, dz)), 16f);
        }
        body.teleport(new Location(world, nx, ny, nz, yaw, 0f));

        stateMachine(dist);

        angL += (tgtL - angL) * 0.3;
        angR += (tgtR - angR) * 0.3;
        splay += (splayTgt - splay) * 0.2;
        sync();
        updateTornadoes();
        if (t % 5 == 0) updateBar();
        if (!phase2 && body.getHealth() < body.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.5) enrage();
        if (t % 2 == 0) world.spawnParticle(Particle.CLOUD, body.getLocation().add(0, 0.6, 0), 4, 0.9, 0.2, 0.9, 0.03);
    }

    private void relax() {
        double sway = Math.sin(t * 0.16) * 0.12;
        tgtL = sway;
        tgtR = -sway;
        splayTgt = 0.12;
        hoverBonus = 0;
    }

    private void stateMachine(double dist) {
        switch (state) {
            case IDLE -> {
                relax();
                if (cooldown > 0) cooldown--;
                else if (target != null) chooseAttack();
            }
            case PUNCH_WINDUP -> {
                stateTicks++;
                if (punchRight) tgtR = 1.3; else tgtL = 1.3;               // fist pulled back
                world.spawnParticle(Particle.CLOUD, body.getLocation().add(0, 4, 0), 6, 1.8, 0.8, 1.8, 0.1);
                if (stateTicks == 1) world.playSound(body.getLocation(), Sound.ENTITY_BREEZE_CHARGE, 3f, 0.5f);
                if (stateTicks >= 18 && (dist < 7.0 || stateTicks >= 30)) {
                    punchStrike();
                    state = State.RECOVER;
                    stateTicks = 0;
                }
            }
            case BURST_WINDUP -> {
                stateTicks++;
                tgtL = tgtR = -1.5;
                splayTgt = 0.7;
                world.spawnParticle(Particle.ELECTRIC_SPARK, body.getLocation().add(0, 4, 0), 8, 2, 1.2, 2, 0.15);
                if (stateTicks == 1) world.playSound(body.getLocation(), Sound.ENTITY_BREEZE_INHALE, 3f, 0.6f);
                if (stateTicks >= 26) {
                    windBurst();
                    state = State.RECOVER;
                    stateTicks = 0;
                }
            }
            case LEVITATE_CAST -> {
                stateTicks++;
                tgtL = tgtR = -2.5;
                splayTgt = 0.9;
                hoverBonus = 2.0;
                vortexParticles(body.getLocation());
                if (stateTicks == 1) world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 4f, 0.5f);
                if (stateTicks == 22) levitate();
                if (stateTicks >= 40) {
                    state = State.RECOVER;
                    stateTicks = 0;
                }
            }
            case TORNADO_CAST -> {
                stateTicks++;
                tgtL = tgtR = -0.9;
                splayTgt = 0.5;
                world.spawnParticle(Particle.CLOUD, body.getLocation().add(0, 1, 0), 12, 2, 0.4, 2, 0.15);
                if (stateTicks == 1) world.playSound(body.getLocation(), Sound.ENTITY_WARDEN_ROAR, 3f, 0.8f);
                if (stateTicks == 24) summonTornadoes();
                if (stateTicks >= 34) {
                    state = State.RECOVER;
                    stateTicks = 0;
                }
            }
            case RECOVER -> {
                stateTicks++;
                relax();
                if (stateTicks >= 22) {
                    state = State.IDLE;
                    stateTicks = 0;
                    double secs = plugin.getConfig().getDouble("boss.attack-interval-seconds", 4.0);
                    cooldown = (int) (secs * 20 * (phase2 ? 0.6 : 1.0));
                }
            }
        }
    }

    private void chooseAttack() {
        double dist = target.getLocation().distance(body.getLocation());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double[] w = {dist <= 18 ? 3.0 : 1.0, 2.0, dist <= 16 ? 1.6 : 0.4, phase2 ? 2.4 : 1.6};   // punch, burst, levitate, tornado
        if (lastAttack >= 0) w[lastAttack] = 0;
        double sum = 0;
        for (double v : w) sum += v;
        double r = rnd.nextDouble() * sum;
        int pick = 0;
        for (; pick < 3 && r > w[pick]; pick++) r -= w[pick];
        lastAttack = pick;
        stateTicks = 0;
        state = switch (pick) {
            case 0 -> { punchRight = rnd.nextBoolean(); yield State.PUNCH_WINDUP; }
            case 1 -> State.BURST_WINDUP;
            case 2 -> State.LEVITATE_CAST;
            default -> State.TORNADO_CAST;
        };
    }

    // ================================================================== 1. hurricane punch

    private void punchStrike() {
        if (punchRight) tgtR = -1.65; else tgtL = -1.65;
        angR = punchRight ? -0.6 : angR;                       // snap the swing so it reads as a hit
        angL = punchRight ? angL : -0.6;
        Location b = body.getLocation();
        double yawRad = Math.toRadians(yaw);
        Vector facing = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        double dmg = plugin.getConfig().getDouble("boss.punch-damage", 18.0);

        world.playSound(b, Sound.ENTITY_GENERIC_EXPLODE, 5f, 0.6f);
        world.playSound(b, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 5f, 0.5f);
        world.playSound(b, Sound.ENTITY_BREEZE_WIND_BURST, 5f, 0.5f);
        for (double r = 2; r <= 11; r += 1.5) {                // blast fan in front
            for (double a = -65; a <= 65; a += 13) {
                Vector d = facing.clone().rotateAroundY(Math.toRadians(a)).multiply(r);
                Location l = b.clone().add(d).add(0, 3.5, 0);
                world.spawnParticle(Particle.CLOUD, l, 2, 0.2, 0.2, 0.2, 0.08);
                if (((int) r) % 2 == 0) world.spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
            }
        }
        for (Player p : playersWithin(12.0)) {
            Location pl = p.getLocation();
            Vector to = new Vector(pl.getX() - b.getX(), 0, pl.getZ() - b.getZ());
            double d = to.length();
            if (d < 0.01 || facing.dot(to.clone().normalize()) < 0.25 || Math.abs(pl.getY() - b.getY()) > 8) continue;
            p.damage(dmg * (d <= 7 ? 1.0 : 0.6), body);
            p.setVelocity(facing.clone().multiply(1.7).setY(0.55));
        }
        vel.add(facing.clone().multiply(0.6));
    }

    // ================================================================== 2. wind burst (every direction)

    private void windBurst() {
        int n = phase2 ? 22 : 16;
        Location center = body.getLocation().add(0, 4.0, 0);
        NamespacedKey mark = manager.chargeKey();
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            double pitch = (i % 3 == 0) ? 0.25 : (i % 3 == 1 ? -0.1 : 0.0);
            Vector dir = new Vector(Math.cos(a), pitch, Math.sin(a)).normalize();
            spawnCharge(center.clone().add(dir.clone().multiply(2.6)), dir, mark);
        }
        if (target != null && valid(target)) {                 // a few aimed ones too
            Vector aim = target.getEyeLocation().subtract(center).toVector().normalize();
            for (int k = -1; k <= 1; k++) spawnCharge(center.clone().add(aim.clone().multiply(2.6)), aim.clone().rotateAroundY(k * 0.12), mark);
        }
        world.playSound(center, Sound.ENTITY_BREEZE_SHOOT, 4f, 0.5f);
        world.spawnParticle(Particle.CLOUD, center, 60, 1.5, 0.8, 1.5, 0.3);
    }

    private void spawnCharge(Location from, Vector dir, NamespacedKey mark) {
        world.spawn(from, BreezeWindCharge.class, c -> {
            c.setShooter(body);
            c.setVelocity(dir.clone().multiply(0.95));
            c.setDirection(dir);
            c.getPersistentDataContainer().set(mark, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ================================================================== 3. levitate (vortex lifts players)

    private void vortexParticles(Location b) {
        for (int k = 0; k < 14; k++) {
            double h = (t * 0.35 + k * 0.9) % 9.0;
            double ang = t * 0.5 + k * 0.7;
            double r = 2.0 + h * 0.9;
            world.spawnParticle(Particle.CLOUD, b.getX() + Math.cos(ang) * r, b.getY() + h * 0.7, b.getZ() + Math.sin(ang) * r, 1, 0, 0, 0, 0.01);
        }
    }

    private void levitate() {
        double secs = plugin.getConfig().getDouble("boss.levitate-seconds", 2.5);
        int ticks = (int) (secs * 20);
        Location b = body.getLocation();
        world.playSound(b, Sound.ENTITY_BREEZE_WIND_BURST, 5f, 0.4f);
        for (Player p : playersWithin(15.0)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, ticks, 2));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks + 100, 0));      // so the drop is survivable
            p.sendActionBar(MM.deserialize("<gradient:#c9bdff:#7a6cff>The vortex lifts you...</gradient>"));
        }
        final int life = ticks;
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                for (Player p : world.getPlayers()) {
                    if (!p.hasPotionEffect(PotionEffectType.LEVITATION)) continue;
                    world.spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.3, 0), 6, 0.5, 0.2, 0.5, 0.02);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 3, 0.4, 0.6, 0.4, 0.2);
                }
                if (++n > life || !body.isValid()) cancel();
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ================================================================== 4. tornado summon

    private void summonTornadoes() {
        int n = phase2 ? 5 : 3;
        Location b = body.getLocation();
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + t * 0.1;
            Location l = new Location(world, b.getX() + Math.cos(a) * 5, b.getY(), b.getZ() + Math.sin(a) * 5);
            l.setY(floorY(l.clone().add(0, 4, 0)));
            tornadoes.add(new Tornado(l));
        }
        world.playSound(b, Sound.ENTITY_BREEZE_WIND_BURST, 4f, 0.6f);
    }

    private final class Tornado {
        final ItemDisplay disp;
        double x, y, z;
        int life = 220;
        final Map<UUID, Integer> cooldown = new HashMap<>();

        Tornado(Location l) {
            x = l.getX();
            y = l.getY();
            z = l.getZ();
            disp = partAt(l, "boss_wind", 1.6, 15);
        }

        boolean tick() {
            life--;
            Player tgt = null;
            double bd = 40 * 40;
            for (Player p : world.getPlayers()) {
                if (!valid(p)) continue;
                double d = p.getLocation().distanceSquared(new Location(world, x, y, z));
                if (d < bd) { bd = d; tgt = p; }
            }
            if (tgt != null) {
                double dx = tgt.getLocation().getX() - x, dz = tgt.getLocation().getZ() - z, d = Math.max(0.01, Math.hypot(dx, dz));
                double sp = phase2 ? 0.27 : 0.2;
                x += dx / d * sp;
                z += dz / d * sp;
            }
            y = floorY(new Location(world, x, y + 3, z));
            disp.teleport(new Location(world, x, y + 1.0, z));
            pose(disp, new Quaternionf().rotateY(life * 0.6f), 1.6);
            for (int k = 0; k < 5; k++) {
                double h = (life * 0.2 + k * 0.7) % 3.4, ang = life * 0.7 + k * 1.3, r = 0.3 + h * 0.35;
                world.spawnParticle(Particle.CLOUD, x + Math.cos(ang) * r, y + h, z + Math.sin(ang) * r, 1, 0, 0, 0, 0);
            }
            cooldown.replaceAll((k, v) -> v - 1);
            cooldown.values().removeIf(v -> v <= 0);
            double dmg = plugin.getConfig().getDouble("boss.tornado-damage", 5.0);
            for (Player p : world.getPlayers()) {
                if (!valid(p) || cooldown.containsKey(p.getUniqueId())) continue;
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - x, pl.getZ() - z) > 1.9 || Math.abs(pl.getY() - y) > 4.0) continue;
                p.damage(dmg, body);
                p.setVelocity(new Vector(ThreadLocalRandom.current().nextDouble(-0.6, 0.6), 0.95, ThreadLocalRandom.current().nextDouble(-0.6, 0.6)));
                cooldown.put(p.getUniqueId(), 25);
                world.playSound(pl, Sound.ENTITY_BREEZE_WIND_BURST, 1.5f, 1.3f);
            }
            return life > 0;
        }

        void remove() {
            if (disp.isValid()) disp.remove();
            world.spawnParticle(Particle.CLOUD, x, y + 1, z, 25, 0.5, 0.8, 0.5, 0.12);
        }
    }

    private void updateTornadoes() {
        tornadoes.removeIf(tor -> {
            boolean alive = tor.tick();
            if (!alive) tor.remove();
            return !alive;
        });
    }

    // ================================================================== boss bar, phase 2, death

    private void enrage() {
        phase2 = true;
        bar.color(BossBar.Color.RED);
        world.playSound(body.getLocation(), Sound.ENTITY_WITHER_SPAWN, 3f, 1.4f);
        for (Player p : world.getPlayers()) {
            if (viewers.contains(p.getUniqueId())) p.sendActionBar(MM.deserialize("<red><b>The Trial Protector is enraged!</b>"));
        }
    }

    private void updateBar() {
        double max = body.getAttribute(Attribute.MAX_HEALTH).getValue();
        bar.progress((float) Math.max(0.0, Math.min(1.0, body.getHealth() / max)));
        Set<UUID> now = new HashSet<>();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(body.getLocation()) <= 64 * 64) {
                now.add(p.getUniqueId());
                if (viewers.add(p.getUniqueId())) p.showBossBar(bar);
            }
        }
        for (UUID id : new HashSet<>(viewers)) {
            if (!now.contains(id)) {
                viewers.remove(id);
                Player p = plugin.getServer().getPlayer(id);
                if (p != null) p.hideBossBar(bar);
            }
        }
    }

    private ItemStack named(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + name));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    public void onDeath(EntityDeathEvent e) {
        var cfg = plugin.getConfig();
        e.getDrops().clear();
        e.getDrops().add(named(Material.BREEZE_ROD, "<gradient:#e3d3a8:#b89a62>Breeze Horn</gradient>", "<gray>Used to craft powerful wind-based items."));
        e.getDrops().add(new ItemStack(Material.TRIAL_KEY));
        if (ThreadLocalRandom.current().nextDouble() < 0.25) {
            e.getDrops().add(named(Material.HEAVY_CORE, "<gradient:#bfe6ff:#7a9cff>Wind Core</gradient>", "<gray>A rare core pulsing with wind energy."));
        }
        int lo = cfg.getInt("boss.xp-min", 20), hi = Math.max(lo, cfg.getInt("boss.xp-max", 40));
        e.setDroppedExp(ThreadLocalRandom.current().nextInt(lo, hi + 1));
        Location l = body.getLocation().add(0, 3, 0);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 4, 2, 2, 2, 0);
        world.spawnParticle(Particle.END_ROD, l, 300, 1.5, 2, 1.5, 0.5);
        world.playSound(l, Sound.ENTITY_WARDEN_DEATH, 5f, 0.6f);
        world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 5f, 0.5f);
        for (UUID id : viewers) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) p.sendActionBar(MM.deserialize("<gradient:#c9bdff:#7a6cff><b>The Trial Protector has fallen.</b></gradient>"));
        }
        remove();
    }

    public void remove() {
        if (task != null) task.cancel();
        task = null;
        for (Tornado tor : tornadoes) tor.remove();
        tornadoes.clear();
        for (UUID id : viewers) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        viewers.clear();
        for (ItemDisplay d : new ItemDisplay[]{torso, head, armL, armR, wind}) if (d.isValid()) d.remove();
        manager.unregister(body.getUniqueId());
        if (body.isValid()) body.remove();
    }
}
