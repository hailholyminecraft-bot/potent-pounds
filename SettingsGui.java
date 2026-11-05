package net.potendpound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /potentpound gui - click-to-edit settings for every ability, cooldown and stat. */
public final class SettingsGui implements Listener {

    private enum Type { BOOL, INT, DOUBLE }

    private record Setting(int slot, String path, Type type, Material icon, String name, String desc,
                           double def, double min, double max, double step, boolean weaponStat) {}

    private static final class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PotendPoundPlugin plugin;
    private final PotendItem items;
    private final Map<Integer, Setting> bySlot = new HashMap<>();

    public SettingsGui(PotendPoundPlugin plugin, PotendItem items) {
        this.plugin = plugin;
        this.items = items;
        define();
    }

    // ------------------------------------------------------------------ layout

    private void bool(int slot, String path, Material icon, String name, String desc, boolean def, boolean weapon) {
        bySlot.put(slot, new Setting(slot, path, Type.BOOL, icon, name, desc, def ? 1 : 0, 0, 1, 1, weapon));
    }

    private void num(int slot, String path, Type type, Material icon, String name, String desc,
                     double def, double min, double max, double step, boolean weapon) {
        bySlot.put(slot, new Setting(slot, path, type, icon, name, desc, def, min, max, step, weapon));
    }

