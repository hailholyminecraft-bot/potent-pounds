package net.potendpound;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sneak + left click: the Pound leaves your hand and flies spinning in a straight line until it
 * hits a living target or has travelled the configured range. A hit deals true damage to the target;
 * a miss (range reached or a wall) deals the same true damage to the thrower. The weapon always
 * returns to the thrower's inventory afterwards.
 */
public final class PoundThrow {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Particle.DustOptions GREY = new Particle.DustOptions(Color.fromRGB(170, 170, 180), 1.1f);
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(12, 12, 16), 1.1f);

    private static final class Flight {
        final Player thrower;
        final ItemStack weapon;
        final int slot;
        final ItemDisplay display;
        BukkitTask task;

        Flight(Player thrower, ItemStack weapon, int slot, ItemDisplay display) {
            this.thrower = thrower;
            this.weapon = weapon;
            this.slot = slot;
            this.display = display;
        }
    }

    private final PotendPoundPlugin plugin;
    private final PotendItem items;
    private final Map<UUID, Flight> flights = new HashMap<>();

    public PoundThrow(PotendPoundPlugin plugin, PotendItem items) {
        this.plugin = plugin;
        this.items = items;
    }

    /** @return false if nothing was thrown (so the caller can refund the cooldown). */
    public boolean start(Player p) {
        UUID id = p.getUniqueId();
        if (flights.containsKey(id)) return false;
        PlayerInventory inv = p.getInventory();
        ItemStack held = inv.getItemInMainHand();
        if (!items.isPotend(held)) return false;

        var cfg = plugin.getConfig();
        final double range = Math.max(1.0, cfg.getDouble("throw.range", 20.0));
        final double speed = Math.max(0.2, cfg.getDouble("throw.speed", 1.0));
        final boolean stopAtBlocks = cfg.getBoolean("throw.stop-at-blocks", true);
        final Vector dir = p.getEyeLocation().getDirection().normalize();
        final Location start = p.getEyeLocation().add(dir.clone().multiply(0.8)).subtract(0, 0.25, 0);
        final World w = p.getWorld();

        final ItemStack weapon = held.clone();
        final int slot = inv.getHeldItemSlot();
        inv.setItemInMainHand(null);

        ItemDisplay display = w.spawn(start, ItemDisplay.class, d -> {
            d.setItemStack(weapon);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(spin(0f, dir));
            d.setPersistent(false);
        });
        w.playSound(start, Sound.ITEM_TRIDENT_THROW, 1.5f, 0.7f);

        Flight flight = new Flight(p, weapon, slot, display);
        flights.put(id, flight);
        flight.task = new BukkitRunnable() {
            final Location cur = start.clone();
            double traveled = 0;
            int t = 0;

            @Override
            public void run() {
                if (!display.isValid() || !p.isOnline()) {
                    returnNow(p);
                    return;
                }
                double step = Math.min(speed, range - traveled);
                Location prev = cur.clone();

                RayTraceResult blk = w.rayTraceBlocks(prev, dir, step, FluidCollisionMode.NEVER, true);
                double entityReach = step;
                if (stopAtBlocks && blk != null) entityReach = blk.getHitPosition().distance(prev.toVector());
                RayTraceResult ent = entityReach > 0.01
                        ? w.rayTraceEntities(prev, dir, entityReach, 0.7, e -> valid(p, e)) : null;

                if (ent != null && ent.getHitEntity() instanceof LivingEntity target) {
                    Location at = ent.getHitPosition().toLocation(w);
                    cancel();
                    finish(p, at);
                    hit(p, target, dir, at);
                    return;
                }
                if (stopAtBlocks && blk != null) {
                    Location at = blk.getHitPosition().toLocation(w);
                    cancel();
                    finish(p, at);
                    miss(p, at, "it hit a wall");
                    return;
                }

                cur.add(dir.clone().multiply(step));
                traveled += step;
                display.teleport(cur);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(1);
                display.setTransformation(spin(t * 0.9f, dir));
                w.spawnParticle(Particle.DUST, cur, 2, 0.1, 0.1, 0.1, 0, (t % 2 == 0) ? GREY : BLACK);
                w.spawnParticle(Particle.CRIT, cur, 1, 0.15, 0.15, 0.15, 0.02);
                if (t % 3 == 0) w.playSound(cur, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.4f, 1.6f);

                if (traveled >= range - 1.0E-6) {
                    cancel();
                    finish(p, cur);
                    miss(p, cur, "it reached the end of its range");
                    return;
                }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    // ------------------------------------------------------------------ outcomes

    private void hit(Player p, LivingEntity target, Vector dir, Location at) {
        double dmg = plugin.getConfig().getDouble("throw.damage-hearts", 3.0) * 2.0;
        World w = at.getWorld();
        w.spawnParticle(Particle.SWEEP_ATTACK, at, 3, 0.3, 0.3, 0.3, 0);
        w.spawnParticle(Particle.CRIT, at, 30, 0.4, 0.4, 0.4, 0.5);
        w.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, 1.6f, 1.0f);
        w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.8f);
        target.setVelocity(dir.clone().multiply(0.7).setY(0.25));
        TrueDamage.apply(target, dmg, p);
        p.sendActionBar(MM.deserialize("<gradient:#ffffff:#777780>Direct hit</gradient>"));
    }

    private void miss(Player p, Location at, String why) {
        var cfg = plugin.getConfig();
        at.getWorld().spawnParticle(Particle.CLOUD, at, 15, 0.3, 0.3, 0.3, 0.05);
        if (!cfg.getBoolean("throw.self-damage-on-miss", true)) {
            p.sendActionBar(MM.deserialize("<gray>Your throw missed."));
            return;
        }
        double dmg = cfg.getDouble("throw.damage-hearts", 3.0) * 2.0;
        p.sendActionBar(MM.deserialize("<red>Missed!</red> <gray>The Pound strikes you back (" + why + ")."));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
        TrueDamage.apply(p, dmg, null);
    }

    /** Removes the flying model and hands the weapon back to the thrower. */
    private void finish(Player p, Location at) {
        Flight f = flights.remove(p.getUniqueId());
        if (f == null) return;
        if (f.display.isValid()) f.display.remove();
        giveBack(p, f.weapon, f.slot);
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1f);
    }

    private void giveBack(Player p, ItemStack weapon, int slot) {
        PlayerInventory inv = p.getInventory();
        ItemStack cur = inv.getItem(slot);
        if (cur == null || cur.getType().isAir()) {
            inv.setItem(slot, weapon);
            return;
        }
        for (ItemStack left : inv.addItem(weapon).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }

    // ------------------------------------------------------------------ safety hooks

    /** Cancels a flight immediately and returns the weapon (quit, errors, shutdown). */
    public void returnNow(Player p) {
        Flight f = flights.remove(p.getUniqueId());
        if (f == null) return;
        if (f.task != null) f.task.cancel();
        if (f.display.isValid()) f.display.remove();
        giveBack(p, f.weapon, f.slot);
    }

    public void onDeath(PlayerDeathEvent e) {
        Flight f = flights.remove(e.getEntity().getUniqueId());
        if (f == null) return;
        if (f.task != null) f.task.cancel();
        if (f.display.isValid()) f.display.remove();
        if (e.getKeepInventory()) giveBack(e.getEntity(), f.weapon, f.slot);
        else e.getDrops().add(f.weapon);
    }

    public void shutdown() {
        for (Flight f : new ArrayList<>(flights.values())) returnNow(f.thrower);
    }

    // ------------------------------------------------------------------ helpers

    private boolean valid(Player thrower, Entity e) {
        if (!(e instanceof LivingEntity) || e.equals(thrower) || e instanceof ArmorStand || e.isDead()) return false;
        if (e instanceof Player pl && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR)) return false;
        return !e.equals(thrower.getVehicle());
    }

    /** End-over-end tumble around the axis perpendicular to the flight direction. */
    private Transformation spin(float angle, Vector dir) {
        Vector3f axis = new Vector3f((float) -dir.getZ(), 0f, (float) dir.getX());
        if (axis.lengthSquared() < 1.0E-4f) axis.set(1f, 0f, 0f);
        else axis.normalize();
        return new Transformation(new Vector3f(), new AxisAngle4f(angle, axis.x, axis.y, axis.z),
                new Vector3f(1.6f), new AxisAngle4f());
    }
}
