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

        meta.itemName(MM.deserialize(cfg.getString("item.name", "Potend Pound")));

        String range = String.valueOf((int) cfg.getDouble("downgrade.range", 20));
        String dur = String.valueOf(cfg.getInt("downgrade.duration-seconds", 10));
        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("item.lore")) {
            lore.add(MM.deserialize("<!italic>" + line.replace("{range}", range).replace("{duration}", dur)));
        }
        meta.lore(lore);

        // Sharpness 7 by default. Density / Wind Burst are added by the player (anvil).
        meta.addEnchant(Enchantment.SHARPNESS, 7, true);

        // Axe-like stats (same ids vanilla uses, so the tooltip shows them as base stats).
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                NamespacedKey.minecraft("base_attack_damage"),
                cfg.getDouble("item.attack-damage", 9.0),
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                NamespacedKey.minecraft("base_attack_speed"),
                cfg.getDouble("item.attack-speed", -3.0),
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
