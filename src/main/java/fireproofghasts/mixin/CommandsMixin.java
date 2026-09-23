package fireproofghasts.mixin;

import com.mojang.brigadier.CommandDispatcher;
import fireproofghasts.FireproofCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// registering the command ourselves means the mod doesn't need Fabric API
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Shadow
	public abstract CommandDispatcher<CommandSourceStack> getDispatcher();

	@Inject(method = "<init>", at = @At("TAIL"))
	private void fireproofghasts$registerCommand(CallbackInfo ci) {
		FireproofCommand.register(getDispatcher());
	}
}
