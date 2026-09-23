package fireproofghasts;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.HappyGhast;

// same as 1.21.6, but 1.21.9 renamed Entity.level() in Fabric's intermediary names,
// so the 1.21.6 jar crashes here and this has to be compiled against 1.21.9
public final class VersionCompat {
	// op level 2, same as /gamerule
	public static final Predicate<CommandSourceStack> CAN_CONFIGURE = source -> source.hasPermission(2);

	private VersionCompat() {
	}

	public static boolean isHappyGhast(Entity entity) {
		return entity instanceof HappyGhast;
	}
}
