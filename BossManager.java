package net.potendpound;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Spawns the Trial Protector when the weapon is taken from the altar and handles its projectiles and death. */
public final class BossManager implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PotendPoundPlugin plugin;
    private final PotendItem items;
    private final AdvancementManager advancements;
    private final NamespacedKey chargeKey;
    private final NamespacedKey dropKey;
    private final Map<UUID, TrialProtector> bosses = new HashMap<>();

    public BossManager(PotendPoundPlugin plugin, PotendItem items, AdvancementManager advancements) {
        this.plugin = plugin;
        this.items = items;
        this.advancements = advancements;
        this.chargeKey = new NamespacedKey(plugin, "boss_charge");
        this.dropKey = new NamespacedKey(plugin, "altar_drop");
    }

    public NamespacedKey chargeKey() {
        return chargeKey;
    }

    public NamespacedKey dropKey() {
        return dropKey;
    }

    public void unregister(UUID id) {
        bosses.remove(id);
    }

    public TrialProtector spawn(Location loc) {
        TrialProtector boss = new TrialProtector(plugin, this, loc);
        bosses.put(boss.body.getUniqueId(), boss);
        return boss;
    }

    /** Removes leftover bosses/parts (after a crash or reload). */
    public void cleanupOrphans() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getScoreboardTags().contains(TrialProtector.TAG) || e.getScoreboardTags().contains(TrialProtector.TAG_PART)) e.remove();
            }
        }
    }

    public void shutdown() {
        for (TrialProtector b : new java.util.ArrayList<>(bosses.values())) b.remove();
        bosses.clear();
        cleanupOrphans();
    }

    // ------------------------------------------------------------------ taking the weapon wakes the boss

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Item it = e.getItem();
        String stored = it.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        if (stored == null || !items.isPotend(it.getItemStack())) return;
        advancements.grant(p);
        if (!plugin.getConfig().getBoolean("boss.enabled", true)) return;
        String[] s = stored.split(";");
        Location spawn = new Location(it.getWorld(), Double.parseDouble(s[0]), Double.parseDouble(s[1]) + 2.0, Double.parseDouble(s[2]));
        for (Player near : spawn.getWorld().getPlayers()) {
            if (near.getLocation().distanceSquared(spawn) < 80 * 80) {
                near.sendMessage(MM.deserialize("<gradient:#ffffff:#6b6b75><b>The Trial Protector awakens...</b></gradient>"));
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(spawn), 15L);
    }

    // ------------------------------------------------------------------ wind charges: damage + armor wear

    @EventHandler
    public void onChargeHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!pr.getPersistentDataContainer().has(chargeKey, PersistentDataType.BYTE)) return;
        Location at = pr.getLocation();
        World w = at.getWorld();
        double dmg = plugin.getConfig().getDouble("boss.charge-damage", 6.0);
        int armorDmg = plugin.getConfig().getInt("boss.armor-damage", 25);
        Entity source = pr.getShooter() instanceof Entity en ? en : null;
        for (Player p : w.getNearbyPlayers(at, 2.4)) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (source != null) p.damage(dmg, source); else p.damage(dmg);
            damageArmor(p, armorDmg);
        }
        w.spawnParticle(Particle.CLOUD, at, 25, 0.4, 0.4, 0.4, 0.12);
        w.spawnParticle(Particle.CRIT, at, 15, 0.4, 0.4, 0.4, 0.3);
        w.playSound(at, Sound.ENTITY_BREEZE_WIND_BURST, 2f, 0.8f);
        pr.remove();
    }

    private void damageArmor(Player p, int amount) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean broke = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack it = armor[i];
            if (it == null || it.getType().isAir() || it.getType().getMaxDurability() <= 0) continue;
            if (!(it.getItemMeta() instanceof Damageable d)) continue;
            int nd = d.getDamage() + amount;
            if (nd >= it.getType().getMaxDurability()) {
                armor[i] = null;
                broke = true;
            } else {
                d.setDamage(nd);
                it.setItemMeta(d);
            }
        }
        inv.setArmorContents(armor);
        p.playSound(p.getLocation(), broke ? Sound.ENTITY_ITEM_BREAK : Sound.ITEM_SHIELD_BLOCK, 1f, 0.7f);
    }

    // ------------------------------------------------------------------ boss lifecycle

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        TrialProtector b = bosses.get(e.getEntity().getUniqueId());
        if (b != null) b.onDeath(e);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!bosses.containsKey(e.getEntity().getUniqueId())) return;
        switch (e.getCause()) {
            case SUFFOCATION, FALL, DROWNING, CRAMMING, FIRE, FIRE_TICK, LAVA, HOT_FLOOR, WORLD_BORDER, FLY_INTO_WALL -> e.setCancelled(true);
            default -> { }
        }
    }
}
