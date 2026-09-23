package fireproofghasts;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.HappyGhast;

// the only code that differs per Minecraft version (this one: 1.21.6 - 1.21.8)
public final class VersionCompat {
	// op level 2, same as /gamerule
	public static final Predicate<CommandSourceStack> CAN_CONFIGURE = source -> source.hasPermission(2);

	private VersionCompat() {
	}

	public static boolean isHappyGhast(Entity entity) {
		return entity instanceof HappyGhast;
	}
}
