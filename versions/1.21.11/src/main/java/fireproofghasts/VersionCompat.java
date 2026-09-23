package fireproofghasts;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;

// 1.21.11 moved HappyGhast and replaced numeric permission levels
public final class VersionCompat {
	public static final Predicate<CommandSourceStack> CAN_CONFIGURE = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private VersionCompat() {
	}

	public static boolean isHappyGhast(Entity entity) {
		return entity instanceof HappyGhast;
	}
}
