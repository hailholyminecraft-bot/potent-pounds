package net.potendpound;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drops worn armor one tier and restores it later. Every downgraded piece carries its
 * original material + an expiry in its PDC, so it can always be restored (timer, quit,
 * death, drop, inventory close, plugin disable) and never gets stuck weakened.
 * Trims, enchants, names etc. are copied over because the whole ItemMeta is carried across.
 */
public final class ArmorDowngrade {

    private static final String[] SLOTS = {"_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};

    private final PotendPoundPlugin plugin;
    private final NamespacedKey originalKey;
    private final NamespacedKey expiresKey;
    private final Map<UUID, BukkitTask> active = new HashMap<>();

    public ArmorDowngrade(PotendPoundPlugin plugin) {
        this.plugin = plugin;
        this.originalKey = new NamespacedKey(plugin, "original_material");
        this.expiresKey = new NamespacedKey(plugin, "expires_at");
    }

    /** @return true if at least one worn piece was downgraded or had its timer refreshed. */
    public boolean apply(Player target) {
        List<String> tiers = plugin.getConfig().getStringList("downgrade.tiers");
        long ticks = plugin.getConfig().getLong("downgrade.duration-seconds", 10) * 20L;
        long expires = System.currentTimeMillis() + ticks * 50L;

        PlayerInventory inv = target.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || piece.getType().isAir()) continue;
            if (isDowngraded(piece)) {               // already weakened: just extend the timer
                armor[i] = withExpiry(piece, expires);
                changed = true;
                continue;
            }
            Material next = nextTier(piece.getType(), tiers);
            if (next == null) continue;
            armor[i] = downgrade(piece, next, expires);
            changed = true;
        }
        if (!changed) return false;
        inv.setArmorContents(armor);
        schedule(target, ticks);
        return true;
    }

    private void schedule(Player target, long ticks) {
        UUID id = target.getUniqueId();
        BukkitTask old = active.remove(id);
        if (old != null) old.cancel();
        active.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            active.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                sweep(p, true);
                AbilityListener.reforgeFx(p);
            }
        }, ticks));
    }

    private Material nextTier(Material m, List<String> tiers) {
        String name = m.name();
        for (String suffix : SLOTS) {
            if (!name.endsWith(suffix)) continue;
            String tier = name.substring(0, name.length() - suffix.length());
            int idx = tiers.indexOf(tier);
            if (idx < 0 || idx + 1 >= tiers.size()) return null;
            return Material.matchMaterial(tiers.get(idx + 1) + suffix);
        }
        return null;
    }

    // ---- conversion helpers -------------------------------------------------

    private ItemStack convert(ItemStack src, Material to) {
        ItemStack out = new ItemStack(to, src.getAmount());
        ItemMeta meta = src.getItemMeta();
        if (meta != null) out.setItemMeta(meta);   // carries enchants, trim, name, lore, PDC...
        if (src.getItemMeta() instanceof Damageable from && out.getItemMeta() instanceof Damageable dest) {
            int oldMax = src.getType().getMaxDurability();
            int newMax = to.getMaxDurability();
            if (oldMax > 0 && newMax > 0 && from.hasDamage()) {
                int d = (int) Math.round(from.getDamage() * (double) newMax / oldMax);
                dest.setDamage(Math.min(d, newMax - 1));
                out.setItemMeta(dest);
            }
        }
        return out;
    }

    private ItemStack downgrade(ItemStack src, Material to, long expires) {
        ItemStack out = convert(src, to);
        ItemMeta meta = out.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(originalKey, PersistentDataType.STRING, src.getType().name());
        pdc.set(expiresKey, PersistentDataType.LONG, expires);
        out.setItemMeta(meta);
        return out;
    }

    private ItemStack withExpiry(ItemStack piece, long expires) {
        ItemStack out = piece.clone();
        ItemMeta meta = out.getItemMeta();
        meta.getPersistentDataContainer().set(expiresKey, PersistentDataType.LONG, expires);
        out.setItemMeta(meta);
        return out;
    }

    public boolean isDowngraded(ItemStack it) {
        return it != null && !it.getType().isAir() && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(originalKey, PersistentDataType.STRING);
    }

    private boolean expired(ItemStack it, long now) {
        Long exp = it.getItemMeta().getPersistentDataContainer().get(expiresKey, PersistentDataType.LONG);
        return exp == null || exp <= now;
    }

    /** Turns a downgraded piece back into its original tier (keeps current enchants/damage ratio). */
    public ItemStack restore(ItemStack piece) {
        ItemStack work = piece.clone();
        ItemMeta meta = work.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String orig = pdc.get(originalKey, PersistentDataType.STRING);
        Material to = orig == null ? null : Material.matchMaterial(orig);
        pdc.remove(originalKey);
        pdc.remove(expiresKey);
        work.setItemMeta(meta);
        return to == null ? work : convert(work, to);
    }

    /** Restores downgraded pieces anywhere in the player's inventory (and cursor). */
    public void sweep(Player p, boolean force) {
        PlayerInventory inv = p.getInventory();
        long now = System.currentTimeMillis();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isDowngraded(it) && (force || expired(it, now))) {
                inv.setItem(i, restore(it));
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (isDowngraded(cursor) && (force || expired(cursor, now))) {
            p.setItemOnCursor(restore(cursor));
        }
    }

    public void cancel(UUID id) {
        BukkitTask t = active.remove(id);
        if (t != null) t.cancel();
    }

    public void revertAll() {
        active.values().forEach(BukkitTask::cancel);
        active.clear();
        for (Player p : Bukkit.getOnlinePlayers()) sweep(p, true);
    }
}
