package dev.upscaler.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.RtComposite;
import dev.upscaler.rt.RtContext;
import dev.upscaler.rt.RtTerrain;

public final class VanillaRenderController {
	public static final VanillaRenderController INSTANCE = new VanillaRenderController();

	private static final boolean CANCEL_WORLD = Boolean.parseBoolean(System.getProperty("upscaler.rt.cancelVanillaWorld", "false"));
	private static final boolean LOG = Boolean.parseBoolean(System.getProperty("upscaler.rt.cancelVanillaWorld.log", "true"));

	public enum OutputMode {
		RT,
		VANILLA;

		public static OutputMode parse(String value) {
			if ("vanilla".equalsIgnoreCase(value)) {
				return VANILLA;
			}
			return RT;
		}
	}

	private static volatile OutputMode outputModeOverride;

	private boolean frameStarted;
	private boolean baseReady;
	private boolean projectionCaptured;
	private boolean worldSkipped;
	private boolean failureLatched;
	private boolean loggedActive;
	private boolean loggedWaitingForRtPlayerSection;
	private boolean loggedRtPlayerSectionReady;
	private OutputMode outputMode = OutputMode.RT;
	private OutputMode lastLoggedOutputMode;
	private String inactiveReason;
	private String lastLoggedInactiveReason;

	private VanillaRenderController() {
	}

	public void beginFrame(RenderTarget mainTarget) {
		this.frameStarted = true;
		this.projectionCaptured = false;
		this.worldSkipped = false;
		this.baseReady = false;
		this.inactiveReason = null;
		this.outputMode = currentOutputMode();

		if (LOG && this.outputMode != this.lastLoggedOutputMode) {
			this.lastLoggedOutputMode = this.outputMode;
			UpscalerMod.LOGGER.info("RT output mode: {}", this.outputMode == OutputMode.RT ? "rt" : "vanilla");
		}

		if (!CANCEL_WORLD || this.outputMode == OutputMode.VANILLA) {
			return;
		}

		this.inactiveReason = findInactiveReason(mainTarget);
		this.baseReady = this.inactiveReason == null;
		if (this.baseReady) {
			if (LOG && !this.loggedActive) {
				this.loggedActive = true;
				UpscalerMod.LOGGER.info("Vanilla world rendering cancellation active; using existing RT composite seam");
			}
		} else {
			logInactive(this.inactiveReason);
		}
	}

	public void markProjectionCaptured() {
		this.projectionCaptured = true;
	}

	public boolean shouldCancelLevelRenderer() {
		return this.shouldCancelLevelRenderer(false);
	}

	public boolean shouldCancelLevelRenderer(boolean waitingForRtPlayerSection) {
		if (!CANCEL_WORLD) {
			return false;
		}
		if (this.outputMode == OutputMode.VANILLA) {
			return false;
		}
		if (!this.frameStarted) {
			logInactive("frame controller was not started");
			return false;
		}
		if (!this.baseReady) {
			return false;
		}
		if (!this.projectionCaptured) {
			logInactive("level projection was not captured");
			return false;
		}
		if (waitingForRtPlayerSection && LOG && !this.loggedWaitingForRtPlayerSection) {
			this.loggedWaitingForRtPlayerSection = true;
			UpscalerMod.LOGGER.info("Keeping vanilla LevelRenderer canceled while waiting for RT player section residency");
		}
		return true;
	}

	public void markRtPlayerSectionReady() {
		if (LOG && !this.loggedRtPlayerSectionReady) {
			this.loggedRtPlayerSectionReady = true;
			UpscalerMod.LOGGER.info("Satisfied vanilla terrain-load callback from RT player section residency");
		}
	}

	public void markWorldSkipped() {
		this.worldSkipped = true;
	}

	public boolean wasWorldSkippedThisFrame() {
		return this.worldSkipped;
	}

	public boolean shouldCompositeRt() {
		return this.outputMode == OutputMode.RT && RtComposite.ENABLED;
	}

	/**
	 * Runtime work/output switch. Startup Vulkan RT feature negotiation is controlled
	 * separately by {@code upscaler.rt}, so VANILLA can be flipped back to RT later.
	 */
	public static boolean rtRuntimeWorkRequested() {
		return currentOutputMode() == OutputMode.RT;
	}

	public static OutputMode currentOutputMode() {
		OutputMode override = outputModeOverride;
		if (override != null) {
			return override;
		}
		return readOutputMode();
	}

	public static void setOutputMode(OutputMode mode) {
		if (mode == null) {
			throw new IllegalArgumentException("mode");
		}
		outputModeOverride = mode;
	}

	public static void clearOutputModeOverride() {
		outputModeOverride = null;
	}

	public void markRtCompositeResult(boolean success) {
		if (this.worldSkipped && !success) {
			latchFailure("RT composite did not produce a replacement frame");
		}
	}

	public void markMissedBeforeHandSeam() {
		if (this.worldSkipped) {
			latchFailure("missed before-hand RT composite seam after vanilla world was skipped");
		}
	}

	private String findInactiveReason(RenderTarget mainTarget) {
		if (this.failureLatched || RtComposite.INSTANCE.hasFailed()) {
			return "RT composite failure latch is set";
		}
		if (!RtComposite.ENABLED) {
			return "upscaler.rt.composite is false";
		}
		if (RtContext.currentOrNull() == null) {
			return "RT context is not ready";
		}
		if (RtTerrain.currentOrNull() == null) {
			return "RT terrain is not ready";
		}
		if (mainTarget == null || mainTarget.getColorTexture() == null || mainTarget.getDepthTexture() == null) {
			return "main render target textures are not ready";
		}
		return null;
	}

	private void latchFailure(String reason) {
		if (!this.failureLatched && LOG) {
			UpscalerMod.LOGGER.warn("Disabling vanilla world cancellation: {}", reason);
		}
		this.failureLatched = true;
		this.baseReady = false;
		this.inactiveReason = reason;
	}

	private void logInactive(String reason) {
		if (!LOG || reason == null || reason.equals(this.lastLoggedInactiveReason)) {
			return;
		}
		UpscalerMod.LOGGER.info("Vanilla world cancellation inactive: {}", reason);
		this.lastLoggedInactiveReason = reason;
	}

	private static OutputMode readOutputMode() {
		return OutputMode.parse(System.getProperty("upscaler.rt.output", "rt"));
	}
}
