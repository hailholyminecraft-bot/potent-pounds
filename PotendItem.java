package net.potendpound;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Weapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The Potend Pound is a real MACE underneath, so the vanilla smash attack, Density and
 * Wind Burst all work natively. It gets axe-like stats, Sharpness VII, a custom 3D item
 * model and a PDC tag that identifies it.
 */
public final class PotendItem {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PotendPoundPlugin plugin;
    private final NamespacedKey idKey;

    public PotendItem(PotendPoundPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "potend_pound");
    }

    public ItemStack create() {
        FileConfiguration cfg = plugin.getConfig();
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();

        meta.itemName(MM.deserialize(cfg.getString("item.name", "Potent Pound")));

        String range = String.valueOf((int) cfg.getDouble("downgrade.range", 20));
        String dur = String.valueOf(cfg.getInt("downgrade.duration-seconds", 10));
        String dens = String.valueOf(cfg.getInt("density.max", 6));
        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("item.lore")) {
            lore.add(MM.deserialize("<!italic>" + line.replace("{range}", range).replace("{duration}", dur).replace("{density}", dens)
                    .replace("{throw_range}", String.valueOf((int) cfg.getDouble("throw.range", 20)))
                    .replace("{throw_hearts}", String.valueOf(cfg.getDouble("throw.damage-hearts", 3.0)).replace(".0", ""))
                    .replace("{crits}", String.valueOf(cfg.getInt("combo.crits-needed", 7)))));
        }
        meta.lore(lore);

        // Sharpness 7 by default. Density / Wind Burst are added by the player (anvil).
        int sharp = cfg.getInt("item.sharpness", 7);
        if (sharp > 0) meta.addEnchant(Enchantment.SHARPNESS, sharp, true);

        // Axe-like stats (same ids vanilla uses, so the tooltip shows them as base stats).
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                NamespacedKey.minecraft("base_attack_damage"),
                cfg.getDouble("item.attack-damage", 9.0),
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                NamespacedKey.minecraft("base_attack_speed"),
                cfg.getDouble("item.attack-speed", -2.4),
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));

        if (meta instanceof Damageable d) {
            d.setMaxDamage(cfg.getInt("item.durability", 2031));
        }
        if (cfg.getBoolean("item.use-custom-model", true)) {
            meta.setItemModel(NamespacedKey.fromString("potendpound:potend_pound"));
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        // Axe behaviour: hits disable shields. (Delete this block if your Paper build's
        // Weapon component API differs - everything else works without it.)
        if (cfg.getBoolean("item.disable-shields", true)) {
            item.setData(DataComponentTypes.WEAPON,
                    Weapon.weapon().itemDamagePerAttack(1).disableBlockingForSeconds(5.0f).build());
        }
        return item;
    }

    /** A fresh weapon with the current config, keeping the old one's non-Sharpness enchants and wear. */
    public ItemStack rebuild(ItemStack old) {
        ItemStack fresh = create();
        ItemMeta fm = fresh.getItemMeta();
        ItemMeta om = old.getItemMeta();
        for (var en : om.getEnchants().entrySet()) {
            if (!en.getKey().equals(Enchantment.SHARPNESS)) fm.addEnchant(en.getKey(), en.getValue(), true);
        }
        if (om instanceof Damageable od && fm instanceof Damageable fd) fd.setDamage(od.getDamage());
        fresh.setItemMeta(fm);
        return fresh;
    }

    public boolean isPotend(ItemStack item) {
        return item != null
                && item.getType() == Material.MACE
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(idKey, PersistentDataType.BYTE);
    }

    public boolean holding(Player p) {
        return isPotend(p.getInventory().getItemInMainHand()) || isPotend(p.getInventory().getItemInOffHand());
    }

    public boolean hasAny(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isPotend(it)) return true;
        }
        return false;
    }
}
