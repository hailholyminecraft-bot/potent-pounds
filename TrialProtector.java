package net.potendpound;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Trial Protector: a huge, levitating, muscle-armed breeze guardian.
 * The hitbox is a real (invisible, scaled, AI-less) Breeze; the body is made of item displays that use the
 * custom models from the resource pack. Attacks: a fan of wind charges that hurt you and wear down your armor,
 * and a rising-then-crashing arm slam that damages everything near the ground.
 */
public final class TrialProtector {

    public static final String TAG = "potendpound_boss";
    public static final String TAG_PART = "potendpound_boss_part";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private enum State { IDLE, VOLLEY_WINDUP, SLAM_WINDUP, SLAM_DIVE, RECOVER }

    private final PotendPoundPlugin plugin;
    private final BossManager manager;
    private final World world;
    public final Breeze body;
    private final ItemDisplay torso, head, armL, armR, wind;
    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private BukkitTask task;

    private final double scale;
    private final float yawOffset;
    private final double homeX, homeZ;
    private State state = State.IDLE;
    private int t, stateTicks, cooldown = 60;
    private boolean phase2, lastWasVolley;
    private float yaw;
    private double armAngle, armTarget, hover = 2.8;
    private Player target;

    public TrialProtector(PotendPoundPlugin plugin, BossManager manager, Location loc) {
        this.plugin = plugin;
        this.manager = manager;
        this.world = loc.getWorld();
        var cfg = plugin.getConfig();
        this.scale = cfg.getDouble("boss.scale", 3.2);
        this.yawOffset = (float) cfg.getDouble("boss.model-yaw-offset", 0.0);
        double hp = cfg.getDouble("boss.health", 600.0);
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
            b.setHealth(hp);
        });
        torso = part("boss_torso");
        head = part("boss_head");
        armL = part("boss_arm_l");
        armR = part("boss_arm_r");
        wind = part("boss_wind");

        bar = BossBar.bossBar(MM.deserialize("<gradient:#ffffff:#6b6b75><b>Trial Protector</b></gradient>"),
                1f, BossBar.Color.WHITE, BossBar.Overlay.NOTCHED_10);
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

    // ------------------------------------------------------------------ model parts

    private ItemDisplay part(String model) {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString("potendpound:" + model));
        it.setItemMeta(m);
        return world.spawn(body.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBrightness(new Display.Brightness(13, 13));
            d.setPersistent(false);
            d.addScoreboardTag(TAG_PART);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f((float) scale), new Quaternionf()));
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
        Location l = new Location(world,
                b.getX() + ox * Math.cos(r) - oz * Math.sin(r), b.getY() + oy,
                b.getZ() + ox * Math.sin(r) + oz * Math.cos(r), yaw + yawOffset, 0f);
        d.teleport(l);
    }

    private void pose(ItemDisplay d, Quaternionf q) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f((float) scale), new Quaternionf()));
    }

    private void sync() {
        place(wind, 0, 1.2, 0);
        place(torso, 0, 3.0, 0);
        place(head, 0, 5.4, 0);
        place(armR, 2.0, 4.0, 0);
        place(armL, -2.0, 4.0, 0);
        pose(armR, new Quaternionf().rotateX((float) armAngle).rotateZ(-0.12f));
        pose(armL, new Quaternionf().rotateX((float) armAngle).rotateZ(0.12f));
        pose(wind, new Quaternionf().rotateY(t * 0.35f));
    }

    // ------------------------------------------------------------------ main loop

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

    private void pickTarget() {
        if (target != null && valid(target) && target.getLocation().distanceSquared(body.getLocation()) < 60 * 60 && t % 40 != 0) return;
        Player best = null;
        double bd = 48 * 48;
        for (Player p : world.getPlayers()) {
            if (!valid(p)) continue;
            double d = p.getLocation().distanceSquared(body.getLocation());
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        target = best;
    }

    private void tick() {
        if (!body.isValid() || body.isDead()) {
            remove();
            return;
        }
        t++;
        pickTarget();
        Location bl = body.getLocation();
        double floor = floorY(bl);
        double nx = bl.getX(), nz = bl.getZ();
        double speed = phase2 ? 0.2 : 0.13;
        boolean mobile = state != State.SLAM_DIVE;
        double wantY = floor + hover + (state == State.SLAM_DIVE ? 0 : Math.sin(t * 0.08) * 0.25);

        if (target != null) {
            double dx = target.getLocation().getX() - nx, dz = target.getLocation().getZ() - nz;
            double dist = Math.hypot(dx, dz);
            if (mobile && dist > 8.0) { nx += dx / dist * speed; nz += dz / dist * speed; }
            else if (mobile && dist < 5.0 && dist > 0.1) { nx -= dx / dist * speed * 0.6; nz -= dz / dist * speed * 0.6; }
            yaw = turn(yaw, (float) Math.toDegrees(Math.atan2(-dx, dz)), 10f);
        } else {
            double dx = homeX - nx, dz = homeZ - nz, dist = Math.hypot(dx, dz);
            if (dist > 1.0) { nx += dx / dist * speed * 0.5; nz += dz / dist * speed * 0.5; }
        }
        double vmax = state == State.SLAM_DIVE ? 1.0 : 0.2;
        double ny = bl.getY() + Math.max(-vmax, Math.min(vmax, wantY - bl.getY()));
        if (blocked(nx, ny, nz)) { nx = bl.getX(); nz = bl.getZ(); }
        Location dest = new Location(world, nx, ny, nz, yaw, 0f);
        body.teleport(dest);

        stateMachine(floor);

        armAngle += (armTarget - armAngle) * 0.25;
        sync();
        if (t % 5 == 0) updateBar();
        if (!phase2 && body.getHealth() < body.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.5) enrage();
        if (t % 3 == 0) world.spawnParticle(Particle.CLOUD, body.getLocation().add(0, 0.5, 0), 3, 0.8, 0.2, 0.8, 0.02);
    }

    private void stateMachine(double floor) {
        switch (state) {
            case IDLE -> {
                armTarget = Math.sin(t * 0.1) * 0.08;
                if (cooldown > 0) cooldown--;
                else if (target != null) chooseAttack();
            }
            case VOLLEY_WINDUP -> {
                stateTicks++;
                armTarget = -1.4;
                world.spawnParticle(Particle.ELECTRIC_SPARK, body.getLocation().add(0, 4, 0), 6, 1.5, 1, 1.5, 0.1);
                if (stateTicks == 1) world.playSound(body.getLocation(), Sound.ENTITY_BREEZE_CHARGE, 3f, 0.5f);
                if (stateTicks >= 30) {
                    fireVolley();
                    state = State.RECOVER;
                    stateTicks = 0;
                }
            }
            case SLAM_WINDUP -> {
                stateTicks++;
                hover = 5.5;
                armTarget = -2.95;
                world.spawnParticle(Particle.CLOUD, body.getLocation().add(0, 1, 0), 8, 2, 0.5, 2, 0.1);
                if (stateTicks == 1) world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 4f, 0.5f);
                if (stateTicks >= 30) {
                    state = State.SLAM_DIVE;
                    stateTicks = 0;
                }
            }
            case SLAM_DIVE -> {
                stateTicks++;
                hover = 0.4;
                armTarget = -0.3;
                if (body.getLocation().getY() <= floor + 0.9 || stateTicks > 24) {
                    slamImpact(floor);
                    state = State.RECOVER;
                    stateTicks = 0;
                }
            }
            case RECOVER -> {
                stateTicks++;
                hover = 2.8;
                armTarget = 0;
                if (stateTicks >= 28) {
                    state = State.IDLE;
                    stateTicks = 0;
                    double secs = plugin.getConfig().getDouble("boss.attack-interval-seconds", 5.0);
                    cooldown = (int) (secs * 20 * (phase2 ? 0.6 : 1.0));
                }
            }
        }
    }

    private void chooseAttack() {
        double dist = target.getLocation().distance(body.getLocation());
        boolean slam = dist <= 14 && (lastWasVolley || ThreadLocalRandom.current().nextFloat() < 0.35f);
        stateTicks = 0;
        if (slam) {
            state = State.SLAM_WINDUP;
            lastWasVolley = false;
        } else {
            state = State.VOLLEY_WINDUP;
            lastWasVolley = true;
        }
    }

    // ------------------------------------------------------------------ attacks

    private void fireVolley() {
        if (target == null || !valid(target)) return;
        int count = phase2 ? 9 : 6;
        Location from = body.getLocation().add(0, 4.2, 0);
        Vector forward = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        from.add(forward.clone().multiply(2.2));
        Vector aim = target.getEyeLocation().subtract(from).toVector().normalize();
        NamespacedKey mark = manager.chargeKey();
        for (int i = 0; i < count; i++) {
            double spread = (i - (count - 1) / 2.0) * 0.13;
            Vector dir = aim.clone().rotateAroundY(spread);
            dir.setY(dir.getY() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.06).normalize();
            world.spawn(from, BreezeWindCharge.class, c -> {
                c.setShooter(body);
                c.setVelocity(dir.clone().multiply(1.0));
                c.setDirection(dir);
                c.getPersistentDataContainer().set(mark, PersistentDataType.BYTE, (byte) 1);
            });
        }
        world.playSound(from, Sound.ENTITY_BREEZE_SHOOT, 4f, 0.6f);
        world.spawnParticle(Particle.CLOUD, from, 30, 0.6, 0.6, 0.6, 0.15);
    }

    private void slamImpact(double floorY) {
        Location c = body.getLocation().clone();
        c.setY(floorY);
        double radius = 10.0, dmg = plugin.getConfig().getDouble("boss.slam-damage", 12.0);
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 5f, 0.6f);
        world.playSound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 5f, 0.5f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 3, 2, 0.5, 2, 0);
        for (Player p : world.getPlayers()) {
            if (!valid(p)) continue;
            Location pl = p.getLocation();
            double h = Math.hypot(pl.getX() - c.getX(), pl.getZ() - c.getZ());
            if (h > radius || pl.getY() - floorY > 3.2) continue;       // jump to dodge the ground shockwave
            double mult = h < radius * 0.5 ? 1.0 : 0.6;
            p.damage(dmg * mult, body);
            Vector away = new Vector(pl.getX() - c.getX(), 0, pl.getZ() - c.getZ());
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            p.setVelocity(away.normalize().multiply(1.3).setY(0.7));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 1));
        }
        new BukkitRunnable() {
            int r = 1;

            @Override
            public void run() {
                for (int k = 0; k < 48; k++) {
                    double a = k * Math.PI * 2 / 48;
                    world.spawnParticle(Particle.CLOUD, c.getX() + Math.cos(a) * r, c.getY() + 0.2, c.getZ() + Math.sin(a) * r, 1, 0, 0.1, 0, 0.05);
                    if (r % 2 == 0) world.spawnParticle(Particle.CRIT, c.getX() + Math.cos(a) * r, c.getY() + 0.4, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0.2);
                }
                if (++r > 10) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void enrage() {
        phase2 = true;
        bar.color(BossBar.Color.RED);
        world.playSound(body.getLocation(), Sound.ENTITY_WITHER_SPAWN, 3f, 1.4f);
        for (Player p : world.getPlayers()) {
            if (viewers.contains(p.getUniqueId())) p.sendActionBar(MM.deserialize("<red><b>The Trial Protector is enraged!</b>"));
        }
    }

    // ------------------------------------------------------------------ boss bar + cleanup

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

    public void onDeath(EntityDeathEvent e) {
        e.getDrops().clear();
        e.setDroppedExp(400);
        Location l = body.getLocation().add(0, 3, 0);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 4, 2, 2, 2, 0);
        world.spawnParticle(Particle.END_ROD, l, 300, 1.5, 2, 1.5, 0.5);
        world.playSound(l, Sound.ENTITY_WARDEN_DEATH, 5f, 0.6f);
        world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 5f, 0.5f);
        for (UUID id : viewers) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) p.sendActionBar(MM.deserialize("<gradient:#ffffff:#777780><b>The Trial Protector has fallen.</b></gradient>"));
        }
        remove();
    }

    public void remove() {
        if (task != null) task.cancel();
        task = null;
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
