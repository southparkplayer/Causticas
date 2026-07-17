package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

public final class CausticaClient implements ClientModInitializer {
	private static boolean rtInitDone = false;

	@Override
	public void onInitializeClient() {
		CausticaMod.LOGGER.info("Caustica client initialized");
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(CausticaMod.MOD_ID, "offline-renderer"),
				OfflineRendererHud::extractRenderState);

		// The GpuDevice exists well before the first tick, so a one-shot at tick start
		// runs on the render thread with the device idle between frames.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			CausticaDebugBridge.tick(client);
			while (CausticaKeyMappings.OPEN_MENU.consumeClick()) {
				client.setScreenAndShow(new CausticaOptionsScreen(null, client.options));
			}
			while (OfflineGroundTruth.KEY.consumeClick()) {
				OfflineGroundTruth.INSTANCE.handleHotkey(client);
			}
			OfflineGroundTruth.INSTANCE.tick(client);
			while (UltraScreenshot.KEY.consumeClick()) {
				UltraScreenshot.INSTANCE.toggle(client);
			}
			UltraScreenshot.INSTANCE.tick(client);
			dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator.INSTANCE.synchronizeRequestedState();
			if (!VanillaRenderController.rtRuntimeWorkRequested()) {
				if (rtInitDone) {
					shutdownRt();
				}
				return;
			}

			// Bring up the RT device/context once; terrain residency + the composite follow below.
			if (!rtInitDone && RtDeviceBringup.rtRequested()) {
				RtContext ctx = RtContext.get();
				if (ctx != null) {
					rtInitDone = true;
				}
			}

			// P2: once RT is up, keep section residency synced to vanilla's loaded chunks around
			// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
			if (rtInitDone) {
				RtContext ctx = RtContext.currentOrNull();
				if (ctx != null) {
					RtFrameStats.FRAME.beginIfInactive();
					// Bring the world pipeline + LabPBR atlases up before terrain tessellates, so per-prim
					// material flags resolve from the first section (PBR on join, no re-extract). No-op
					// until we're in a world with the block atlas loaded, or once already created.
					RtComposite.INSTANCE.ensureResourcesReady(ctx);
					if (!OfflineGroundTruth.INSTANCE.active()) {
						RtTerrain.update(ctx);
					}
					// Log DLSS-FG availability once when frame generation is enabled (capability query only;
					// the present-loop integration that consumes it is built separately).
					if (dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.enabled()) {
						dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.probeAvailabilityOnce();
					}
				}
			}
		});

		// Vanilla's full render-state invalidation (LevelExtractor.allChanged(): dimension change via
		// setLevel, render-distance change, F3+A) — drop RT terrain residency so it rebuilds for the new
		// world. Fixes stale geometry persisting across an End→Overworld switch (coords alone aren't
		// world-unique). Resource reloads do NOT fire this; that path is handled separately.
		InvalidateRenderStateCallback.EVENT.register(() -> {
			if (OfflineGroundTruth.INSTANCE.engaged()) {
				OfflineGroundTruth.INSTANCE.abort("Offline renderer stopped: world render state changed");
			}
			RtTerrain.requestFullClear();
			RtComposite.INSTANCE.requestTemporalReset();
			if (VanillaRenderController.INSTANCE.shouldResetRtFailureLatchForInvalidation()) {
				RtComposite.INSTANCE.resetFailureLatch(); // F3+A doubles as manual RT recovery after a latched failure
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			shutdownRt();
			dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.destroy();
		});
	}

	private static void shutdownRt() {
		WorldRenderScaler.INSTANCE.destroy();
		RtWorkerPool.INSTANCE.shutdown(); // no-op if never started; stops worker threads on teardown
		RtUiOverlay.destroy(); // GUI redirect is not gated by rtInitDone; always release its TextureTarget
		if (!rtInitDone) {
			return;
		}

		RtContext ctx = RtContext.currentOrNull();
		if (ctx != null) {
			ctx.waitIdle("client shutdown");
			RtTerrain.shutdown(ctx);
			RtEntities.INSTANCE.shutdown();
		}
		RtComposite.INSTANCE.destroy();
		RtEntityTextures.INSTANCE.reset();
		RtBlockMaterials.INSTANCE.destroy();
		// RtComposite released the RR viewport resources above. Keep Streamline itself alive across
		// resource/RT reloads; the VulkanDevice close hook performs the one process-wide shutdown after
		// every Streamline feature has stopped using the shared Vulkan device.
		if (ctx != null) {
			ctx.destroy();
		}
		rtInitDone = false;
	}
}
