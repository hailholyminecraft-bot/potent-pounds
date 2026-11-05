package net.potendpound;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PotendPoundPlugin extends JavaPlugin implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private PotendItem items;
    private ArmorDowngrade armor;
    private BarrierField barrier;
    private NametagManager nametags;
    private LocatorBarManager locator;
    private TrialChamber chamber;
    private PoundThrow throwing;
    private Village village;
    private AdvancementManager advancements;
    private BossManager bosses;
    private StructureBuilder lastBuild;
    private SettingsGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        items = new PotendItem(this);
        armor = new ArmorDowngrade(this);
        barrier = new BarrierField(this);
        nametags = new NametagManager(this, items);
        locator = new LocatorBarManager(this);
        chamber = new TrialChamber(this, items);
        throwing = new PoundThrow(this, items);
        village = new Village(this);
        advancements = new AdvancementManager(this, items);
        bosses = new BossManager(this, items, advancements);
        gui = new SettingsGui(this, items);

        Bukkit.getPluginManager().registerEvents(new AbilityListener(this, items, armor, barrier, nametags, locator, throwing), this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(new AltarListener(this, items, bosses), this);
        Bukkit.getPluginManager().registerEvents(bosses, this);
        var cmd = getCommand("potentpound");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        nametags.start();
        locator.start();
        advancements.start();
        bosses.cleanupOrphans();
    }

    @Override
    public void onDisable() {
        if (bosses != null) bosses.shutdown();
        if (advancements != null) advancements.shutdown();
        if (throwing != null) throwing.shutdown();
        if (locator != null) locator.shutdown();
        if (nametags != null) nametags.shutdown();
        if (barrier != null) barrier.stopAll();
        if (armor != null) armor.revertAll();
    }

    /** Re-applies settings that are cached by running systems. */
    public void reloadLive() {
        nametags.shutdown();
        nametags.start();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MM.deserialize("<gray>/potentpound <white>gui<gray> | <white>give [player]<gray> | <white>reload<gray> | <white>chamber<gray> | <white>village<gray> | <white>boss<gray> | <white>undo"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(MM.deserialize("<red>Player not found."));
                    return true;
                }
                ItemStack item = items.create();
                target.getInventory().addItem(item).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(MM.deserialize("<gray>Gave the <white>Potent Pound</white> to " + target.getName() + "."));
            }
            case "reload" -> {
                reloadConfig();
                reloadLive();
                sender.sendMessage(MM.deserialize("<gray>Potent Pound config reloaded."));
            }
            case "gui" -> {
                if (!(sender instanceof Player gp) || !sender.hasPermission("potendpound.gui")) {
                    sender.sendMessage(MM.deserialize("<red>Players with potendpound.gui only."));
                    return true;
                }
                gui.open(gp);
            }
            case "chamber" -> {
                if (!(sender instanceof Player p) || !sender.hasPermission("potendpound.chamber")) {
                    sender.sendMessage(MM.deserialize("<red>Players with potendpound.chamber only."));
                    return true;
                }
                if (!chamber.build(p)) sender.sendMessage(MM.deserialize("<red>A structure is already being built or removed."));
                else lastBuild = chamber;
            }
            case "village" -> {
                if (!(sender instanceof Player vp) || !sender.hasPermission("potendpound.chamber")) {
                    sender.sendMessage(MM.deserialize("<red>Players with potendpound.chamber only."));
                    return true;
                }
                if (!village.build(vp)) sender.sendMessage(MM.deserialize("<red>A structure is already being built or removed."));
                else lastBuild = village;
            }
            case "boss" -> {
                if (!(sender instanceof Player bp) || !sender.hasPermission("potendpound.chamber")) {
                    sender.sendMessage(MM.deserialize("<red>Players with potendpound.chamber only."));
                    return true;
                }
                var dir = bp.getLocation().getDirection().setY(0).normalize().multiply(10);
                bosses.spawn(bp.getLocation().add(dir).add(0, 3, 0));
                sender.sendMessage(MM.deserialize("<gray>The Trial Protector awakens."));
            }
            case "undo" -> {
                if (!sender.hasPermission("potendpound.chamber")) {
                    sender.sendMessage(MM.deserialize("<red>No permission."));
                    return true;
                }
                if (lastBuild == null) sender.sendMessage(MM.deserialize("<red>Nothing to undo (only the last build since the last restart is remembered)."));
                else lastBuild.undo(sender);
            }
            default -> sender.sendMessage(MM.deserialize("<red>Unknown subcommand."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("gui");
            out.add("give");
            out.add("reload");
            out.add("chamber");
            out.add("village");
            out.add("boss");
            out.add("undo");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        return out;
    }
}
