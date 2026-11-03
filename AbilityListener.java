package net.potendpound;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AbilityListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Particle.DustOptions GREY = new Particle.DustOptions(Color.fromRGB(170, 170, 180), 1.2f);
    private static final Particle.DustOptions BLACK = new Particle.DustOptions(Color.fromRGB(12, 12, 16), 1.2f);

    private final PotendPoundPlugin plugin;
    private final PotendItem items;
    private final ArmorDowngrade armor;
    private final BarrierField barrier;
    private final NametagManager nametags;
    private final Map<String, Long> cooldowns = new HashMap<>();

    public AbilityListener(PotendPoundPlugin plugin, PotendItem items, ArmorDowngrade armor,
                           BarrierField barrier, NametagManager nametags) {
        this.plugin = plugin;
        this.items = items;
        this.armor = armor;
        this.barrier = barrier;
        this.nametags = nametags;
    }

    // ------------------------------------------------------------------ abilities

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!usable(p)) return;

        if (p.isSneaking()) {
            e.setCancelled(true);
            tryBarrier(p);
            return;
        }
        Player target = findTarget(p);
        if (target != null) {
            e.setCancelled(true);
            tryRend(p, target);
        }
    }

    /** Close-range clicks arrive as entity interactions instead. */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!usable(p)) return;
        if (p.isSneaking()) {
            e.setCancelled(true);
            tryBarrier(p);
        } else if (e.getRightClicked() instanceof Player target && target.getGameMode() != GameMode.SPECTATOR) {
            e.setCancelled(true);
            tryRend(p, target);
        }
    }

    private boolean usable(Player p) {
        return p.hasPermission("potendpound.use") && items.isPotend(p.getInventory().getItemInMainHand());
    }

    private Player findTarget(Player p) {
        double range = plugin.getConfig().getDouble("downgrade.range", 20.0);
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.4,
                ent -> ent instanceof Player pl && !pl.equals(p) && pl.getGameMode() != GameMode.SPECTATOR);
        return r != null && r.getHitEntity() instanceof Player t ? t : null;
    }

    private void tryRend(Player user, Player target) {
        double cd = plugin.getConfig().getDouble("downgrade.cooldown-seconds", 12.0);
        String key = user.getUniqueId() + ":rend";
        if (!ready(user, key, "Rend Armor", cd)) return;
        if (!armor.apply(target)) {
            cooldowns.remove(key);   // nothing to rend: don't burn the cooldown
            user.sendActionBar(MM.deserialize("<gray>" + target.getName() + " <dark_gray>has no armor to rend."));
            return;
        }
        int secs = plugin.getConfig().getInt("downgrade.duration-seconds", 10);
        user.sendActionBar(MM.deserialize("<gradient:#ffffff:#777780>Armor rended</gradient> <dark_gray>» <gray>" + target.getName()));
        target.sendActionBar(MM.deserialize("<red>Your armor was rended!</red> <gray>(" + secs + "s)"));
        rendFx(user, target);
    }

    private void tryBarrier(Player user) {
        double cd = plugin.getConfig().getDouble("barrier.cooldown-seconds", 25.0);
        if (!ready(user, user.getUniqueId() + ":barrier", "Fortress of Wind", cd)) return;
        user.sendActionBar(MM.deserialize("<gradient:#ffffff:#777780>Fortress of Wind</gradient>"));
        barrier.activate(user);
    }

    /** Sets the cooldown if ready; otherwise shows the remaining time (ignoring double-fired events). */
    private boolean ready(Player p, String key, String label, double seconds) {
        long now = System.currentTimeMillis();
        long total = (long) (seconds * 1000);
        Long until = cooldowns.get(key);
        if (until != null && until > now) {
            if (until - now < total - 250) {
                p.sendActionBar(MM.deserialize("<gray>" + label + " <dark_gray>» <white>"
                        + String.format("%.1f", (until - now) / 1000.0) + "s"));
            }
            return false;
        }
        cooldowns.put(key, now + total);
        return true;
    }

    // ------------------------------------------------------------------ effects

    private void rendFx(Player user, Player target) {
        Location from = user.getEyeLocation().add(user.getEyeLocation().getDirection().multiply(0.8)).subtract(0, 0.3, 0);
        Location to = target.getLocation().add(0, 1.0, 0);
        Vector step = to.toVector().subtract(from.toVector());
        double len = step.length();
        step.normalize().multiply(0.5);
        Location cur = from.clone();
        for (double d = 0; d < len; d += 0.5) {
            user.getWorld().spawnParticle(Particle.DUST, cur, 1, 0.02, 0.02, 0.02, 0, ((int) (d * 2) % 2 == 0) ? GREY : BLACK);
            cur.add(step);
        }
        target.getWorld().spawnParticle(Particle.CRIT, to, 30, 0.4, 0.6, 0.4, 0.3);
        target.getWorld().spawnParticle(Particle.SMOKE, to, 20, 0.4, 0.6, 0.4, 0.02);
        target.getWorld().playSound(to, Sound.ITEM_SHIELD_BREAK, 1f, 0.7f);
        target.getWorld().playSound(to, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.6f);
        user.playSound(user.getLocation(), Sound.ITEM_MACE_SMASH_GROUND, 0.8f, 1.2f);
    }

    /** Played when armor is reforged (called by ArmorDowngrade). */
    public static void reforgeFx(Player p) {
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.ENCHANTED_HIT, l, 25, 0.4, 0.7, 0.4, 0.2);
        p.getWorld().playSound(l, Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1.1f);
        p.sendActionBar(MM.deserialize("<gray>Your armor has been <white>reforged</white>."));
    }

    // ------------------------------------------------------------------ anvil
    // Vanilla refuses Density on anything with Sharpness. The Pound is Sharpness VII by
    // default, so this lets a Density book through (Wind Burst needs no help).

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        ItemStack first = e.getInventory().getFirstItem();
        ItemStack second = e.getInventory().getSecondItem();
        if (!items.isPotend(first) || second == null || second.getType() != Material.ENCHANTED_BOOK) return;
        if (!(second.getItemMeta() instanceof EnchantmentStorageMeta book)) return;

        int bookLevel = book.getStoredEnchantLevel(Enchantment.DENSITY);
        if (bookLevel <= 0) return;

        ItemStack result = e.getResult() != null ? e.getResult().clone() : first.clone();
        int cur = result.getEnchantmentLevel(Enchantment.DENSITY);
        int max = Enchantment.DENSITY.getMaxLevel();
        int next = cur == bookLevel ? Math.min(bookLevel + 1, max) : Math.max(cur, bookLevel);
        if (next == cur) return;

        result.addUnsafeEnchantment(Enchantment.DENSITY, next);
        e.setResult(result);
        e.getView().setRepairCost(Math.max(1, e.getView().getRepairCost()) + next * 2);
    }

    // ------------------------------------------------------------------ armor safety net

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        armor.sweep(p, true);
        String url = plugin.getConfig().getString("resource-pack.url", "");
        if (url != null && !url.isBlank()) {
            String sha = plugin.getConfig().getString("resource-pack.sha1", "");
            byte[] hash = (sha == null || sha.isBlank()) ? null : HexFormat.of().parseHex(sha.trim());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.setResourcePack(url, hash);
            }, 20L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        armor.cancel(p.getUniqueId());
        armor.sweep(p, true);
        barrier.stop(p.getUniqueId());
        nametags.leave(p);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        List<ItemStack> drops = e.getDrops();
        for (int i = 0; i < drops.size(); i++) {
            ItemStack d = drops.get(i);
            if (armor.isDowngraded(d)) drops.set(i, armor.restore(d));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> armor.sweep(p, true), 1L);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Item entity = e.getItemDrop();
        ItemStack s = entity.getItemStack();
        if (armor.isDowngraded(s)) entity.setItemStack(armor.restore(s));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) armor.sweep(p, false);
    }
}
