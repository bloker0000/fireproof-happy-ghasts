package fireproofghasts;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FireproofGhasts implements ModInitializer {
	public static final String MOD_ID = "fireproofghasts";
	public static final Logger LOGGER = LoggerFactory.getLogger("Fireproof Happy Ghasts");

	@Override
	public void onInitialize() {
		FireproofConfig.load();
		LOGGER.info("Loaded settings from {}", FireproofConfig.fileName());
	}
}
