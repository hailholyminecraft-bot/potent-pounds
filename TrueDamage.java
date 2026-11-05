package net.potendpound;

import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Damage that ignores armor, enchantments, resistance and absorption. */
public final class TrueDamage {

    private TrueDamage() {}

    public static void apply(LivingEntity victim, double amount, Player source) {
        if (victim instanceof Player pl && (pl.getGameMode() == GameMode.CREATIVE || pl.getGameMode() == GameMode.SPECTATOR)) return;
        double health = victim.getHealth();
        if (health - amount > 0.0) {
            victim.setHealth(health - amount);
            victim.playHurtAnimation(0f);
            victim.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, victim.getLocation().add(0, 1, 0), 8, 0.3, 0.3, 0.3, 0.1);
            return;
        }
        // lethal: GENERIC_KILL ignores totems and everything else, and credits the thrower with the kill
        var builder = DamageSource.builder(DamageType.GENERIC_KILL);
        if (source != null) builder.withCausingEntity(source).withDirectEntity(source);
        victim.damage(1_000_000.0, builder.build());
    }
}
