package dev.upscaler.client;

import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtSmokeTest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class UpscalerClient implements ClientModInitializer {
	private static boolean smokeTestDone = false;

	@Override
	public void onInitializeClient() {
		UpscalerMod.LOGGER.info("Sodium Upscaler client initialized");

		// The GpuDevice exists well before the first tick, so a one-shot at tick
		// start runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (!smokeTestDone) {
				FsrSmokeTest.run();
				DlssSmokeTest.run();
				smokeTestDone = true;
			}
			RtSmokeTest.run();
		});
	}
}
