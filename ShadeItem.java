package net.potendpound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** The Shade Sheath: a netherite sword underneath (normal sword stats and cooldown) with a custom model and abilities. */
public final class ShadeItem {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PotendPoundPlugin plugin;
    private final NamespacedKey idKey;

    public ShadeItem(PotendPoundPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "shade_sheath");
    }

    public ItemStack create() {
        FileConfiguration cfg = plugin.getConfig();
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(MM.deserialize(cfg.getString("shade.name", "<!italic><gradient:#7a4ad8:#d2b4ff:#7a4ad8><b>Shade Sheath</b></gradient>")));

        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("shade.lore")) {
            lore.add(MM.deserialize("<!italic>" + line
                    .replace("{dome_sec}", String.valueOf(cfg.getInt("dome.duration-seconds", 15)))
                    .replace("{dome_radius}", String.valueOf((int) cfg.getDouble("dome.radius", 8.0)))
                    .replace("{slam_max}", String.valueOf(cfg.getInt("slam.max-count", 3)))
                    .replace("{halo_kills}", String.valueOf(cfg.getInt("shade.halo-kills", 5)))));
        }
        meta.lore(lore);

        int sharp = cfg.getInt("shade.sharpness", 5);
        if (sharp > 0) meta.addEnchant(Enchantment.SHARPNESS, sharp, true);
        if (cfg.getBoolean("shade.use-custom-model", true)) {
            meta.setItemModel(NamespacedKey.fromString("potendpound:shade_sheath"));
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isShade(ItemStack item) {
        return item != null
                && item.getType() == Material.NETHERITE_SWORD
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(idKey, PersistentDataType.BYTE);
    }

    public boolean holding(Player p) {
        return isShade(p.getInventory().getItemInMainHand());
    }
}
