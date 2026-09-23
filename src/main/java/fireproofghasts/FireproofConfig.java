package fireproofghasts;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class FireproofConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(FireproofGhasts.MOD_ID + ".properties");

	// always swapped as a whole, so the server never sees half-applied settings
	private static volatile Settings current = Settings.DEFAULTS;

	private FireproofConfig() {
	}

	public static Settings get() {
		return current;
	}

	public static String fileName() {
		return "config/" + FILE.getFileName();
	}

	public static List<String> load() {
		List<String> problems = new ArrayList<>();
		if (Files.notExists(FILE)) {
			current = Settings.DEFAULTS;
			try {
				save(current);
			} catch (IOException e) {
				problems.add("Couldn't create " + fileName() + ": " + e.getMessage());
			}
		} else {
			Properties file = new Properties();
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				file.load(reader);
				current = parse(file, problems);
			} catch (IOException e) {
				problems.add("Couldn't read " + fileName() + " (" + e.getMessage() + "), keeping the previous settings");
			}
		}
		problems.forEach(problem -> FireproofGhasts.LOGGER.warn("{}", problem));
		return problems;
	}

	public static void update(Settings settings) throws IOException {
		current = settings;
		save(settings);
	}

	private static Settings parse(Properties file, List<String> problems) {
		Settings settings = Settings.DEFAULTS;
		for (Toggle toggle : Toggle.values()) {
			String value = file.getProperty(toggle.key);
			if (value == null) {
				continue;
			}
			switch (value.trim().toLowerCase(Locale.ROOT)) {
				case "true" -> settings = settings.with(toggle, true);
				case "false" -> settings = settings.with(toggle, false);
				default -> problems.add(toggle.key + "=" + value.trim() + " should be true or false, using " + toggle.defaultValue);
			}
		}
		String extraTypes = file.getProperty(Settings.EXTRA_DAMAGE_TYPES);
		if (extraTypes != null) {
			settings = settings.withExtraDamageTypes(Settings.parseDamageTypes(extraTypes, problems));
		}
		for (String key : file.stringPropertyNames()) {
			if (!key.equals(Settings.EXTRA_DAMAGE_TYPES) && findToggle(key) == null) {
				problems.add("Unknown setting '" + key + "' in " + fileName() + " (ignored)");
			}
		}
		return settings;
	}

	private static Toggle findToggle(String key) {
		for (Toggle toggle : Toggle.values()) {
			if (toggle.key.equals(key)) {
				return toggle;
			}
		}
		return null;
	}

	private static void save(Settings settings) throws IOException {
		Files.createDirectories(FILE.getParent());
		Files.writeString(FILE, render(settings), StandardCharsets.UTF_8);
	}

	private static String render(Settings settings) {
		StringBuilder out = new StringBuilder();
		out.append("# Fireproof Happy Ghasts\n");
		out.append("# Run /fireproofghasts reload after editing. Changing settings in-game rewrites this file.\n");
		appendToggle(out, settings, Toggle.ENABLED);
		appendToggle(out, settings, Toggle.LAVA);
		appendToggle(out, settings, Toggle.FIRE);
		appendToggle(out, settings, Toggle.BURNING);
		out.append("\n# More damage types to ignore, comma separated: ids like minecraft:lightning_bolt or tags like #minecraft:is_explosion.\n");
		out.append(Settings.EXTRA_DAMAGE_TYPES).append('=').append(String.join(", ", settings.extraDamageTypes())).append('\n');
		appendToggle(out, settings, Toggle.PROTECT_ADULTS);
		appendToggle(out, settings, Toggle.PROTECT_GHASTLINGS);
		appendToggle(out, settings, Toggle.REQUIRE_HARNESS);
		appendToggle(out, settings, Toggle.REQUIRE_RIDER);
		appendToggle(out, settings, Toggle.PROTECT_RIDERS);
		return out.toString();
	}

	private static void appendToggle(StringBuilder out, Settings settings, Toggle toggle) {
		out.append("\n# ").append(toggle.description).append('\n');
		out.append(toggle.key).append('=').append(settings.get(toggle)).append('\n');
	}
}
