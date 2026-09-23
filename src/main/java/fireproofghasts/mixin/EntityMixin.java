package fireproofghasts.mixin;

import fireproofghasts.FireRules;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class EntityMixin {
	// lava, fire blocks, fire aspect, flaming arrows and lightning all set fire through here
	@ModifyVariable(method = "setRemainingFireTicks", at = @At("HEAD"), argsOnly = true)
	private int fireproofghasts$preventBurning(int ticks) {
		return ticks > 0 && FireRules.preventsBurning((Entity) (Object) this) ? 0 : ticks;
	}
}
