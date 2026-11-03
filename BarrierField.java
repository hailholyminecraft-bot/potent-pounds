package net.potendpound;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Fortress of Wind": a shockwave, then a force-field cube that follows the owner and
 * repels everything (mobs, players, projectiles, items). It is made of particles + velocity,
 * not real blocks, so nothing is ever placed in the world and nothing needs cleaning up.
 */
public final class BarrierField {

    private static final Particle.DustOptions GREY = new Particle.DustOptions(Color.fromRGB(165, 165, 175), 1.4f);
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(12, 12, 16), 1.4f);

    private final PotendPoundPlugin plugin;
    private final Map<UUID, BukkitTask> fields = new HashMap<>();

    public BarrierField(PotendPoundPlugin plugin) {
        this.plugin = plugin;
    }

    public void activate(Player owner) {
        stop(owner.getUniqueId());
        var cfg = plugin.getConfig();
        double half = cfg.getDouble("barrier.half-size", 3.5);
        int duration = cfg.getInt("barrier.duration-seconds", 6) * 20;
        double burst = cfg.getDouble("barrier.burst-strength", 1.8);
        double hold = cfg.getDouble("barrier.hold-strength", 0.7);

        shockwave(owner, half * 2.5, burst);

        UUID id = owner.getUniqueId();
        BukkitTask task = new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead() || elapsed >= duration) {
                    stop(id);
                    if (owner.isOnline()) {
                        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.4f);
                    }
                    return;
                }
                Location c = owner.getLocation().add(0, 1, 0);
                if (elapsed % 4 == 0) drawBox(c, half);
                repel(owner, c, half, hold);
                elapsed += 2;
            }
        }.runTaskTimer(plugin, 1L, 2L);
        fields.put(id, task);
    }

    private void shockwave(Player owner, double radius, double strength) {
        World w = owner.getWorld();
        Location c = owner.getLocation().add(0, 1, 0);
        w.playSound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.6f, 0.7f);
        w.playSound(c, Sound.ENTITY_BREEZE_WIND_BURST, 1.6f, 0.8f);
        w.spawnParticle(Particle.EXPLOSION, c, 1);
        for (int i = 0; i < 48; i++) {
            double a = Math.PI * 2 * i / 48;
            Vector dir = new Vector(Math.cos(a), 0, Math.sin(a));
            for (double r = 1; r <= radius; r += 1.5) {
                w.spawnParticle(Particle.CLOUD, c.clone().add(dir.clone().multiply(r)).add(0, -0.7, 0), 1, 0, 0, 0, 0.02);
            }
        }
        for (Entity e : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!pushable(owner, e)) continue;
            Vector away = e.getLocation().toVector().subtract(c.toVector());
            double dist = Math.max(0.5, away.length());
            double falloff = 1.0 - Math.min(0.6, dist / radius * 0.6);
            e.setVelocity(horizontal(away).multiply(strength * falloff).setY(0.45));
        }
    }

    private void repel(Player owner, Location c, double half, double strength) {
        double reach = half + 1.5;
        for (Entity e : c.getWorld().getNearbyEntities(c, reach, reach, reach)) {
            if (!pushable(owner, e)) continue;
            Vector off = e.getLocation().toVector().subtract(c.toVector());
            double margin = half + 0.6;
            if (Math.abs(off.getX()) > margin || Math.abs(off.getY()) > margin || Math.abs(off.getZ()) > margin) continue;
            e.setVelocity(horizontal(off).multiply(strength).setY(0.2));
        }
    }

    private boolean pushable(Player owner, Entity e) {
        if (e.equals(owner) || e.isDead() || !e.isValid() || e instanceof Hanging) return false;
        if (e.equals(owner.getVehicle()) || owner.getPassengers().contains(e)) return false;
        return !(e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR);
    }

    private Vector horizontal(Vector v) {
        Vector h = new Vector(v.getX(), 0, v.getZ());
        if (h.lengthSquared() < 1.0E-4) {
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            return new Vector(Math.cos(a), 0, Math.sin(a));
        }
        return h.normalize();
    }

    private void drawBox(Location c, double half) {
        World w = c.getWorld();
        double[] s = {-half, half};
        int n = 0;
        for (double a : s) {
            for (double b : s) {
                line(w, c.getX() - half, c.getY() + a, c.getZ() + b, c.getX() + half, c.getY() + a, c.getZ() + b, n++);
                line(w, c.getX() + a, c.getY() - half, c.getZ() + b, c.getX() + a, c.getY() + half, c.getZ() + b, n++);
                line(w, c.getX() + a, c.getY() + b, c.getZ() - half, c.getX() + a, c.getY() + b, c.getZ() + half, n++);
            }
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            Location p = c.clone().add(r.nextDouble(-half, half), r.nextDouble(-half, half), r.nextDouble(-half, half));
            w.spawnParticle(Particle.SMOKE, p, 1, 0, 0, 0, 0.01);
        }
    }

    private void line(World w, double x1, double y1, double z1, double x2, double y2, double z2, int seed) {
        double len = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = Math.max(1, (int) Math.round(len));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location p = new Location(w, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t);
            w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, ((i + seed) % 2 == 0) ? GREY : BLACK);
        }
    }

    public void stop(UUID id) {
        BukkitTask t = fields.remove(id);
        if (t != null) t.cancel();
    }

    public void stopAll() {
        fields.values().forEach(BukkitTask::cancel);
        fields.clear();
    }
}
