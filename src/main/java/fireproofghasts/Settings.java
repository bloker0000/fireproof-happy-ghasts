package fireproofghasts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Settings {
	public static final String EXTRA_DAMAGE_TYPES = "extra_damage_types";
	public static final Settings DEFAULTS = new Settings(defaultToggles(), List.of());

	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

	private final boolean[] toggles;
	private final List<String> extraDamageTypes;
	private final Set<String> extraIds;
	private final Set<String> extraTags;

	private Settings(boolean[] toggles, List<String> extraDamageTypes) {
		this.toggles = toggles;
		this.extraDamageTypes = List.copyOf(extraDamageTypes);
		this.extraIds = extraDamageTypes.stream()
				.filter(type -> !type.startsWith("#"))
				.collect(Collectors.toUnmodifiableSet());
		this.extraTags = extraDamageTypes.stream()
				.filter(type -> type.startsWith("#"))
				.map(type -> type.substring(1))
				.collect(Collectors.toUnmodifiableSet());
	}

	public boolean get(Toggle toggle) {
		return toggles[toggle.ordinal()];
	}

	public List<String> extraDamageTypes() {
		return extraDamageTypes;
	}

	public Set<String> extraIds() {
		return extraIds;
	}

	public Set<String> extraTags() {
		return extraTags;
	}

	public Settings with(Toggle toggle, boolean value) {
		boolean[] changed = toggles.clone();
		changed[toggle.ordinal()] = value;
		return new Settings(changed, extraDamageTypes);
	}

	public Settings withExtraDamageTypes(List<String> types) {
		return new Settings(toggles, types);
	}

	// "lightning_bolt, #is_explosion" -> [minecraft:lightning_bolt, #minecraft:is_explosion]
	public static List<String> parseDamageTypes(String text, List<String> problems) {
		List<String> types = new ArrayList<>();
		for (String entry : text.split("[,\\s]+")) {
			String type = entry.toLowerCase(Locale.ROOT);
			if (type.isEmpty() || type.equals("none")) {
				continue;
			}
			boolean tag = type.startsWith("#");
			String id = tag ? type.substring(1) : type;
			if (!id.contains(":")) {
				id = "minecraft:" + id;
			}
			if (!ID.matcher(id).matches()) {
				problems.add("'" + entry + "' is not a damage type id or #tag");
				continue;
			}
			type = tag ? "#" + id : id;
			if (!types.contains(type)) {
				types.add(type);
			}
		}
		return types;
	}

	private static boolean[] defaultToggles() {
		boolean[] values = new boolean[Toggle.values().length];
		for (Toggle toggle : Toggle.values()) {
			values[toggle.ordinal()] = toggle.defaultValue;
		}
		return values;
	}
}
