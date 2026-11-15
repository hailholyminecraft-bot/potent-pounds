package net.potendpound;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shade Dome: a sphere of dark particles. Everything that is inside can't leave until it fades, and
 * (see ShadeManager) nothing inside can hurt the owner.
 */
public final class ShadeDome {

    private static final Particle.DustOptions VIOLET = new Particle.DustOptions(Color.fromRGB(120, 60, 220), 1.4f);
    private static final Particle.DustOptions VIOLET_LT = new Particle.DustOptions(Color.fromRGB(190, 150, 255), 1.2f);
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(10, 8, 16), 1.6f);

    private final PotendPoundPlugin plugin;
    private final ShadeManager manager;
    public final Player owner;
    public final Location center;
    public final double radius;
    private final boolean confineOwner, blockEntry;
    private final Map<UUID, Location> lastInside = new HashMap<>();
    private int ticksLeft, t;
    private BukkitTask task;

    public ShadeDome(PotendPoundPlugin plugin, ShadeManager manager, Player owner, Location center) {
        this.plugin = plugin;
        this.manager = manager;
        this.owner = owner;
        this.center = center;
        var cfg = plugin.getConfig();
        this.radius = Math.max(2.0, cfg.getDouble("dome.radius", 8.0));
        this.ticksLeft = Math.max(1, cfg.getInt("dome.duration-seconds", 15)) * 20;
        this.confineOwner = cfg.getBoolean("dome.owner-confined", true);
        this.blockEntry = cfg.getBoolean("dome.block-entry", false);
    }

    public boolean contains(Location l) {
        return l.getWorld().equals(center.getWorld()) && l.distanceSquared(center) <= radius * radius;
    }

    /** Is this player locked inside (used to stop teleporting out)? */
    public boolean confines(Player p) {
        if (p.equals(owner) && !confineOwner) return false;
        return lastInside.containsKey(p.getUniqueId());
    }

    public void start() {
        World w = center.getWorld();
        w.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.5f, 0.6f);
        w.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 2.5f, 0.5f);
        w.spawnParticle(Particle.SQUID_INK, center, 80, radius / 2, radius / 3, radius / 2, 0.1);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void tick() {
        if (!owner.isOnline() || owner.isDead() || ticksLeft-- <= 0) {
            end();
            return;
        }
        t++;
        World w = center.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (t % 2 == 0) {                                          // shimmering shell
            for (int i = 0; i < 70; i++) {
                Vector v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
                if (v.lengthSquared() < 1.0E-6) continue;
                v.normalize().multiply(radius);
                w.spawnParticle(Particle.DUST, center.getX() + v.getX(), center.getY() + v.getY(), center.getZ() + v.getZ(),
                        1, 0, 0, 0, 0, i % 3 == 0 ? BLACK : (i % 3 == 1 ? VIOLET : VIOLET_LT));
            }
        }
        if (t % 4 == 0) {                                          // glowing rings
            for (double yo : new double[]{-radius * 0.55, 0, radius * 0.55}) {
                double r = Math.sqrt(radius * radius - yo * yo);
                for (int k = 0; k < 40; k++) {
                    double a = k * Math.PI * 2 / 40 + t * 0.03;
                    w.spawnParticle(Particle.DUST, center.getX() + Math.cos(a) * r, center.getY() + yo, center.getZ() + Math.sin(a) * r,
                            1, 0, 0, 0, 0, VIOLET_LT);
                }
            }
        }
        w.spawnParticle(Particle.SCULK_SOUL, center.clone().add(rnd.nextDouble(-radius, radius) * 0.7, rnd.nextDouble(0, 2), rnd.nextDouble(-radius, radius) * 0.7), 1, 0, 0, 0, 0.02);
        if (ticksLeft == 40) w.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.6f);
        confine(w);
    }

    private void confine(World w) {
        for (Entity e : w.getNearbyEntities(center, radius + 4, radius + 4, radius + 4)) {
            if (!(e instanceof LivingEntity) || e instanceof ArmorStand) continue;
            if (e instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
            if (e.equals(owner) && !confineOwner) continue;
            Location l = e.getLocation();
            double d = l.distance(center);
            UUID id = e.getUniqueId();
            if (d <= radius - 0.3) {
                lastInside.put(id, l.clone());
            } else if (lastInside.containsKey(id)) {                // tried to leave: pull back inside
                Vector inward = center.toVector().subtract(l.toVector()).normalize();
                Location back = l.clone().add(inward.multiply(d - (radius - 1.2)));
                back.setYaw(l.getYaw());
                back.setPitch(l.getPitch());
                if (back.getBlock().getType().isSolid()) back = lastInside.get(id).clone();
                e.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
                e.setVelocity(new Vector());
                if (t % 4 == 0) {
                    w.spawnParticle(Particle.SQUID_INK, back.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.05);
                    w.playSound(back, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.5f);
                }
            } else if (blockEntry && d < radius + 0.8) {            // optional: nobody walks in either
                Vector away = l.toVector().subtract(center.toVector()).normalize().multiply(0.6).setY(0.2);
                e.setVelocity(away);
            }
        }
    }

    public void end() {
        if (task != null) task.cancel();
        task = null;
        World w = center.getWorld();
        w.spawnParticle(Particle.SQUID_INK, center, 150, radius / 2, radius / 3, radius / 2, 0.15);
        w.spawnParticle(Particle.SCULK_SOUL, center, 60, radius / 2, radius / 3, radius / 2, 0.05);
        w.playSound(center, Sound.ENTITY_WITHER_BREAK_BLOCK, 2f, 0.7f);
        manager.domeEnded(owner.getUniqueId());
    }
}
