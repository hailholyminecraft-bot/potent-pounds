package net.potendpound;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Right-clicking the weapon on the altar: it levitates and spins while particles pour out,
 * then bursts in a harmless explosion (no damage, no block damage) and drops as an item.
 */
public final class AltarListener implements Listener {

    private static final String BUSY = "potendpound_busy";
    private static final int RISE = 100;

    private final PotendPoundPlugin plugin;
    private final PotendItem items;

    public AltarListener(PotendPoundPlugin plugin, PotendItem items) {
        this.plugin = plugin;
        this.items = items;
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Interaction inter)) return;
        if (!inter.getScoreboardTags().contains(TrialChamber.TAG_ALTAR)) return;
        e.setCancelled(true);
        if (inter.getScoreboardTags().contains(BUSY)) return;
        inter.addScoreboardTag(BUSY);

        ItemDisplay found = null;
        for (Entity n : inter.getNearbyEntities(2, 4, 2)) {
            if (n instanceof ItemDisplay d && d.getScoreboardTags().contains(TrialChamber.TAG_ITEM)) {
                found = d;
                break;
            }
        }
        final ItemDisplay display = found;
        final Location base = display != null ? display.getLocation() : inter.getLocation().add(0, 1.2, 0);
        final World w = base.getWorld();

        w.playSound(base, Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.6f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                double p = Math.min(1.0, t / (double) RISE);
                double ease = 1 - Math.pow(1 - p, 2);
                Location cur = base.clone().add(0, ease * 3.2 + Math.sin(t * 0.25) * 0.08, 0);
                cur.setYaw((float) ((t * t * 0.15 + t * 4.0) % 360.0));
                if (display != null && display.isValid()) display.teleport(cur);

                // swirling particles
                for (int a = 0; a < 3; a++) {
                    double ang = t * 0.35 + a * 2.094;
                    double r = 1.2 + 0.8 * Math.sin(t * 0.1 + a);
                    double yy = ((t * 0.07 + a * 0.9) % 3.2) - 1.0;
                    w.spawnParticle(Particle.END_ROD, cur.getX() + Math.cos(ang) * r, cur.getY() + yy, cur.getZ() + Math.sin(ang) * r, 1, 0, 0, 0, 0);
                }
                w.spawnParticle(Particle.ENCHANT, cur.clone().add(0, 0.6, 0), 14, 1.6, 1.3, 1.6, 0.6);
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, cur, 3, 0.4, 0.8, 0.4, 0.02);
                w.spawnParticle(Particle.REVERSE_PORTAL, cur, 8, 0.6, 0.8, 0.6, 0.15);
                if (t > 45) w.spawnParticle(Particle.ELECTRIC_SPARK, cur, 6, 0.8, 0.9, 0.8, 0.4);
                if (t % 2 == 0) {                                   // a ring that closes in on the altar
                    double rr = 6.0 * (1.0 - p);
                    for (int k = 0; k < 14; k++) {
                        double a = k * Math.PI * 2 / 14 + t * 0.1;
                        w.spawnParticle(Particle.CLOUD, base.getX() + Math.cos(a) * rr, base.getY() - 1.0, base.getZ() + Math.sin(a) * rr, 1, 0, 0, 0, 0.01);
                    }
                }
                if (t % 8 == 0) w.playSound(cur, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 2f, (float) (0.5 + 1.5 * p));
                if (t % 25 == 0) w.playSound(cur, Sound.BLOCK_BEACON_AMBIENT, 2f, (float) (0.6 + p));

                if (t >= RISE) {
                    cancel();
                    explode(cur);
                    if (display != null) display.remove();
                    inter.remove();
                    return;
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void explode(Location loc) {
        World w = loc.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3, 1, 1, 1, 0);
        w.spawnParticle(Particle.END_ROD, loc, 400, 0.2, 0.2, 0.2, 0.9);
        w.spawnParticle(Particle.CLOUD, loc, 120, 0.5, 0.5, 0.5, 0.5);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 80, 0.3, 0.3, 0.3, 0.4);
        w.spawnParticle(Particle.ELECTRIC_SPARK, loc, 200, 0.3, 0.3, 0.3, 0.9);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.7f);
        w.playSound(loc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 3f, 0.6f);
        w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 1.1f);
        w.strikeLightningEffect(loc);                               // visual only, never damages

        if (plugin.getConfig().getBoolean("altar.knockback", true)) {
            for (Entity en : w.getNearbyEntities(loc, 8, 8, 8)) {
                if (!(en instanceof LivingEntity) || en instanceof ArmorStand) continue;
                Vector v = en.getLocation().toVector().subtract(loc.toVector());
                v.setY(0);
                if (v.lengthSquared() < 0.01) v = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5, 0, ThreadLocalRandom.current().nextDouble() - 0.5);
                en.setVelocity(v.normalize().multiply(0.9).setY(0.4));
            }
        }
        w.dropItem(loc, items.create(), (Item it) -> {
            it.setVelocity(new Vector(0, 0.35, 0));
            it.setPickupDelay(30);
            it.setGlowing(true);
            it.setInvulnerable(true);
            it.setUnlimitedLifetime(true);
        });
    }
}
