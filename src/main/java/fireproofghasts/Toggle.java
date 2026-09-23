package fireproofghasts;

// new toggles also need a line in FireproofConfig.render()
public enum Toggle {
	ENABLED("enabled", true, "Turns the whole mod on or off."),
	LAVA("lava", true, "Immune to lava."),
	FIRE("fire", true, "Immune to fire, soul fire, campfires, magma blocks and fireballs (anything in #minecraft:is_fire)."),
	BURNING("burning", true, "Never catch fire, so no burning afterwards and no flames."),
	PROTECT_ADULTS("protect_adults", true, "Protect grown-up happy ghasts."),
	PROTECT_GHASTLINGS("protect_ghastlings", true, "Protect ghastlings (baby happy ghasts)."),
	REQUIRE_HARNESS("require_harness", false, "Only protect happy ghasts wearing a harness."),
	REQUIRE_RIDER("require_rider", false, "Only protect happy ghasts while someone rides them."),
	PROTECT_RIDERS("protect_riders", false, "Whoever rides a protected happy ghast gets the same protection.");

	public final String key;
	public final boolean defaultValue;
	public final String description;

	Toggle(String key, boolean defaultValue, String description) {
		this.key = key;
		this.defaultValue = defaultValue;
		this.description = description;
	}
}