    private void define() {
        // row 1: switches
        bool(0, "abilities.rend-armor", Material.NETHERITE_CHESTPLATE, "Rend Armor", "Right-click: drop a foe's armor one tier.", true, false);
        bool(1, "abilities.fortress-of-wind", Material.WIND_CHARGE, "Fortress of Wind", "Sneak + right-click: wind barrier box.", true, false);
        bool(2, "abilities.throw", Material.TRIDENT, "Pound Throw", "Sneak + left-click: hurl the weapon.", true, false);
        bool(3, "abilities.crit-combo", Material.MACE, "Crit Combo", "Crits in a row trigger a mace smash.", true, false);
        bool(4, "density.enabled", Material.HEAVY_CORE, "Kill Density", "Every kill adds +1 Density.", true, false);
        bool(5, "density.count-mobs", Material.ZOMBIE_HEAD, "Mob kills count", "Off = only player kills grow Density.", false, false);
        bool(6, "item.disable-shields", Material.SHIELD, "Shield breaker", "Hits disable shields like an axe (new weapons).", true, true);
        bool(7, "nametag.enabled", Material.NAME_TAG, "Grey nametag", "Dark-grey nametag for holders.", true, false);
        bool(8, "locator-bar.enabled", Material.RECOVERY_COMPASS, "Dragon-egg locator bar", "Locator bar only with a Dragon Egg.", true, false);
        // row 2: weapon stats
        num(9, "item.sharpness", Type.INT, Material.DIAMOND_SWORD, "Sharpness level", "0 = none. Applies to new weapons.", 7, 0, 10, 1, true);
        num(10, "density.max", Type.INT, Material.ANVIL, "Max Density (kills)", "Cap for kill-grown Density.", 6, 1, 10, 1, false);
        num(11, "item.attack-damage", Type.DOUBLE, Material.IRON_AXE, "Attack damage bonus", "Base is +1, so 9 = 10 damage.", 9, 0, 40, 0.5, true);
        num(12, "item.attack-speed", Type.DOUBLE, Material.CLOCK, "Attack speed modifier", "-2.4 = sword (1.6/s), -3 = axe (1.0/s).", -2.4, -3.9, 0, 0.1, true);
        num(13, "item.durability", Type.INT, Material.DAMAGED_ANVIL, "Durability", "Max durability of new weapons.", 2031, 50, 10000, 50, true);
        bool(14, "altar.knockback", Material.FIREWORK_ROCKET, "Altar blast push", "Harmless push when the altar explodes.", true, false);
        // row 3: rend + fortress
        num(18, "downgrade.cooldown-seconds", Type.DOUBLE, Material.CLOCK, "Rend cooldown (s)", "Seconds between Rend Armor uses.", 50, 1, 600, 5, false);
        num(19, "downgrade.duration-seconds", Type.INT, Material.IRON_CHESTPLATE, "Rend duration (s)", "How long armor stays downgraded.", 10, 1, 120, 1, false);
        num(20, "downgrade.range", Type.DOUBLE, Material.SPYGLASS, "Rend range", "Blocks.", 20, 3, 60, 1, false);
        num(21, "barrier.cooldown-seconds", Type.DOUBLE, Material.CLOCK, "Fortress cooldown (s)", "Seconds between barrier uses.", 30, 1, 600, 5, false);
        num(22, "barrier.duration-seconds", Type.INT, Material.SHIELD, "Fortress duration (s)", "How long the box stays up.", 6, 1, 60, 1, false);
        num(23, "barrier.half-size", Type.DOUBLE, Material.BARRIER, "Fortress half-size", "Box is twice this wide.", 3.5, 1.5, 10, 0.5, false);
        num(24, "barrier.burst-strength", Type.DOUBLE, Material.WIND_CHARGE, "Fortress burst", "Initial shockwave strength.", 1.8, 0.2, 5, 0.1, false);
        num(25, "barrier.hold-strength", Type.DOUBLE, Material.FEATHER, "Fortress hold", "Push while the box is up.", 0.7, 0.1, 3, 0.1, false);
        // row 4: throw
        num(27, "throw.cooldown-seconds", Type.DOUBLE, Material.CLOCK, "Throw cooldown (s)", "Seconds between throws.", 40, 1, 600, 5, false);
        num(28, "throw.range", Type.DOUBLE, Material.SPYGLASS, "Throw range", "Blocks it flies before it misses.", 20, 5, 60, 1, false);
        num(29, "throw.damage-hearts", Type.DOUBLE, Material.RED_DYE, "Throw damage (hearts)", "True damage on a hit - or to you on a miss.", 3, 0.5, 20, 0.5, false);
        num(30, "throw.speed", Type.DOUBLE, Material.ARROW, "Throw speed", "Blocks per tick.", 1, 0.3, 3, 0.1, false);
        bool(31, "throw.stop-at-blocks", Material.STONE, "Stops at walls", "Off = flies through blocks.", true, false);
        bool(32, "throw.self-damage-on-miss", Material.SKELETON_SKULL, "Hurts you on a miss", "Off = a miss is harmless.", true, false);
        // row 5: crit combo
        num(36, "combo.crits-needed", Type.INT, Material.GOLDEN_SWORD, "Crits needed", "Crits in a row for the smash.", 7, 1, 30, 1, false);
        num(37, "combo.smash-bonus-damage", Type.DOUBLE, Material.TNT, "Smash bonus damage", "Extra damage on the smash hit.", 8, 0, 40, 1, false);
        num(38, "combo.smash-radius", Type.DOUBLE, Material.COBWEB, "Smash radius", "Knockback radius.", 3.5, 1, 8, 0.5, false);
        num(39, "combo.windburst-base", Type.DOUBLE, Material.WIND_CHARGE, "Wind Burst launch", "Base upward launch with Wind Burst.", 1.0, 0.2, 4, 0.1, false);
        num(40, "combo.windburst-per-level", Type.DOUBLE, Material.WIND_CHARGE, "Launch per level", "Extra launch per Wind Burst level.", 0.4, 0, 2, 0.1, false);
        num(41, "combo.reset-seconds", Type.INT, Material.CLOCK, "Streak timeout (s)", "Streak resets after this long.", 8, 1, 60, 1, false);
        bool(42, "combo.same-target", Material.TARGET, "Same target only", "Streak must stay on one target.", true, false);
    }

    // ------------------------------------------------------------------ open / render

    public void open(Player p) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<gradient:#ffffff:#6b6b75>Potent Pound Settings</gradient>"));
        holder.inv = inv;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray> ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        for (Setting s : bySlot.values()) inv.setItem(s.slot(), render(s));

