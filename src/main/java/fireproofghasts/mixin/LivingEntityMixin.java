package fireproofghasts.mixin;

import fireproofghasts.FireRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
	private void fireproofghasts$ignoreDamage(ServerLevel level, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (FireRules.ignoresDamage((LivingEntity) (Object) this, source)) {
			cir.setReturnValue(true);
		}
	}
}
