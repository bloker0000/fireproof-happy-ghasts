package fireproofghasts;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

// only use vanilla argument types here, otherwise players without the mod can't join
public final class FireproofCommand {
	private static final String NAME = FireproofGhasts.MOD_ID;

	private FireproofCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
		for (Toggle toggle : Toggle.values()) {
			set.then(Commands.literal(toggle.key)
					.then(Commands.argument("value", BoolArgumentType.bool())
							.executes(context -> setToggle(context.getSource(), toggle, BoolArgumentType.getBool(context, "value")))));
		}
		set.then(Commands.literal(Settings.EXTRA_DAMAGE_TYPES)
				.then(Commands.argument("types", StringArgumentType.greedyString())
						.executes(context -> setExtraDamageTypes(context.getSource(), StringArgumentType.getString(context, "types")))));

		dispatcher.register(Commands.literal(NAME)
				.requires(VersionCompat.CAN_CONFIGURE)
				.executes(context -> show(context.getSource()))
				.then(Commands.literal("reload").executes(context -> reload(context.getSource())))
				.then(set));
	}

	private static int show(CommandSourceStack source) {
		Settings settings = FireproofConfig.get();
		MutableComponent text = Component.literal("Fireproof Happy Ghasts (click a setting to change it)").withStyle(ChatFormatting.GOLD);
		for (Toggle toggle : Toggle.values()) {
			boolean value = settings.get(toggle);
			Component shown = Component.literal(String.valueOf(value)).withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
			text.append(settingLine(toggle.key, shown, toggle.description, "set " + toggle.key + " " + !value));
		}
		String extraTypes = String.join(", ", settings.extraDamageTypes());
		Component shown = Component.literal(extraTypes.isEmpty() ? "none" : extraTypes).withStyle(ChatFormatting.AQUA);
		text.append(settingLine(Settings.EXTRA_DAMAGE_TYPES, shown,
				"More damage types to ignore, like minecraft:lightning_bolt or #minecraft:is_explosion. \"none\" clears the list.",
				"set " + Settings.EXTRA_DAMAGE_TYPES + " " + (extraTypes.isEmpty() ? "" : extraTypes)));

		source.sendSuccess(() -> text, false);
		warnAboutUnknownDamageTypes(source, settings);
		return 1;
	}

	private static Component settingLine(String key, Component value, String description, String suggestedArguments) {
		return Component.literal("\n  " + key + ": ").withStyle(ChatFormatting.GRAY)
				.append(value)
				.withStyle(style -> style
						.withClickEvent(new ClickEvent.SuggestCommand("/" + NAME + " " + suggestedArguments))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(description))));
	}

	private static int setToggle(CommandSourceStack source, Toggle toggle, boolean value) {
		return apply(source, FireproofConfig.get().with(toggle, value), toggle.key + " is now " + value);
	}

	private static int setExtraDamageTypes(CommandSourceStack source, String text) {
		List<String> problems = new ArrayList<>();
		List<String> types = Settings.parseDamageTypes(text, problems);
		if (!problems.isEmpty()) {
			problems.forEach(problem -> source.sendFailure(Component.literal(problem)));
			return 0;
		}
		Settings settings = FireproofConfig.get().withExtraDamageTypes(types);
		int result = apply(source, settings, Settings.EXTRA_DAMAGE_TYPES + " is now " + (types.isEmpty() ? "none" : String.join(", ", types)));
		warnAboutUnknownDamageTypes(source, settings);
		return result;
	}

	private static int apply(CommandSourceStack source, Settings settings, String message) {
		try {
			FireproofConfig.update(settings);
		} catch (IOException e) {
			source.sendFailure(Component.literal("Changed until the next restart, but couldn't save "
					+ FireproofConfig.fileName() + ": " + e.getMessage()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Fireproof Happy Ghasts: " + message), true);
		return 1;
	}

	private static int reload(CommandSourceStack source) {
		List<String> problems = FireproofConfig.load();
		problems.forEach(problem -> source.sendFailure(Component.literal(problem)));
		source.sendSuccess(() -> Component.literal("Fireproof Happy Ghasts: reloaded " + FireproofConfig.fileName()), true);
		warnAboutUnknownDamageTypes(source, FireproofConfig.get());
		return problems.isEmpty() ? 1 : 0;
	}

	private static void warnAboutUnknownDamageTypes(CommandSourceStack source, Settings settings) {
		if (settings.extraDamageTypes().isEmpty()) {
			return;
		}
		var damageTypes = source.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
		Set<String> known = damageTypes.listElements()
				.map(Holder::getRegisteredName)
				.collect(Collectors.toCollection(HashSet::new));
		damageTypes.listTagIds().forEach(tag -> known.add("#" + tag.location()));
		for (String type : settings.extraDamageTypes()) {
			if (!known.contains(type)) {
				source.sendFailure(Component.literal("Unknown damage type '" + type + "' in " + Settings.EXTRA_DAMAGE_TYPES + " (typo?)"));
			}
		}
	}
}