        inv.setItem(45, named(Material.BOOK, "<white>How to use",
                List.of("<gray>Left-click: toggle / increase", "<gray>Right-click: decrease",
                        "<gray>Shift-click: change by 5 steps", "<dark_gray>Saved to config.yml instantly.")));
        inv.setItem(47, named(Material.NETHER_STAR, "<white>Give a fresh Potent Pound",
                List.of("<gray>Uses the current settings.")));
        inv.setItem(48, named(Material.SMITHING_TABLE, "<white>Refresh the held weapon",
                List.of("<gray>Re-applies Sharpness, damage, speed,", "<gray>durability and name to the Pound", "<gray>in your hand (keeps other enchants).")));
        inv.setItem(50, named(Material.WRITABLE_BOOK, "<white>Reload config from disk",
                List.of("<gray>Use after editing config.yml by hand.")));
        inv.setItem(53, named(Material.BARRIER, "<red>Close", List.of()));
        p.openInventory(inv);
    }

    private ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + name));
        List<Component> l = new ArrayList<>();
        for (String line : lore) l.add(MM.deserialize("<!italic>" + line));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    private ItemStack render(Setting s) {
        var cfg = plugin.getConfig();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + s.desc());
        lore.add("");
        if (s.type() == Type.BOOL) {
            boolean v = cfg.getBoolean(s.path(), s.def() == 1.0);
            lore.add("<gray>Status: " + (v ? "<green><b>ON" : "<red><b>OFF"));
            lore.add("<yellow>Click to toggle");
        } else {
            double v = cfg.getDouble(s.path(), s.def());
            lore.add("<gray>Value: <white><b>" + fmt(v));
            lore.add("<yellow>Left +" + fmt(s.step()) + " <dark_gray>| <yellow>Right -" + fmt(s.step()));
            lore.add("<dark_gray>Shift = x5  (range " + fmt(s.min()) + " to " + fmt(s.max()) + ")");
        }
        if (s.weaponStat()) lore.add("<dark_gray>Applies to new weapons or 'Refresh the held weapon'.");
        return named(s.icon(), "<white>" + s.name(), lore);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getSlot();

        switch (slot) {
            case 47 -> {
                for (ItemStack left : p.getInventory().addItem(items.create()).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
                p.sendMessage(MM.deserialize("<gray>Gave you a fresh <white>Potent Pound</white>."));
                return;
            }
            case 48 -> {
                ItemStack held = p.getInventory().getItemInMainHand();
                if (!items.isPotend(held)) {
                    p.sendMessage(MM.deserialize("<red>Hold the Potent Pound first."));
                } else {
                    p.getInventory().setItemInMainHand(items.rebuild(held));
                    p.sendMessage(MM.deserialize("<gray>Your Potent Pound was refreshed."));
                }
                return;
            }
            case 50 -> {
                plugin.reloadConfig();
                plugin.reloadLive();
                open(p);
                p.sendMessage(MM.deserialize("<gray>Config reloaded from disk."));
                return;
            }
            case 53 -> {
                p.closeInventory();
                return;
            }
            default -> { }
        }
        Setting s = bySlot.get(slot);
        if (s == null) return;
        change(s, e.getClick());
        holder.inv.setItem(slot, render(s));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void change(Setting s, ClickType click) {
        var cfg = plugin.getConfig();
        if (s.type() == Type.BOOL) {
            cfg.set(s.path(), !cfg.getBoolean(s.path(), s.def() == 1.0));
        } else {
            double mult = click.isShiftClick() ? 5 : 1;
            double dir = click.isRightClick() ? -1 : 1;
            double next = cfg.getDouble(s.path(), s.def()) + dir * s.step() * mult;
            next = Math.max(s.min(), Math.min(s.max(), next));
            if (s.type() == Type.INT) cfg.set(s.path(), (int) Math.round(next));
            else cfg.set(s.path(), Math.round(next * 100.0) / 100.0);
        }
        plugin.saveConfig();
        if (s.path().startsWith("nametag.")) plugin.reloadLive();
    }
}
