package dev.upscaler;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpscalerMod implements ModInitializer {
	public static final String MOD_ID = "upscaler";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Register every setting (applying TOML file values) and write a default config on first run.
		UpscalerConfig.ensureRegistered();
		UpscalerConfig.saveIfMissing();
		LOGGER.info("Upscaler initialized (common); config: {}", UpscalerConfig.configPath());
	}
}
