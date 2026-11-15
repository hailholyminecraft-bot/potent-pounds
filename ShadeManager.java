package net.potendpound;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shade Sheath abilities:
 *  - Sneak + right click: Shade Dome (nothing inside can hurt you, nothing inside can leave).
 *  - Hit something, then right click: it flies up, then gets slammed into the ground.
 *    Right click again after a slam (up to the max) to slam again.
 *  - Kills grow a dark aura around you; enough kills give a permanent dark halo. Everything can be reset.
 */
public final class ShadeManager implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Particle.DustOptions VIOLET = new Particle.DustOptions(Color.fromRGB(120, 60, 220), 1.3f);
    private static final Particle.DustOptions VIOLET_LT = new Particle.DustOptions(Color.fromRGB(190, 150, 255), 1.1f);
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(10, 8, 16), 1.5f);

    private record Mark(UUID target, long expires) {}

    private final class Combo {
        final Player owner;
        final UUID target;
        int done;
        boolean busy;
        BukkitTask closer;

        Combo(Player owner, UUID target) {
            this.owner = owner;
            this.target = target;
        }
    }

    private final PotendPoundPlugin plugin;
    private final ShadeItem shade;
    private final NamespacedKey killsKey, epochKey;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<UUID, ShadeDome> domes = new HashMap<>();
    private final Map<UUID, Combo> combos = new HashMap<>();
    private final Map<UUID, Mark> marks = new HashMap<>();
    private final Set<UUID> grabbed = new HashSet<>();
    private BukkitTask auraTask;
    private int tick;

    public ShadeManager(PotendPoundPlugin plugin, ShadeItem shade) {
        this.plugin = plugin;
        this.shade = shade;
        this.killsKey = new NamespacedKey(plugin, "shade_kills");
        this.epochKey = new NamespacedKey(plugin, "shade_epoch");
    }

    public void start() {
        auraTask = Bukkit.getScheduler().runTaskTimer(plugin, this::auraTick, 10L, 2L);
    }

    public void shutdown() {
        if (auraTask != null) auraTask.cancel();
        auraTask = null;
        for (ShadeDome d : new ArrayList<>(domes.values())) d.end();
        for (Combo c : new ArrayList<>(combos.values())) endCombo(c, false);
        grabbed.clear();
    }

    private boolean on(String path) {
        return plugin.getConfig().getBoolean(path, true);
    }

    // ================================================================== kills, aura, halo, reset

    public int kills(Player p) {
        var pdc = p.getPersistentDataContainer();
        int epoch = plugin.getConfig().getInt("shade.epoch", 0);
        Integer pe = pdc.get(epochKey, PersistentDataType.INTEGER);
        if (pe == null || pe != epoch) {
            pdc.set(epochKey, PersistentDataType.INTEGER, epoch);
            pdc.set(killsKey, PersistentDataType.INTEGER, 0);
            return 0;
        }
        return pdc.getOrDefault(killsKey, PersistentDataType.INTEGER, 0);
    }

    private void setKills(Player p, int n) {
        p.getPersistentDataContainer().set(killsKey, PersistentDataType.INTEGER, n);
        p.getPersistentDataContainer().set(epochKey, PersistentDataType.INTEGER, plugin.getConfig().getInt("shade.epoch", 0));
    }

    private void auraTick() {
        tick += 2;
        var cfg = plugin.getConfig();
        boolean aura = cfg.getBoolean("shade.aura-enabled", true), halo = cfg.getBoolean("shade.halo-enabled", true);
        int haloKills = cfg.getInt("shade.halo-kills", 5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            int kills = kills(p);
            if (aura && shade.holding(p)) aura(p, kills);
            if (halo && kills >= haloKills) halo(p);
        }
    }

    private void aura(Player p, int kills) {
        World w = p.getWorld();
        Location base = p.getLocation();
        int n = Math.min(26, 2 + kills * 2);
        double r = 0.8 + Math.min(kills, 10) * 0.04;
        for (int i = 0; i < n; i++) {
            double ang = tick * 0.12 + i * Math.PI * 2 / n;
            double yy = ((tick * 0.03 + i * 0.37) % 1.0) * 1.9;
            double x = base.getX() + Math.cos(ang) * r, z = base.getZ() + Math.sin(ang) * r, y = base.getY() + yy;
            w.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, i % 3 == 0 ? BLACK : (i % 3 == 1 ? VIOLET : VIOLET_LT));
            if (kills >= 3 && i % 4 == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, x, y, z, 1, 0, 0, 0, 0.005);
            if (kills >= 6 && i % 6 == 0) w.spawnParticle(Particle.SCULK_SOUL, x, y, z, 1, 0, 0, 0, 0.01);
            if (kills >= 10 && i % 8 == 0) w.spawnParticle(Particle.SQUID_INK, x, y, z, 1, 0, 0, 0, 0.01);
        }
    }

    private void halo(Player p) {
        World w = p.getWorld();
        Location c = p.getLocation().add(0, p.getHeight() + 0.35, 0);
        for (int k = 0; k < 14; k++) {
            double a = k * Math.PI * 2 / 14 + tick * 0.09;
            w.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * 0.42, c.getY(), c.getZ() + Math.sin(a) * 0.42, 1, 0, 0, 0, 0,
                    k % 3 == 0 ? VIOLET : BLACK);
        }
    }

    private void addKill(Player p) {
        int n = kills(p) + 1;
        setKills(p, n);
        World w = p.getWorld();
        Location l = p.getLocation().add(0, 1, 0);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 30 + n * 3, 0.5, 0.8, 0.5, 0.05);
        w.spawnParticle(Particle.SQUID_INK, l, 25, 0.5, 0.8, 0.5, 0.08);
        w.spawnParticle(Particle.DUST, l, 40, 0.6, 0.9, 0.6, 0, VIOLET);
        w.playSound(l, Sound.ENTITY_WITHER_AMBIENT, 0.8f, 1.7f);
        int need = plugin.getConfig().getInt("shade.halo-kills", 5);
        p.sendActionBar(MM.deserialize("<gradient:#9a6aff:#d2b4ff>Shade kills</gradient> <dark_gray>» <white>" + n));
        if (n == need && plugin.getConfig().getBoolean("shade.halo-enabled", true)) {
            p.sendMessage(MM.deserialize("<gradient:#7a4ad8:#d2b4ff><b>A dark halo crowns you.</b></gradient>"));
            w.playSound(l, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.6f);
            w.spawnParticle(Particle.END_ROD, p.getLocation().add(0, p.getHeight() + 0.3, 0), 40, 0.4, 0.2, 0.4, 0.1);
        }
    }

    /** Resets kills, aura, halo, cooldowns, the active dome and any grab combo for one player. */
    public void reset(Player p) {
        UUID id = p.getUniqueId();
        ShadeDome d = domes.get(id);
        if (d != null) d.end();
        Combo c = combos.get(id);
        if (c != null) endCombo(c, false);
        marks.remove(id);
        cooldowns.remove(id + ":dome");
        cooldowns.remove(id + ":slam");
        setKills(p, 0);
        p.sendMessage(MM.deserialize("<gray>Your Shade aura, halo and cooldowns were reset."));
    }

    public void resetAll() {
        var cfg = plugin.getConfig();
        cfg.set("shade.epoch", cfg.getInt("shade.epoch", 0) + 1);       // offline players reset when they next join/tick
        plugin.saveConfig();
        for (Player p : Bukkit.getOnlinePlayers()) reset(p);
    }

    // ================================================================== triggers

    private boolean ready(Player p, String key, String label, double seconds) {
        long now = System.currentTimeMillis(), total = (long) (seconds * 1000);
        Long until = cooldowns.get(key);
        if (until != null && until > now) {
            if (until - now < total - 250) {
                p.sendActionBar(MM.deserialize("<gray>" + label + " <dark_gray>» <white>" + String.format("%.1f", (until - now) / 1000.0) + "s"));
            }
            return false;
        }
        cooldowns.put(key, now + total);
        return true;
    }

    private boolean usable(Player p) {
        return p.hasPermission("potendpound.use") && shade.holding(p);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!usable(p)) return;
        if (p.isSneaking() ? tryDome(p) : trySlam(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!usable(p)) return;
        if (p.isSneaking() ? tryDome(p) : trySlam(p)) e.setCancelled(true);
    }

    private boolean tryDome(Player p) {
        if (!on("abilities.shade-dome")) return false;
        UUID id = p.getUniqueId();
        if (domes.containsKey(id)) {
            p.sendActionBar(MM.deserialize("<gray>Your dome is already up."));
            return true;
        }
        if (!ready(p, id + ":dome", "Shade Dome", plugin.getConfig().getDouble("dome.cooldown-seconds", 30.0))) return true;
        ShadeDome d = new ShadeDome(plugin, this, p, p.getLocation().add(0, 1, 0));
        domes.put(id, d);
        d.start();
        p.sendActionBar(MM.deserialize("<gradient:#9a6aff:#d2b4ff>Shade Dome</gradient>"));
        return true;
    }

    public void domeEnded(UUID owner) {
        domes.remove(owner);
    }

    // ================================================================== combat hooks

    private Entity root(Entity damager) {
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) return shooter;
        return damager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        // anything inside the dome can't hurt its owner
        if (e.getEntity() instanceof Player victim) {
            ShadeDome d = domes.get(victim.getUniqueId());
            if (d != null) {
                Entity src = root(e.getDamager());
                if (src != null && !src.equals(victim) && d.contains(src.getLocation())) {
                    e.setCancelled(true);
                    victim.getWorld().spawnParticle(Particle.SQUID_INK, src.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.03);
                    return;
                }
            }
        }
        // hitting something with the blade marks it for the grab
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getDamager() instanceof Player p
                && e.getEntity() instanceof LivingEntity v && shade.holding(p)) {
            long secs = plugin.getConfig().getLong("slam.mark-seconds", 8);
            marks.put(p.getUniqueId(), new Mark(v.getUniqueId(), System.currentTimeMillis() + secs * 1000L));
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        if (!(victim instanceof Player) && !plugin.getConfig().getBoolean("shade.count-mobs", false)) return;
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || !shade.holding(killer)) return;
        addKill(killer);
    }

    @EventHandler
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && grabbed.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        for (ShadeDome d : domes.values()) {
            if (d.confines(e.getPlayer()) && (e.getTo() == null || !d.contains(e.getTo()))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        ShadeDome d = domes.get(id);
        if (d != null) d.end();
        Combo c = combos.get(id);
        if (c != null) endCombo(c, false);
        marks.remove(id);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        ShadeDome d = domes.get(id);
        if (d != null) d.end();
        Combo c = combos.get(id);
        if (c != null) endCombo(c, false);
    }

    // ================================================================== grab + slam

    private boolean validTarget(Player owner, Entity en) {
        if (!(en instanceof LivingEntity le) || en instanceof ArmorStand || en.equals(owner) || !en.isValid() || le.isDead()) return false;
        if (!en.getWorld().equals(owner.getWorld())) return false;
        return !(en instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR));
    }

    private LivingEntity markedTarget(Player p, double range) {
        Mark m = marks.get(p.getUniqueId());
        if (m == null || m.expires() < System.currentTimeMillis()) return null;
        Entity en = Bukkit.getEntity(m.target());
        if (!validTarget(p, en)) return null;
        return en.getLocation().distance(p.getLocation()) <= range ? (LivingEntity) en : null;
    }

    private boolean trySlam(Player p) {
        var cfg = plugin.getConfig();
        if (!on("abilities.shade-slam")) return false;
        UUID id = p.getUniqueId();
        int max = cfg.getInt("slam.max-count", 3);
        double range = cfg.getDouble("slam.range", 14.0);
        Combo c = combos.get(id);
        LivingEntity target;
        if (c == null) {
            target = markedTarget(p, range);
            if (target == null) return false;                      // nothing to grab: let the click do its normal thing
            long now = System.currentTimeMillis();
            Long until = cooldowns.get(id + ":slam");
            if (until != null && until > now) {
                p.sendActionBar(MM.deserialize("<gray>Soul Slam <dark_gray>» <white>" + String.format("%.1f", (until - now) / 1000.0) + "s"));
                return true;
            }
            c = new Combo(p, target.getUniqueId());
            combos.put(id, c);
        } else {
            if (c.busy || c.done >= max) return true;
            Entity en = Bukkit.getEntity(c.target);
            if (!validTarget(p, en)) {
                endCombo(c, true);
                return false;
            }
            target = (LivingEntity) en;
        }
        if (target.getLocation().distance(p.getLocation()) > range) {
            p.sendActionBar(MM.deserialize("<gray>Too far away."));
            return true;
        }
        if (c.closer != null) {
            c.closer.cancel();
            c.closer = null;
        }
        c.busy = true;
        runSlam(c, target);
        return true;
    }

    /** The target is launched straight up, hangs for a moment, then is slammed back into the ground. */
    private void runSlam(Combo c, LivingEntity v) {
        var cfg = plugin.getConfig();
        Player p = c.owner;
        World w = v.getWorld();
        double liftHeight = cfg.getDouble("slam.lift-height", 8.0);
        int hang = Math.max(0, cfg.getInt("slam.hang-ticks", 6));
        double startY = v.getLocation().getY();
        grabbed.add(v.getUniqueId());
        w.playSound(v.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);
        w.playSound(v.getLocation(), Sound.ENTITY_BREEZE_JUMP, 2f, 0.7f);
        w.spawnParticle(Particle.SQUID_INK, v.getLocation().add(0, 0.5, 0), 50, 0.5, 0.3, 0.5, 0.12);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, v.getLocation().add(0, 0.5, 0), 40, 0.4, 0.2, 0.4, 0.1);
        new BukkitRunnable() {
            int phase = 0, t = 0, hangT = 0, fall = 0;     // 0 = fly up, 1 = hang, 2 = slam down

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !v.isValid() || v.isDead()) {
                    cancel();
                    finishSlam(c, v);
                    return;
                }
                Location vl = v.getLocation();
                if (phase == 0) {
                    v.setVelocity(new Vector(0, 0.95, 0));
                    v.setFallDistance(0f);
                    w.spawnParticle(Particle.SQUID_INK, vl.clone().add(0, 0.3, 0), 4, 0.3, 0.2, 0.3, 0.03);
                    w.spawnParticle(Particle.DUST, vl.clone().add(0, 0.5, 0), 5, 0.3, 0.4, 0.3, 0, VIOLET);
                    boolean ceiling = vl.clone().add(0, v.getHeight() + 0.4, 0).getBlock().getType().isSolid();
                    if (vl.getY() - startY >= liftHeight || ++t > 30 || ceiling) phase = 1;
                } else if (phase == 1) {
                    v.setVelocity(new Vector(0, 0, 0));          // hang in the air for a beat
                    v.setFallDistance(0f);
                    for (int k = 0; k < 10; k++) {
                        double a = k * Math.PI * 2 / 10 + hangT * 0.5;
                        w.spawnParticle(Particle.DUST, vl.getX() + Math.cos(a) * 1.1, vl.getY() + 1, vl.getZ() + Math.sin(a) * 1.1, 1, 0, 0, 0, 0, k % 2 == 0 ? BLACK : VIOLET_LT);
                    }
                    if (hangT == 0) w.playSound(vl, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 1.2f);
                    if (++hangT >= hang) {
                        phase = 2;
                        w.playSound(vl, Sound.ENTITY_BREEZE_JUMP, 2f, 0.5f);
                    }
                } else {
                    v.setVelocity(new Vector(0, -2.6, 0));
                    w.spawnParticle(Particle.SQUID_INK, vl.clone().add(0, 1, 0), 6, 0.3, 0.8, 0.3, 0.05);
                    w.spawnParticle(Particle.DUST, vl.clone().add(0, 1.5, 0), 6, 0.3, 0.8, 0.3, 0, VIOLET_LT);
                    boolean grounded = v.isOnGround() || vl.clone().subtract(0, 0.4, 0).getBlock().getType().isSolid();
                    if (grounded || ++fall > 40) {
                        cancel();
                        impact(c, v);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void impact(Combo c, LivingEntity v) {
        var cfg = plugin.getConfig();
        Player p = c.owner;
        Location l = v.getLocation();
        World w = l.getWorld();
        double dmg = cfg.getDouble("slam.damage", 16.0) + cfg.getDouble("slam.damage-per-extra", 6.0) * c.done;
        c.done++;
        w.spawnParticle(Particle.EXPLOSION_EMITTER, l, 2, 0.5, 0.2, 0.5, 0);
        w.spawnParticle(Particle.SQUID_INK, l.clone().add(0, 0.5, 0), 140, 1.2, 0.6, 1.2, 0.2);
        w.spawnParticle(Particle.SCULK_SOUL, l.clone().add(0, 0.5, 0), 100, 1.0, 1.2, 1.0, 0.1);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 0.5, 0), 130, 1.0, 1.4, 1.0, 0.15);
        w.spawnParticle(Particle.DRAGON_BREATH, l.clone().add(0, 0.3, 0), 100, 1.4, 0.3, 1.4, 0.05);
        w.spawnParticle(Particle.REVERSE_PORTAL, l.clone().add(0, 1, 0), 120, 0.6, 2.0, 0.6, 0.4);
        w.spawnParticle(Particle.CRIT, l.clone().add(0, 0.5, 0), 80, 1.0, 0.6, 1.0, 0.6);
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.6f);
        w.playSound(l, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 4f, 0.5f);
        w.playSound(l, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.5f, 0.8f);
        new BukkitRunnable() {                                      // shockwave ring
            int r = 1;

            @Override
            public void run() {
                for (int k = 0; k < 40; k++) {
                    double a = k * Math.PI * 2 / 40;
                    w.spawnParticle(Particle.DUST, l.getX() + Math.cos(a) * r, l.getY() + 0.2, l.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, r % 2 == 0 ? BLACK : VIOLET);
                    if (r % 2 == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.getX() + Math.cos(a) * r, l.getY() + 0.3, l.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0.02);
                }
                if (++r > 9) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);

        grabbed.remove(v.getUniqueId());
        if (cfg.getBoolean("slam.true-damage", true)) TrueDamage.apply(v, dmg, p);
        else v.damage(dmg, p);
        for (Entity en : w.getNearbyEntities(l, 5, 3, 5)) {           // harmless knockback for bystanders
            if (!(en instanceof LivingEntity) || en.equals(v) || en.equals(p) || en instanceof ArmorStand) continue;
            Vector away = en.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) continue;
            en.setVelocity(away.normalize().multiply(0.9).setY(0.35));
        }
        p.sendActionBar(MM.deserialize("<gradient:#9a6aff:#d2b4ff>Soul Slam</gradient> <dark_gray>» <white>" + c.done + "<dark_gray>/</dark_gray>" + cfg.getInt("slam.max-count", 3)));
        finishSlam(c, v);
    }

    private void finishSlam(Combo c, LivingEntity v) {
        grabbed.remove(v.getUniqueId());
        c.busy = false;
        var cfg = plugin.getConfig();
        boolean over = !v.isValid() || v.isDead() || !c.owner.isOnline() || c.done >= cfg.getInt("slam.max-count", 3);
        if (over) {
            endCombo(c, true);
            return;
        }
        long window = (long) (cfg.getDouble("slam.window-seconds", 5.0) * 20);
        c.closer = Bukkit.getScheduler().runTaskLater(plugin, () -> endCombo(c, true), window);
        c.owner.sendActionBar(MM.deserialize("<gray>Right-click to slam again <dark_gray>(" + c.done + "/" + cfg.getInt("slam.max-count", 3) + ")"));
    }

    private void endCombo(Combo c, boolean startCooldown) {
        if (c.closer != null) c.closer.cancel();
        c.closer = null;
        UUID id = c.owner.getUniqueId();
        if (combos.get(id) == c) combos.remove(id);
        grabbed.remove(c.target);
        if (startCooldown) {
            double cd = plugin.getConfig().getDouble("slam.cooldown-seconds", 50.0);
            cooldowns.put(id + ":slam", System.currentTimeMillis() + (long) (cd * 1000));
        }
    }
}
