package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.CausticaJitter;
import dev.comfyfluffy.caustica.client.OfflineGroundTruth;
import dev.comfyfluffy.caustica.mixin.CommandEncoderAccessor;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.gen.SharcPushAddrData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.BreakEntry;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtEmissionSemantics;
import dev.comfyfluffy.caustica.rt.material.RtMaterialOverrides;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtBloomPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssdDisocclusionPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtFgUiAlphaPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtPathSampleSequence;
import dev.comfyfluffy.caustica.rt.pipeline.RtBlueNoiseSequence;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSharcResolvePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSkyViewPipeline;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dcaustica.rt=true}.
 *
 * <p>The path tracer and its guide buffers run at the configured render scale of display res with a per-frame
 * sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the display-res image. With RR
 * disabled the trace runs at 1:1 and a linear blit stands in for the upscale (a raw, noisy reference).
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();

    public static boolean enabled() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    public boolean sharcActive() {
        return sharcCache != null && sharcUpdatePipeline != null && sharcResolvePipeline != null
                && sharcQueryPipeline != null && sharcDiffuseQueryPipeline != null;
    }

    public boolean sharcPrimaryDiffuseActive() {
        return sharcActive() && CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE.value()
                && sharcPrimaryQueryPipeline != null;
    }

    public void requestSharcReset(String reason) {
        if (sharcCache != null) sharcCache.requestReset(reason);
    }

    public int sharcCapacity() {
        return sharcCache == null ? 0 : sharcCache.capacity();
    }

    public long sharcBytes() {
        return sharcCache == null ? 0L : sharcCache.bytes();
    }

    public long sharcResetCountValue() {
        return sharcCache == null ? 0L : sharcCache.resetCount();
    }

    public String sharcLastResetReason() {
        return sharcCache == null ? "none" : sharcCache.lastResetReason();
    }

    public long baselineTraceGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.baselineTraceNanos(); }
    public long sharcUpdateGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.sharcUpdateNanos(); }
    public long sharcResolveGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.sharcResolveNanos(); }
    public long sharcQueryGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.sharcQueryNanos(); }
    public long blasGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.blasNanos(); }
    public long tlasGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.tlasNanos(); }
    public long reconstructionGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.reconstructionNanos(); }
    public long disocclusionGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.disocclusionNanos(); }
    public long dlssRrGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.dlssRrNanos(); }
    public long exposureGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.exposureNanos(); }
    public long displayGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.displayNanos(); }
    public long copyGpuNanos() { return traceGpuProfiler == null ? 0L : traceGpuProfiler.copyNanos(); }
    public int renderWidth() { return renderW; }
    public int renderHeight() { return renderH; }
    public float skySunAngle() { return publishedSunAngle; }
    public float skyMoonAngle() { return publishedMoonAngle; }
    public float skyDayFactor() { return publishedDayFactor; }
    public float skyTwilightFactor() { return publishedTwilightFactor; }
    public float skyAmbientEv() { return publishedAmbientEv; }
    public float skySunX() { return publishedSunX; }
    public float skySunY() { return publishedSunY; }
    public float skySunZ() { return publishedSunZ; }
    public float skyMoonX() { return publishedMoonX; }
    public float skyMoonY() { return publishedMoonY; }
    public float skyMoonZ() { return publishedMoonZ; }
    public float exposureActualEv() { return exposure.actualEv(); }
    public float exposureTargetEv() { return exposure.targetEv(); }
    public float exposureConfidence() { return exposure.confidence(); }
    public float exposureTrustedCoverage() { return exposure.trustedCoverage(); }
    public float exposureActiveCeilingEv() { return exposure.activeCeilingEv(); }
    public float exposureAverageLogLuminance() { return exposure.averageLogLuminance(); }

    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // tableAddr/entityTableAddr/frameIndex are duplicated so hit shaders skip a global-memory dereference;
    // PushAddrData is generated from the same Slang module and owns this second ABI as well.
    private static final int GUIDE_COUNT = 11; // RR guides, offline mean/pilots, animated mask, diffuse path guide
    private static final int FRAME_FLAG_RR_GUIDES = 1 << 5;
    private static final int FRAME_FLAG_FG_GUIDES = 1 << 6;
    private static final int FRAME_FLAG_OFFLINE_GROUND_TRUTH = 1 << 7;
    private static final int FRAME_FLAG_OFFLINE_CALIBRATING = 1 << 8;
    private static final int FRAME_FLAG_DIFFUSE_PATH_GUIDE = 1 << 9;
    private static final int FRAME_FLAG_EARTH_ATMOSPHERE = 1 << 10;
    private static final int FRAME_FLAG_EXPOSURE_DEPTH = 1 << 11;
    private static final double TEMPORAL_TELEPORT_DISTANCE_SQ = 64.0 * 64.0;
    private static final long TEMPORAL_GAP_NANOS = 500_000_000L;
    // Frames a retired per-frame TLAS must outlive before it's freed (> frames-in-flight); matches
    // RtTerrain's deferred-free horizon. The frame TLAS is built + traced this frame, then freed once
    // the composite frame counter has advanced this far past it (so no in-flight frame still reads it).
    private static final int KEEP_FRAMES = 4;

    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return OfflineGroundTruth.INSTANCE.active()
                ? OfflineGroundTruth.INSTANCE.samplesPerBatch()
                : CausticaConfig.Rt.Composite.SPP.value();
    }

    private static int maxBounces() {
        return OfflineGroundTruth.INSTANCE.active()
                ? OfflineGroundTruth.INSTANCE.maxBounces()
                : CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    private static boolean waterWaves() {
        return CausticaConfig.Rt.Composite.WATER_WAVES.value();
    }

    private static int groundTruthSettingsSignature() {
        return OfflineGroundTruth.INSTANCE.sessionSignature();
    }

    // Finite sun/moon angular sizes let NEE shadow rays sample the light disk (soft, contact-hardening
    // penumbrae). The defaults use the real sun/moon angular radii of approximately 0.27 degrees.
    private static final int WATER_ANCHOR_MASK = 4095;
    private static final Identifier SUN_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_IDS = createMoonIds();
    // The physical sky supplies the local north celestial pole; atlas-body tangent frames and stars share it.
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    // Monotonic per-composite frame counter, used by RtTerrain to time frames-in-flight-safe frees.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline worldPipeline;
    private RtPipeline offlinePipeline;
    private RtPipeline sharcUpdatePipeline;
    private RtPipeline sharcQueryPipeline;
    private RtPipeline sharcDiffuseQueryPipeline;
    private RtPipeline sharcPrimaryQueryPipeline;
    private RtPipeline sharcDiagnosticUpdatePipeline;
    private RtPipeline sharcDiagnosticQueryPipeline;
    private RtPipeline sharcPrimaryDiagnosticQueryPipeline;
    private RtSharcResolvePipeline sharcResolvePipeline;
    private RtSharcCache sharcCache;
    private RtTraceGpuProfiler traceGpuProfiler;
    private int sharcTerrainX = Integer.MIN_VALUE;
    private int sharcTerrainY = Integer.MIN_VALUE;
    private int sharcTerrainZ = Integer.MIN_VALUE;
    private float sharcSceneScale = Float.NaN;
    private float sharcRadianceScale = Float.NaN;
    private String sharcCreationResetReason = "enabled";
    private Object sharcWorldIdentity;
    private boolean sharcPrimaryModeKnown;
    private boolean lastSharcPrimaryMode;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundAtlasHandle;
    private long boundCelestialAtlasHandle;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // World push data (256 B) lives in a host-visible BDA ring; only the 8-byte slot address is pushed
    // inline (256-byte NVIDIA push constant ceiling is otherwise exhausted by the world push struct).
    // One slot per in-flight frame, cycled per frame so an in-flight slot is never overwritten.
    private static final int PUSH_RING = 6;
    private RtBuffer[] pushRing;
    private RtBlueNoiseSequence blueNoiseSequence;
    private RtPathSampleSequence pathSampleSequence;
    private int pushSlot;
    private RtDisplayPipeline displayPipeline;
    private RtBloomPipeline bloomPipeline;
    private RtSkyViewPipeline skyViewPipeline;
    private RtImage skyViewLut;
    private RtImage skyTransmittanceLut;
    private boolean skyTransmittanceReady;
    private boolean skyViewStateValid;
    private float lastSkyViewSunX, lastSkyViewSunY, lastSkyViewSunZ, lastSkyViewSunSource;
    private float lastSkyViewMoonX, lastSkyViewMoonY, lastSkyViewMoonZ, lastSkyViewMoonSource;
    private boolean lastSkyViewEnabled;
    private RtImage output;
    private RtImage displayImage;
    // Parallel PQ-encoded ([0,1], ST.2084) HDR display image. Written alongside displayImage when HDR is
    // enabled. When the PQ swapchain is active, the combined UI overlay is composited over this image, then
    // this image is blitted straight to the swapchain.
    private RtImage hdrDisplayImage;
    private RtImage bloomHalf;
    private RtImage bloomQuarter;
    private RtImage bloomEighth;
    // Set true after this frame's display dispatch wrote hdrDisplayImage (HDR enabled + RT ran); gates the
    // HDR present blit so a frame where RT did not run falls back to the vanilla SDR present.
    private boolean hdrWrittenThisFrame;
    // One independently retired input set per application-visible swapchain image. Streamline can consume
    // these resources asynchronously after the real present, so no set may be overwritten until its
    // DLSSGState timeline value completes.
    private FgInputSlot[] fgInputSlots = new FgInputSlot[0];
    private RtFgUiAlphaPipeline fgUiAlphaPipeline;
    // Step C.2: composites the combined UI overlay over hdrDisplayImage at paper white, just before present.
    private RtHdrCompositePipeline hdrCompositePipeline;
    private long hdrUiSampler;
    // Menu/non-RT present: converts the SDR main target (sRGB) to PQ-encoded at paper white so menus,
    // the title panorama and the loading screen present correctly to the PQ swapchain instead of being
    // raw-copied (misdisplayed). Lazily created; the image is sized to the swapchain.
    private RtSdrPresentPipeline sdrPresentPipeline;
    private RtImage sdrPresentImage;
    // Streamline temporal state for the inputs attached to the next real present.
    private boolean fgReset = true;
    private final Matrix4f fgPreviousViewProjection = new Matrix4f();
    private boolean fgPreviousViewProjectionValid;
    private float frameJitterX;
    private float frameJitterY;
    // Animated reflection motion needs the exact wave phase from the previous submitted RT frame. Use
    // renderer-relative monotonic seconds (rather than wrapping wall-clock seconds) so the wave field
    // never jumps at an arbitrary modulo boundary.
    private final long waterWaveEpochNanos = System.nanoTime();
    private float previousWaterWaveTime;
    private boolean previousWaterWaveTimeValid;
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, depth, motion,
    // specular albedo, and reflection motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    private RtImage gSpecMotion;
    private RtImage groundTruthAccum;
    private RtImage offlinePilotA;
    private RtImage offlinePilotB;
    private RtImage gAnimatedGuide;
    private RtImage gDiffuseRayDirectionHitDistance;
    private RtImage gDepthHistoryA;
    private RtImage gDepthHistoryB;
    private RtImage gDisocclusion;
    private RtImage gBiasCurrentColor;
    private boolean diffusePathGuideKnown;
    private boolean lastDiffusePathGuide;
    private RtDlssdDisocclusionPipeline dlssdDisocclusionPipeline;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private final RtExposure exposure = new RtExposure();

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;
    private boolean renderSizeFgGuides;
    private boolean renderSizeExposureDepth;
    private boolean renderSizeHdrEnabled;
    private boolean renderSizeGroundTruth;
    private boolean rrProducedPreviousFrame;
    private int lastDebugView = Integer.MIN_VALUE;
    private float lastEmissiveIntensity = Float.NaN;
    private float lastSkySunX = Float.NaN, lastSkySunY = Float.NaN, lastSkySunZ = Float.NaN;
    private long lastPacketDayTime = Long.MIN_VALUE;
    private long lastPacketGameTime = Long.MIN_VALUE;
    private volatile boolean commandTimeResetRequested;
    private float lastSkyAmbientEv = Float.NaN;
    private float lastSunlightEv = Float.NaN;
    private float lastMoonlightEv = Float.NaN;
    private float lastAirglowEv = Float.NaN;
    private float lastSunAngularRadius = Float.NaN;
    private float lastMoonAngularRadius = Float.NaN;
    private volatile float publishedSunAngle, publishedMoonAngle, publishedDayFactor, publishedTwilightFactor;
    private volatile float publishedAmbientEv, publishedSunX, publishedSunY, publishedSunZ;
    private volatile float publishedMoonX, publishedMoonY, publishedMoonZ;
    private int groundTruthAccumulationFrames;
    private SkyPush offlineSkyPush;
    private int groundTruthSettingsSignature = Integer.MIN_VALUE;
    private float groundTruthWaterWaveTime = Float.NaN;

    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, snapshotted for consumers each frame before the rolling state advances.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    // Snapshot of the true previous matrix for FG; mvPrevProjView advances during updateMotion().
    private final Matrix4f mvFgPrevProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private final BlockPos.MutableBlockPos cameraBlockPos = new BlockPos.MutableBlockPos();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private long atlasSampler;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f previousCapturedProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;
    private boolean hasCapturedProjection;
    private boolean offlineCameraCaptured;
    private boolean rtFrameProducedThisFrame;
    private long lastFrameBeginNanos;
    private long celestialUvAtlasHandle;
    private int celestialUvMoonPhase = -1;
    private float sunU0;
    private float sunV0;
    private float sunU1;
    private float sunV1;
    private float moonU0;
    private float moonV0;
    private float moonU1;
    private float moonV1;
    private boolean celestialUvFailureLogged;
    private boolean celestialUvResolvedLogged;

    // Per-frame TLAS resources, rebuilt in place from a small ring of persistent slots (see
    // RtAccel.TlasRing — replaces the old create-and-defer-destroy-per-frame churn whose VMA slow path
    // showed up as rare multi-ms prepareTlas spikes).
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();
    private RtAccel.PreparedTlas offlineTlas;
    private long offlineLastPresentNanos;
    private static final long OFFLINE_PRESENT_INTERVAL_NANOS = 125_000_000L;

    // This frame's TLAS handle, published after prepareTlas so the world-overlay pass (block outline's
    // rayQueryEXT occlusion test) can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (RtWorldOverlay's transient buffer runs later, same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    private long pendingTerrainGraphicsUse;

    private RtComposite() {
    }

    /** This frame's TLAS handle (0 if none built yet), for {@code dev.comfyfluffy.caustica.rt.overlay} occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    private static Identifier[] createMoonIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /** Keep vanilla rendering alive while a new material/descriptor epoch is not trace-ready. */
    public boolean requiresVanillaWorldFallback() {
        if (worldPipeline == null || !materialBindingsReady) return true;
        if (RtEntityTextures.maxTextures() > bindlessTextureCapacity) return true;
        if (reloadRebindRequested) {
            long atlas = blockAtlasView();
            return atlas == 0L || atlas == boundAtlasHandle;
        }
        return false;
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on vanilla until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            CausticaMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
        RtDlssRr.INSTANCE.resetFailureLatch();
    }

    /** Reset every temporal consumer owned by the composite after a render-state discontinuity. */
    public void requestTemporalReset() {
        mvHasPrev = false;
        fgPreviousViewProjectionValid = false;
        fgReset = true;
        frameCaptured = false;
        rtFrameProducedThisFrame = false;
        rrProducedPreviousFrame = false;
        lastDebugView = Integer.MIN_VALUE;
        lastSkySunX = lastSkySunY = lastSkySunZ = Float.NaN;
        lastPacketDayTime = Long.MIN_VALUE;
        lastPacketGameTime = Long.MIN_VALUE;
        // Do not consume a pending server-command TOD discontinuity here. skyPush owns that flag, so a
        // coincident camera/FOV reset cannot prevent the required SHARC + lighting-history invalidation.
        lastSkyAmbientEv = Float.NaN;
        lastSunlightEv = Float.NaN;
        lastMoonlightEv = Float.NaN;
        lastAirglowEv = Float.NaN;
        lastSunAngularRadius = Float.NaN;
        lastMoonAngularRadius = Float.NaN;
        hasCapturedProjection = false;
        previousWaterWaveTimeValid = false;
        exposure.resetAutoHistory();
        // RR/FG resets are routine (FOV transitions, reload notifications, debug changes). Once an
        // offline session owns a frozen camera and scene, those unrelated temporal resets must not erase
        // expensive accumulation. Actual offline image recreation still calls onRendererReset directly.
        if (!OfflineGroundTruth.INSTANCE.active()) {
            groundTruthAccumulationFrames = 0;
            groundTruthSettingsSignature = Integer.MIN_VALUE;
            groundTruthWaterWaveTime = Float.NaN;
            OfflineGroundTruth.INSTANCE.onRendererReset();
        }
        RtDlssRr.INSTANCE.requestHistoryReset();
        CausticaJitter.INSTANCE.reset();
    }

    /** True only after this render call produced fresh final color plus FG motion/depth guides. */
    public boolean hasCurrentFrameForFg() {
        return rtFrameProducedThisFrame && renderSizeFgGuides && !failed && gDepth != null && gMotion != null;
    }

    /** True at frame tail only when this render call completed a fresh DLSS Ray Reconstruction result. */
    public boolean producedFreshDlssRrFrame() {
        return rtFrameProducedThisFrame && rrProducedPreviousFrame && !failed;
    }

    /** True at frame tail when the native offline trace submitted a fresh progressive batch. */
    public boolean producedFreshOfflineFrame() {
        return rtFrameProducedThisFrame && OfflineGroundTruth.INSTANCE.active() && !failed;
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        if (OfflineGroundTruth.INSTANCE.active() && offlineCameraCaptured) {
            // Continue submitting the immutable session camera even if recoil, FOV animation, commands,
            // or another mod tries to move the live camera. Blending distinct views is never acceptable.
            frameCaptured = true;
            return;
        }
        if (hasCapturedProjection && projectionDiscontinuity(previousCapturedProjection, projection)) {
            requestTemporalReset();
        }
        frameProjection.set(projection);
        previousCapturedProjection.set(projection);
        hasCapturedProjection = true;
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
        if (OfflineGroundTruth.INSTANCE.active()) {
            offlineCameraCaptured = true;
        }
    }

    public void beginOfflineSession() {
        offlineCameraCaptured = false;
        offlineTlas = null;
        offlineLastPresentNanos = 0L;
        RtEntities.INSTANCE.beginOfflineSession();
    }

    public void endOfflineSession() {
        RtContext activeContext = RtContext.currentOrNull();
        if (activeContext != null && offlineTlas != null) {
            activeContext.waitIdle("offline scene snapshot release");
        }
        RtEntities.INSTANCE.endOfflineSession();
        offlineTlas = null;
        offlineLastPresentNanos = 0L;
        offlineCameraCaptured = false;
    }

    private static boolean projectionDiscontinuity(Matrix4fc previous, Matrix4fc current) {
        return relativeDifference(previous.m00(), current.m00()) > 0.05f
                || relativeDifference(previous.m11(), current.m11()) > 0.05f;
    }

    private static float relativeDifference(float a, float b) {
        return Math.abs(a - b) / Math.max(Math.max(Math.abs(a), Math.abs(b)), 1.0e-4f);
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — overlay raster passes ({@code dev.comfyfluffy.caustica.rt.overlay}) reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Reset per-frame present state at the very start of {@link net.minecraft.client.renderer.GameRenderer}
     * render (before any RT work). Critical for menu/no-world frames: {@link #composite()} is only called
     * while a level is rendering ({@code WorldRenderScaler} opens its window in {@code renderLevel}), so on
     * menu frames {@code composite} never runs and {@code hdrWrittenThisFrame} would otherwise keep its stale
     * {@code true} from the last world frame — presenting a black/stale HDR image behind the menu. Clearing it
     * here every frame makes {@link #isHdrPresentActive()} false on menu frames so the SDR convert-present path
     * runs instead.
     */
    public void beginFrame() {
        if (pendingTerrainGraphicsUse != 0L) {
            throw new IllegalStateException("Previous RT terrain graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        long now = System.nanoTime();
        if (!rtFrameProducedThisFrame
                || (!OfflineGroundTruth.INSTANCE.active()
                        && lastFrameBeginNanos != 0L && now - lastFrameBeginNanos > TEMPORAL_GAP_NANOS)) {
            requestTemporalReset();
        }
        lastFrameBeginNanos = now;
        rtFrameProducedThisFrame = false;
        frameCaptured = false;
        hdrWrittenThisFrame = false;
        frameCaptured = false;
        frameJitterX = 0.0f;
        frameJitterY = 0.0f;
    }

    /** Record terrain retirement completion after the frame's final TLAS consumer (world overlay). */
    public void finishTerrainGraphicsUse() {
        long graphicsUse = pendingTerrainGraphicsUse;
        if (graphicsUse == 0L) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before terrain graphics use completed");
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).caustica$getBackend();
        ctx.gpuExecutor().endGraphicsTerrainUse(encoder, graphicsUse);
        pendingTerrainGraphicsUse = 0L;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // global frame serial used by remaining per-frame/entity rings and diagnostics
        hdrWrittenThisFrame = false; // set true again below once this frame's HDR display image is written
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.gpuExecutor().throwIfFailed();
        // Count-bounded terrain streaming (dispatch/drain/build kick) runs here once per render frame — before
        // the ready gate below, because it is what MAKES terrain ready during the initial fill.
        try {
            if (!OfflineGroundTruth.INSTANCE.active()) {
                RtTerrain.frame(ctx);
            }
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT terrain streaming failed; reverting to vanilla path", t);
            return false;
        }
        if (RtTerrain.currentOrNull() == null || !frameCaptured || Minecraft.getInstance().level == null) {
            // No usable world/camera this frame. Skip RT so presentation falls back to vanilla SDR or the
            // menu-safe PQ conversion path instead of reusing stale temporal inputs.
            return false;
        }
        try {
            // Sun/moon presentation is part of the world pipeline's descriptor contract. Never substitute
            // the block atlas: if the celestials atlas is one upload behind, vanilla renders this frame and
            // RT is created only after the correct view exists.
            if (blockAtlasView() == 0L || celestialsAtlasView() == 0L) {
                return false;
            }
            if (displayPipeline == null) {
                displayPipeline = RtDisplayPipeline.create(ctx);
            }
            if (bloomPipeline == null) {
                bloomPipeline = RtBloomPipeline.create(ctx);
            }
            // A resource reload re-stitches the block and celestial atlases. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAtlasView();
                long celestialAtlas = celestialsAtlasView();
                if (!replacementAtlasesReady(atlas, celestialAtlas,
                        boundAtlasHandle, boundCelestialAtlasHandle)) {
                    return false;
                }
            }
            ensureOutput(ctx, width, height);
            // Cheap idempotent check every frame (not just on resize): if the exposure mode is switched
            // manual -> auto at runtime (video settings), the auto-mode histogram/state/pipeline must be
            // allocated before recordFrame's exposure.record() below needs them, or it throws.
            exposure.ensureResources(ctx);
            refreshPipelineShapeIfNeeded(ctx);
            boolean offlineGroundTruth = OfflineGroundTruth.INSTANCE.active();
            RtPipeline active = offlineGroundTruth ? ensureOfflineWorld(ctx) : ensureWorld(ctx);
            syncSharcResources(ctx, !offlineGroundTruth);
            syncSharcPrimaryQueryPipeline(ctx, !offlineGroundTruth);
            syncSharcDiagnosticPipelines(ctx, !offlineGroundTruth);
            syncTraceGpuProfiler(ctx);
            refreshMaterialBindingsIfNeeded(ctx);
            updateMotion();
            recordFrame(ctx, active, nativeColor);
            rtFrameProducedThisFrame = true;
            if (!loggedActive) {
                loggedActive = true;
                CausticaMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT composite failed; reverting to vanilla path", t);
            return false;
        }
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so per-prim material flags ({@code hasS}/{@code
     * hasN}) resolve from the first section. That makes PBR-on-join structural rather than relying on a
     * re-extract after the fact. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public void ensureResourcesReady(RtContext ctx) {
        if (failed || worldPipeline != null || reloadRebindRequested) {
            return;
        }
        if (Minecraft.getInstance().level == null || blockAtlasView() == 0L || celestialsAtlasView() == 0L) {
            return;
        }
        try {
            ensureWorld(ctx);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT resource bring-up failed; reverting to vanilla path", t);
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            if (blockAtlasView() == 0L || celestialsAtlasView() == 0L) {
                throw new IllegalStateException("world and celestial atlases must be ready before RT pipeline creation");
            }
            if (skyViewLut == null) {
                skyViewLut = ctx.createStorageImage(256, 256, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                        "spectral sky-view LUT");
                skyTransmittanceLut = ctx.createStorageImage(256, 64, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        "spectral transmittance LUT");
                skyViewPipeline = RtSkyViewPipeline.create(ctx);
                skyViewPipeline.setImages(skyViewLut.view, skyTransmittanceLut.view);
                skyTransmittanceReady = false;
                skyViewStateValid = false;
            }
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            worldPipeline = RtPipeline.create(ctx, RtDeviceBringup.worldRaygenShader(),
                    new String[]{"world.rmiss.spv", "world_guide.rmiss.spv"}, "world.rchit.spv", "world.rahit.spv",
                    WorldPushConstantsData.BYTE_SIZE, true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
            // Per-frame push data lives in this BDA ring; the pipeline only pushes its address.
            if (pushRing == null) {
                pushRing = new RtBuffer[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i);
                }
            }
            if (blueNoiseSequence == null) {
                blueNoiseSequence = RtBlueNoiseSequence.create(ctx);
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is rebuilt and bound per frame in recordFrame since dynamic entity content animates
        // the instance set every frame.
        return worldPipeline;
    }

    /** Create the converged renderer only on entry to offline mode; live rendering owns no Sobol buffer. */
    private RtPipeline ensureOfflineWorld(RtContext ctx) {
        ensureWorld(ctx); // establishes shared push buffers, texture registry, and bindless capacity
        if (offlinePipeline == null) {
            offlinePipeline = RtPipeline.create(ctx, RtDeviceBringup.offlineWorldRaygenShader(),
                    new String[]{"world.rmiss.spv", "world_guide.rmiss.spv"}, "world.rchit.spv", "world.rahit.spv",
                    WorldPushConstantsData.BYTE_SIZE, true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
            pathSampleSequence = RtPathSampleSequence.create(ctx);
            if (output != null) {
                offlinePipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindPipelineTextures(ctx, offlinePipeline);
        }
        return offlinePipeline;
    }

    private void destroyOfflineResources() {
        if (offlinePipeline != null) {
            offlinePipeline.destroy();
            offlinePipeline = null;
        }
        if (pathSampleSequence != null) {
            pathSampleSequence.destroy();
            pathSampleSequence = null;
        }
    }

    private boolean sharcRequested() {
        return CausticaConfig.Rt.Sharc.ENABLED.value() && RtSharcSupport.available();
    }

    /** Lazily owns every SHaRC-only allocation so disabled/offline mode retains the baseline ABI and memory use. */
    private void syncSharcResources(RtContext ctx, boolean allowed) {
        boolean want = allowed && sharcRequested();
        int exponent = CausticaConfig.Rt.Sharc.CACHE_EXPONENT.value();
        if (!want) {
            if (sharcCache != null) {
                ctx.waitIdle("disable SHaRC");
                destroySharcResources();
                sharcCreationResetReason = "enabled";
            }
            return;
        }
        if (sharcCache != null && sharcCache.exponent() == exponent) return;
        if (sharcCache != null) {
            ctx.waitIdle("resize SHaRC cache");
            destroySharcResources();
            sharcCreationResetReason = "cache size changed";
        }
        try {
            sharcUpdatePipeline = RtPipeline.create(ctx, RtDeviceBringup.sharcUpdateRaygenShader(),
                    new String[]{"world_sharc.rmiss.spv", "world_sharc_guide.rmiss.spv"},
                    "world_sharc.rchit.spv", "world_sharc.rahit.spv", SharcPushAddrData.BYTE_SIZE,
                    true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
            sharcQueryPipeline = RtPipeline.create(ctx, RtDeviceBringup.sharcQueryRaygenShader(),
                    new String[]{"world_sharc.rmiss.spv", "world_sharc_guide.rmiss.spv"},
                    "world_sharc.rchit.spv", "world_sharc.rahit.spv", SharcPushAddrData.BYTE_SIZE,
                    true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
            sharcDiffuseQueryPipeline = RtPipeline.create(ctx, RtDeviceBringup.sharcDiffuseQueryRaygenShader(),
                    new String[]{"world_sharc.rmiss.spv", "world_sharc_guide.rmiss.spv"},
                    "world_sharc.rchit.spv", "world_sharc.rahit.spv", SharcPushAddrData.BYTE_SIZE,
                    true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
            sharcResolvePipeline = RtSharcResolvePipeline.create(ctx);
            sharcCache = RtSharcCache.create(ctx, exponent);
            sharcCache.requestReset(sharcCreationResetReason);
            sharcCreationResetReason = "enabled";
            if (output != null) {
                sharcUpdatePipeline.setStorageImage(output.view);
                sharcQueryPipeline.setStorageImage(output.view);
                sharcDiffuseQueryPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindPipelineTextures(ctx, sharcUpdatePipeline);
            bindPipelineTextures(ctx, sharcQueryPipeline);
            bindPipelineTextures(ctx, sharcDiffuseQueryPipeline);
            CausticaMod.LOGGER.info("SHaRC enabled: {} entries, {} MiB, {}", sharcCache.capacity(),
                    sharcCache.bytes() / (1024 * 1024), RtSharcSupport.status());
        } catch (Throwable t) {
            destroySharcResources();
            RtSharcSupport.fail("pipeline setup failed: " + t.getClass().getSimpleName());
            RtDlssRr.INSTANCE.requestHistoryReset();
            CausticaMod.LOGGER.error("SHaRC setup failed; using baseline tracer", t);
        }
    }

    /** Lazily creates C-mode so baseline and secondary-only SHaRC pay no pipeline allocation or setup cost. */
    private void syncSharcPrimaryQueryPipeline(RtContext ctx, boolean allowed) {
        boolean want = allowed && sharcCache != null && CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE.value();
        if (!want || sharcPrimaryQueryPipeline != null) return;
        try {
            sharcPrimaryQueryPipeline = RtPipeline.create(ctx, RtDeviceBringup.sharcPrimaryQueryRaygenShader(),
                    new String[]{"world_sharc.rmiss.spv", "world_sharc_guide.rmiss.spv"},
                    "world_sharc.rchit.spv", "world_sharc.rahit.spv", SharcPushAddrData.BYTE_SIZE,
                    true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
            if (output != null) {
                sharcPrimaryQueryPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindPipelineTextures(ctx, sharcPrimaryQueryPipeline);
            RtDlssRr.INSTANCE.requestHistoryReset();
            CausticaMod.LOGGER.info("SHaRC primary-diffuse reuse pipeline ready");
        } catch (Throwable t) {
            if (sharcPrimaryQueryPipeline != null) sharcPrimaryQueryPipeline.destroy();
            sharcPrimaryQueryPipeline = null;
            CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE.set(false);
            RtDlssRr.INSTANCE.requestHistoryReset();
            CausticaMod.LOGGER.error("SHaRC primary-diffuse reuse setup failed; retaining secondary-only SHaRC", t);
        }
    }

    /** Diagnostic SHaRC shaders are large and never enter the normal render path; instantiate on demand. */
    private void syncSharcDiagnosticPipelines(RtContext ctx, boolean allowed) {
        int view = debugView();
        boolean want = allowed && sharcCache != null
                && (CausticaConfig.Rt.Sharc.DETAILED_STATS.value() || (view >= 9 && view <= 16));
        if (!want) {
            if (sharcDiagnosticUpdatePipeline != null || sharcDiagnosticQueryPipeline != null
                    || sharcPrimaryDiagnosticQueryPipeline != null) {
                ctx.waitIdle("disable SHaRC diagnostics");
                if (sharcDiagnosticUpdatePipeline != null) sharcDiagnosticUpdatePipeline.destroy();
                if (sharcDiagnosticQueryPipeline != null) sharcDiagnosticQueryPipeline.destroy();
                if (sharcPrimaryDiagnosticQueryPipeline != null) sharcPrimaryDiagnosticQueryPipeline.destroy();
                sharcDiagnosticUpdatePipeline = null;
                sharcDiagnosticQueryPipeline = null;
                sharcPrimaryDiagnosticQueryPipeline = null;
            }
            return;
        }
        if (sharcDiagnosticUpdatePipeline == null) {
            sharcDiagnosticUpdatePipeline = RtPipeline.create(ctx, RtDeviceBringup.sharcDiagnosticUpdateRaygenShader(),
                    new String[]{"world_sharc.rmiss.spv", "world_sharc_guide.rmiss.spv"},
                    "world_sharc.rchit.spv", "world_sharc.rahit.spv", SharcPushAddrData.BYTE_SIZE,
                    true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
        }
        if (sharcDiagnosticQueryPipeline == null) {
            sharcDiagnosticQueryPipeline = RtPipeline.create(ctx, RtDeviceBringup.sharcDiagnosticQueryRaygenShader(),
                    new String[]{"world_sharc.rmiss.spv", "world_sharc_guide.rmiss.spv"},
                    "world_sharc.rchit.spv", "world_sharc.rahit.spv", SharcPushAddrData.BYTE_SIZE,
                    true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
        }
        if (CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE.value()
                && sharcPrimaryDiagnosticQueryPipeline == null) {
            sharcPrimaryDiagnosticQueryPipeline = RtPipeline.create(ctx,
                    RtDeviceBringup.sharcPrimaryDiagnosticQueryRaygenShader(),
                    new String[]{"world_sharc.rmiss.spv", "world_sharc_guide.rmiss.spv"},
                    "world_sharc.rchit.spv", "world_sharc.rahit.spv", SharcPushAddrData.BYTE_SIZE,
                    true, GUIDE_COUNT, bindlessTextureCapacity, true, true);
        }
        if (output != null) {
            sharcDiagnosticUpdatePipeline.setStorageImage(output.view);
            sharcDiagnosticQueryPipeline.setStorageImage(output.view);
            if (sharcPrimaryDiagnosticQueryPipeline != null) {
                sharcPrimaryDiagnosticQueryPipeline.setStorageImage(output.view);
            }
            bindGuideImages();
        }
        bindPipelineTextures(ctx, sharcDiagnosticUpdatePipeline);
        bindPipelineTextures(ctx, sharcDiagnosticQueryPipeline);
        if (sharcPrimaryDiagnosticQueryPipeline != null) {
            bindPipelineTextures(ctx, sharcPrimaryDiagnosticQueryPipeline);
        }
    }

    private RtPipeline[] worldPipelines() {
        return new RtPipeline[]{worldPipeline, offlinePipeline, sharcUpdatePipeline, sharcQueryPipeline,
                sharcDiffuseQueryPipeline, sharcPrimaryQueryPipeline, sharcDiagnosticUpdatePipeline,
                sharcDiagnosticQueryPipeline, sharcPrimaryDiagnosticQueryPipeline};
    }

    private RtPipeline[] onlinePipelines() {
        return new RtPipeline[]{worldPipeline, sharcUpdatePipeline, sharcQueryPipeline, sharcDiffuseQueryPipeline,
                sharcPrimaryQueryPipeline, sharcDiagnosticUpdatePipeline, sharcDiagnosticQueryPipeline,
                sharcPrimaryDiagnosticQueryPipeline};
    }

    private void syncTraceGpuProfiler(RtContext ctx) {
        boolean wanted = sharcCache != null || RtFrameStats.enabled();
        if (wanted && traceGpuProfiler == null) {
            try {
                traceGpuProfiler = RtTraceGpuProfiler.create(ctx);
            } catch (Throwable t) {
                if (sharcCache != null) {
                    destroySharcResources();
                    RtSharcSupport.fail("GPU timestamp setup failed: " + t.getClass().getSimpleName());
                    RtDlssRr.INSTANCE.requestHistoryReset();
                    CausticaMod.LOGGER.error("SHaRC GPU timing setup failed; using baseline tracer", t);
                } else {
                    CausticaMod.LOGGER.warn("RT GPU timestamps unavailable; frame rendering continues", t);
                }
            }
        } else if (!wanted && traceGpuProfiler != null) {
            ctx.waitIdle("disable RT GPU timestamps");
            traceGpuProfiler.destroy();
            traceGpuProfiler = null;
        }
    }

    private void destroySharcResources() {
        if (sharcUpdatePipeline != null) sharcUpdatePipeline.destroy();
        if (sharcQueryPipeline != null) sharcQueryPipeline.destroy();
        if (sharcDiffuseQueryPipeline != null) sharcDiffuseQueryPipeline.destroy();
        if (sharcPrimaryQueryPipeline != null) sharcPrimaryQueryPipeline.destroy();
        if (sharcDiagnosticUpdatePipeline != null) sharcDiagnosticUpdatePipeline.destroy();
        if (sharcDiagnosticQueryPipeline != null) sharcDiagnosticQueryPipeline.destroy();
        if (sharcPrimaryDiagnosticQueryPipeline != null) sharcPrimaryDiagnosticQueryPipeline.destroy();
        if (sharcResolvePipeline != null) sharcResolvePipeline.destroy();
        if (sharcCache != null) sharcCache.destroy();
        sharcUpdatePipeline = null;
        sharcQueryPipeline = null;
        sharcDiffuseQueryPipeline = null;
        sharcPrimaryQueryPipeline = null;
        sharcDiagnosticUpdatePipeline = null;
        sharcDiagnosticQueryPipeline = null;
        sharcPrimaryDiagnosticQueryPipeline = null;
        sharcResolvePipeline = null;
        sharcCache = null;
        sharcTerrainX = sharcTerrainY = sharcTerrainZ = Integer.MIN_VALUE;
        sharcWorldIdentity = null;
        sharcPrimaryModeKnown = false;
    }

    private void refreshPipelineShapeIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        int desiredBindlessCapacity = RtEntityTextures.maxTextures();
        if (desiredBindlessCapacity <= bindlessTextureCapacity) {
            return;
        }
        ctx.waitIdle("bindless texture capacity growth");
        worldPipeline.destroy();
        worldPipeline = null;
        destroyOfflineResources();
        destroySharcResources();
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the LabPBR {@code _s}/{@code _n} parallel atlases (bindings 9/10). Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry and recreates the {@code _s}/{@code _n}
     * atlases at the current block-atlas size, then re-extracts all terrain ({@link RtTerrain#markAllDirty})
     * so per-prim material flags are recomputed against the (re)built atlases. On first creation this runs
     * before terrain is resident (see {@link #ensureResourcesReady}), so the re-extract is a no-op and PBR
     * is structural; on a resource reload it refreshes the flags of the already-resident terrain.
     */
    private void bindWorldTextures(RtContext ctx) {
        long sampler = atlasSampler(ctx);
        long atlasView = blockAtlasView();
        boundAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        for (RtPipeline pipeline : worldPipelines()) if (pipeline != null) pipeline.setBlockAlbedoAtlas(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtBlockMaterials.INSTANCE.reset();
        RtMaterialOverrides materialOverrides = RtMaterialOverrides.load();
        RtEmissionSemantics emissionSemantics = RtEmissionSemantics.analyze();
        RtBlockMaterials.INSTANCE.prepareAll(ctx, bindlessTextureCapacity, emissionSemantics, materialOverrides);
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity);
        for (RtPipeline pipeline : worldPipelines()) if (pipeline != null) {
            pipeline.setEntityAlbedoTexture(0, atlasView, sampler);
            RtBlockMaterials.INSTANCE.bindPages(pipeline, sampler);
        }
        RtMaterialRegistry.INSTANCE.rebuild(ctx, RtBlockMaterials.INSTANCE, materialOverrides);
        // LabPBR _s + _n parallel atlases. Bind the (block-atlas-sized) atlases; their pixels fill
        // lazily as terrain extraction encounters sprites and refresh via flush(). Fall back to the block
        // atlas view if an atlas didn't initialize so bindings 9/10 always hold a valid descriptor —
        // the shader only samples them when a prim is flagged (mat.z/mat.w), so the fallback is never read.
        materialBindingsReady = true;
        // Bind the real vanilla celestials atlas (sun + moon phases). Pipeline creation is gated on this
        // view, so an upload race cannot silently turn the block atlas into the sky atlas for the session.
        long celView = celestialsAtlasView();
        if (celView == 0L) throw new IllegalStateException("celestials atlas unavailable during world binding");
        boundCelestialAtlasHandle = celView;
        if (worldPipeline.hasSkyAtlas()) {
            for (RtPipeline pipeline : worldPipelines()) if (pipeline != null) {
                pipeline.setSkyAtlas(celView, sampler);
                pipeline.setSkyViewLut(skyViewLut.view);
            }
        }
        setCelestialUvAtlas(celView);
        RtTerrain.requestFullClear();
    }

    /** Bind the existing stable texture registry into one newly-created specialized pipeline. */
    private void bindPipelineTextures(RtContext ctx, RtPipeline pipeline) {
        long sampler = atlasSampler(ctx);
        long atlasView = blockAtlasView();
        pipeline.setBlockAlbedoAtlas(atlasView, sampler);
        pipeline.setEntityAlbedoTexture(0, atlasView, sampler);
        RtBlockMaterials.INSTANCE.bindPages(pipeline, sampler);
        if (pipeline.hasSkyAtlas()) {
            long celestialView = celestialsAtlasView();
            if (celestialView == 0L) throw new IllegalStateException("celestials atlas unavailable during pipeline binding");
            pipeline.setSkyAtlas(celestialView, sampler);
            pipeline.setSkyViewLut(skyViewLut.view);
        }
        RtEntityTextures.INSTANCE.bindAll(sampler, pipeline);
    }

    private void refreshMaterialBindingsIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        if (!materialBindingsReady) {
            bindWorldTextures(ctx);
        }
    }

    /** Vulkan image-view of the vanilla celestials atlas (sun + moon-phase sprites), or 0 if unavailable. */
    private static long celestialsAtlasView() {
        try {
            GpuTextureView view = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS).getTextureView();
            return vkImageView(view);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Hooked at the HEAD of {@link net.minecraft.client.Minecraft#reloadResourcePacks()} (mixin). A
     * resource reload re-stitches the block atlas (and reloads entity textures): MC frees the old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so MC can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). Terrain stays resident and is re-extracted via
     * {@code markAllDirty()} so material flags pick up the new pack.
     */
    public void onResourceReloadStart() {
        if (OfflineGroundTruth.INSTANCE.active()) {
            OfflineGroundTruth.INSTANCE.abort("Offline renderer stopped: resources reloaded");
        }
        requestTemporalReset();
        reloadRebindRequested = true;
        sharcCreationResetReason = "resource pack/material reload";
        materialBindingsReady = false;
        setCelestialUvAtlas(0L);
        RtEntities.INSTANCE.onResourceReload();
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null && worldPipeline != null) {
            ctx.waitIdle("resource reload");
            worldPipeline.destroy();
            worldPipeline = null;
            destroyOfflineResources();
            destroySharcResources();
            bindlessTextureCapacity = 0;
        }
    }

    /** Bind the guide buffers into the world pipeline's extra storage-image slots. */
    private void bindGuideImages() {
        if (gNormal != null) {
            for (RtPipeline pipeline : onlinePipelines()) if (pipeline != null) {
                pipeline.setExtraStorageImage(0, gNormal.view);
                pipeline.setExtraStorageImage(1, gAlbedo.view);
                pipeline.setExtraStorageImage(2, gDepth.view);
                pipeline.setExtraStorageImage(3, gMotion.view);
                pipeline.setExtraStorageImage(4, gSpecAlbedo.view);
                pipeline.setExtraStorageImage(5, gSpecMotion.view);
                pipeline.setExtraStorageImage(7, gAnimatedGuide.view);
                pipeline.setExtraStorageImage(10, gDiffuseRayDirectionHitDistance.view);
            }
        }
        if (offlinePipeline != null && groundTruthAccum != null
                && offlinePilotA != null && offlinePilotB != null) {
            offlinePipeline.setExtraStorageImage(6, groundTruthAccum.view);
            offlinePipeline.setExtraStorageImage(8, offlinePilotA.view);
            offlinePipeline.setExtraStorageImage(9, offlinePilotB.view);
        }
    }

    private void destroyGuideImages() {
        if (dlssdDisocclusionPipeline != null) {
            dlssdDisocclusionPipeline.destroy();
            dlssdDisocclusionPipeline = null;
        }
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (gSpecMotion != null) {
            gSpecMotion.destroy();
            gSpecMotion = null;
        }
        if (gAnimatedGuide != null) {
            gAnimatedGuide.destroy();
            gAnimatedGuide = null;
        }
        if (gDiffuseRayDirectionHitDistance != null) {
            gDiffuseRayDirectionHitDistance.destroy();
            gDiffuseRayDirectionHitDistance = null;
        }
        if (gDepthHistoryA != null) {
            gDepthHistoryA.destroy();
            gDepthHistoryA = null;
        }
        if (gDepthHistoryB != null) {
            gDepthHistoryB.destroy();
            gDepthHistoryB = null;
        }
        if (gDisocclusion != null) {
            gDisocclusion.destroy();
            gDisocclusion = null;
        }
        if (gBiasCurrentColor != null) {
            gBiasCurrentColor.destroy();
            gBiasCurrentColor = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
        if (groundTruthAccum != null) {
            groundTruthAccum.destroy();
            groundTruthAccum = null;
        }
        if (offlinePilotA != null) {
            offlinePilotA.destroy();
            offlinePilotA = null;
        }
        if (offlinePilotB != null) {
            offlinePilotB.destroy();
            offlinePilotB = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        boolean offlineGroundTruth = OfflineGroundTruth.INSTANCE.active();
        if (offlineGroundTruth && renderSizeGroundTruth && displayW > 0
                && (displayW != width || displayH != height)) {
            OfflineGroundTruth.INSTANCE.abort("Offline renderer stopped: resolution changed");
            offlineGroundTruth = false;
        }
        boolean rrOperational = !offlineGroundTruth && RtDlssRr.INSTANCE.isOperational();
        int rrQuality = rrOperational ? RtDlssRr.quality() : Integer.MIN_VALUE;
        boolean fgGuidesRequired = !offlineGroundTruth && RtDlssFg.requested();
        boolean autoExposure = !offlineGroundTruth
                && "auto".equalsIgnoreCase(CausticaConfig.Rt.Exposure.MODE.get());
        boolean exposureDepthRequired = exposureDepthRequired(autoExposure, rrOperational, fgGuidesRequired);
        boolean hdrEnabled = RtHdr.effective();
        if (output != null && displayImage != null && hdrDisplayImage != null && exposure.ready()
                && bloomHalf != null && bloomQuarter != null && bloomEighth != null
                && (!renderSizeRrEnabled || rrOutput != null)
                && displayW == width && displayH == height
                && renderSizeRrEnabled == rrOperational && renderSizeRrQuality == rrQuality
                && renderSizeFgGuides == fgGuidesRequired && renderSizeHdrEnabled == hdrEnabled
                && renderSizeExposureDepth == exposureDepthRequired
                && renderSizeGroundTruth == offlineGroundTruth
                && (!offlineGroundTruth
                    || (groundTruthAccum != null && offlinePilotA != null && offlinePilotB != null))) {
            return;
        }
        ctx.waitIdle("output resize or mode change"); // no in-flight frame may use old images/descriptors
        if (offlineGroundTruth && !renderSizeGroundTruth) {
            exposure.beginOfflineSession(ctx);
        } else if (!offlineGroundTruth && renderSizeGroundTruth) {
            exposure.endOfflineSession();
            destroyOfflineResources();
        }
        // Release Streamline's viewport before any tagged input/output image is destroyed or resized.
        // This also promptly frees RR when the setting is switched off instead of retaining its feature
        // allocation until device shutdown.
        RtDlssRr.INSTANCE.destroy();
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        destroyBloomImages();
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at render res; DLSS-RR (or a fallback blit) upscales
        // to display res. With RR off there is no reconstruction pass, so trace at 1:1 for a faithful reference.
        // With RR on, ask the Streamline RR plugin what render resolution its chosen quality mode expects
        // than assuming a fixed ratio: different quality modes (and driver versions) use different
        // ratios, and DLSSD's own optimal-settings query is the source of truth for what it will accept.
        int[] optimal = rrOperational ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        rrOperational = optimal != null;
        rrQuality = rrOperational ? RtDlssRr.quality() : Integer.MIN_VALUE;
        exposureDepthRequired = exposureDepthRequired(autoExposure, rrOperational, fgGuidesRequired);
        renderW = optimal != null ? optimal[0] : width;
        renderH = optimal != null ? optimal[1] : height;
        renderSizeRrEnabled = rrOperational;
        renderSizeRrQuality = rrQuality;
        renderSizeFgGuides = fgGuidesRequired;
        renderSizeExposureDepth = exposureDepthRequired;
        renderSizeHdrEnabled = hdrEnabled;
        renderSizeGroundTruth = offlineGroundTruth;
        if (offlineGroundTruth) {
            groundTruthAccumulationFrames = 0;
            groundTruthWaterWaveTime = Float.NaN;
            OfflineGroundTruth.INSTANCE.onRendererReset();
        }

        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the display
        // mapping seam. displayImage stays R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // Bind a tiny format-compatible target while HDR is off; display.comp does not access it unless the
        // HDR flag is set. A settings toggle recreates a full-resolution image before enabling the write.
        int hdrW = hdrEnabled ? width : 1;
        int hdrH = hdrEnabled ? height : 1;
        hdrDisplayImage = ctx.createStorageImage(hdrW, hdrH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                hdrEnabled ? "RT HDR display image " + width + "x" + height : "inactive HDR display image");
        int bloomHalfW = Math.max(1, (width + 1) / 2);
        int bloomHalfH = Math.max(1, (height + 1) / 2);
        int bloomQuarterW = Math.max(1, (bloomHalfW + 1) / 2);
        int bloomQuarterH = Math.max(1, (bloomHalfH + 1) / 2);
        int bloomEighthW = Math.max(1, (bloomQuarterW + 1) / 2);
        int bloomEighthH = Math.max(1, (bloomQuarterH + 1) / 2);
        bloomHalf = ctx.createStorageImage(bloomHalfW, bloomHalfH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "HDR glare half resolution");
        bloomQuarter = ctx.createStorageImage(bloomQuarterW, bloomQuarterH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "HDR glare quarter resolution");
        bloomEighth = ctx.createStorageImage(bloomEighthW, bloomEighthH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "HDR glare eighth resolution");

        // Descriptors must remain valid even when their consumer is off. Allocate full-sized guide images
        // only for RR, or depth/motion for FG; the shader skips writes to the 1x1 inactive bindings.
        boolean motionGuidesRequired = rrOperational || fgGuidesRequired;
        int rrGuideW = rrOperational ? renderW : 1;
        int rrGuideH = rrOperational ? renderH : 1;
        int motionGuideW = motionGuidesRequired ? renderW : 1;
        int motionGuideH = motionGuidesRequired ? renderH : 1;
        int depthGuideW = (motionGuidesRequired || exposureDepthRequired) ? renderW : 1;
        int depthGuideH = (motionGuidesRequired || exposureDepthRequired) ? renderH : 1;
        gNormal = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness");
        gAlbedo = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo");
        gDepth = ctx.createStorageImage(depthGuideW, depthGuideH, VK10.VK_FORMAT_R32_SFLOAT, "guide depth");
        gMotion = ctx.createStorageImage(motionGuideW, motionGuideH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion");
        gSpecAlbedo = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo");
        gSpecMotion = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion");
        if (offlineGroundTruth) {
            groundTruthAccum = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                    "offline ground-truth FP32 radiance mean " + width + "x" + height);
            int pilotW = (width + 7) / 8;
            int pilotH = (height + 7) / 8;
            offlinePilotA = ctx.createStorageImage(pilotW, pilotH, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                    "offline adaptive pilot A " + pilotW + "x" + pilotH);
            offlinePilotB = ctx.createStorageImage(pilotW, pilotH, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                    "offline adaptive pilot B " + pilotW + "x" + pilotH);
        }
        gAnimatedGuide = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R16_SFLOAT,
                "DLSSD animated-surface responsivity");
        gDiffuseRayDirectionHitDistance = ctx.createStorageImage(rrGuideW, rrGuideH,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSSD diffuse ray direction + hit distance");
        gDepthHistoryA = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R32_SFLOAT,
                "DLSSD depth history A");
        gDepthHistoryB = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R32_SFLOAT,
                "DLSSD depth history B");
        gDisocclusion = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R16_SFLOAT,
                "DLSSD disocclusion mask");
        gBiasCurrentColor = ctx.createStorageImage(rrGuideW, rrGuideH, VK10.VK_FORMAT_R16_SFLOAT,
                "DLSSD current color bias");
        if (rrOperational) {
            dlssdDisocclusionPipeline = RtDlssdDisocclusionPipeline.create(ctx);
            dlssdDisocclusionPipeline.setImages(gDepth.view, gMotion.view,
                    gDepthHistoryA.view, gDepthHistoryB.view, gDisocclusion.view, gBiasCurrentColor.view,
                    gAnimatedGuide.view);
        }
        // At native resolution the display mapper can consume the trace image directly; reserve a second
        // full-resolution FP16 image only when RR may write or need a same-frame fallback upscale.
        rrOutput = rrOperational
                ? ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        "DLSS-RR output " + width + "x" + height)
                : null;
        exposure.ensureResources(ctx);
        exposure.resetAutoHistory(); // tile coordinates and trusted-history identity changed with these images

        requestTemporalReset(); // recreated images -> every temporal consumer starts from a clean frame
        if (worldPipeline != null) {
            for (RtPipeline pipeline : worldPipelines()) if (pipeline != null) pipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        // The display pipeline consumes blue noise directly, so output creation must not depend on the
        // client-tick world prewarm having happened first. Startup ordering can legitimately reach the
        // first composite before that tick (for example after an automatic world load).
        if (blueNoiseSequence == null) {
            blueNoiseSequence = RtBlueNoiseSequence.create(ctx);
        }
        RtImage displayInput = rrOutput != null ? rrOutput : output;
        bloomPipeline.setImages(displayInput.view, exposure.image().view,
                bloomHalf.view, bloomQuarter.view, bloomEighth.view);
        displayPipeline.setImages(displayImage.view, displayInput.view, exposure.image().view,
                hdrDisplayImage.view, blueNoiseSequence.buffer(), bloomHalf.view);
    }

    private void destroyBloomImages() {
        if (bloomHalf != null) {
            bloomHalf.destroy();
            bloomHalf = null;
        }
        if (bloomQuarter != null) {
            bloomQuarter.destroy();
            bloomQuarter = null;
        }
        if (bloomEighth != null) {
            bloomEighth.destroy();
            bloomEighth = null;
        }
    }

    static boolean exposureDepthRequired(boolean autoExposure, boolean rrOperational, boolean fgGuidesRequired) {
        return autoExposure && !rrOperational && !fgGuidesRequired;
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion() {
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        double dx = camX - mvPrevCamX;
        double dy = camY - mvPrevCamY;
        double dz = camZ - mvPrevCamZ;
        if (mvHasPrev && dx * dx + dy * dy + dz * dz > TEMPORAL_TELEPORT_DISTANCE_SQ) {
            requestTemporalReset();
        }
        if (mvHasPrev) {
            fgPreviousViewProjection.set(mvPrevProjView);
            fgPreviousViewProjectionValid = true;
            mvPushMatrix.set(mvPrevProjView);
            mvFgPrevProjView.set(mvPrevProjView);
            mvCamDeltaX = (float) dx;
            mvCamDeltaY = (float) dy;
            mvCamDeltaZ = (float) dz;
        } else {
            fgPreviousViewProjection.set(mvCurProjView);
            fgPreviousViewProjectionValid = false;
            mvPushMatrix.set(mvCurProjView);
            mvFgPrevProjView.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = camX;
        mvPrevCamY = camY;
        mvPrevCamZ = camZ;
        mvHasPrev = true;
    }

    private void recordFrame(RtContext ctx, RtPipeline active, GpuTexture nativeColor) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // Jitter is suppressed for the no-RR reference and for the debug guide views (raw inspection).
            boolean offlineGroundTruth = OfflineGroundTruth.INSTANCE.active();
            int debugView = debugView();
            int worldDebugView = offlineGroundTruth ? 0
                    : debugView == CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON
                    ? 0 : debugView;
            if (worldDebugView >= 9 && sharcCache == null) worldDebugView = 0;
            float emissiveIntensity = CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.value();
            boolean emissiveIntensityChanged = !Float.isNaN(lastEmissiveIntensity)
                    && Float.floatToIntBits(emissiveIntensity) != Float.floatToIntBits(lastEmissiveIntensity);
            if (emissiveIntensityChanged) {
                // Lighting changed discontinuously. Do not let RR/FG retain history from the previous
                // radiance scale, which made live intensity edits appear ineffective or respond slowly.
                fgReset = true;
                rrProducedPreviousFrame = false;
                RtDlssRr.INSTANCE.requestHistoryReset();
                requestSharcReset("emissive radiance changed");
                exposure.resetAutoHistory();
            }
            lastEmissiveIntensity = emissiveIntensity;
            if (lastDebugView != Integer.MIN_VALUE && debugView != lastDebugView) {
                fgReset = true;
                rrProducedPreviousFrame = false;
                RtDlssRr.INSTANCE.requestHistoryReset();
            }
            lastDebugView = debugView;
            int settingsSignature = groundTruthSettingsSignature();
            if (offlineGroundTruth && settingsSignature != groundTruthSettingsSignature) {
                groundTruthAccumulationFrames = 0;
                groundTruthWaterWaveTime = Float.NaN;
                groundTruthSettingsSignature = settingsSignature;
                OfflineGroundTruth.INSTANCE.onRendererReset();
            } else if (!offlineGroundTruth) {
                groundTruthSettingsSignature = Integer.MIN_VALUE;
            }
            if (offlineGroundTruth) {
            }
            if (offlineGroundTruth && groundTruthAccumulationFrames == 0) {
                clearOfflineAccumulation(cmd, stack);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            boolean rrPath = !offlineGroundTruth && renderSizeRrEnabled
                    && RtDlssRr.INSTANCE.isOperational() && worldDebugView == 0;
            boolean diffusePathGuide = rrPath && CausticaConfig.Rt.DlssRr.DIFFUSE_PATH_GUIDE.value();
            if (diffusePathGuideKnown && diffusePathGuide != lastDiffusePathGuide) {
                RtDlssRr.INSTANCE.requestHistoryReset();
            }
            diffusePathGuideKnown = true;
            lastDiffusePathGuide = diffusePathGuide;
            if (rrPath && !rrProducedPreviousFrame) {
                RtDlssRr.INSTANCE.requestHistoryReset();
            }
            rrProducedPreviousFrame = false;
            if (rrPath) {
                // Validate/create the feature before choosing jitter so setup failure produces an unjittered
                // fallback frame; the next frame will resize the trace path to native resolution.
                rrPath = RtDlssRr.INSTANCE.ensureFeature(
                        cmd.address(), renderW, renderH, displayW, displayH);
            }
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                CausticaJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }
            frameJitterX = jitterX;
            frameJitterY = jitterY;

            boolean rrDone = false;
            RtTerrain terrain = RtTerrain.currentOrNull();
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            RtBuffer pushBuf = pushRing[pushSlot];
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            // flags: PBR BRDF (bit 1, always on) + camera-in-water (so the path tracer starts in the water
            // medium when the eye is submerged, fixing the air→water first-segment orientation).
            int flags = 0b10;
            var level = Minecraft.getInstance().level;
            if (level != null) {
                cameraBlockPos.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
                // Height-aware, mirroring vanilla's own Camera.getFluidInCamera(): a plain block-granular
                // test wrongly flags the eye submerged anywhere in a water column's top block, even well
                // above its actual surface (shallow/flowing water, or standing with your head just over a
                // source block).
                FluidState fs = level.getFluidState(cameraBlockPos);
                if (fs.is(FluidTags.WATER) && camY < cameraBlockPos.getY() + fs.getHeight(level, cameraBlockPos)) {
                    flags |= 0b01;
                }
                if (Level.OVERWORLD.equals(level.dimension())) {
                    flags |= FRAME_FLAG_EARTH_ATMOSPHERE;
                }
            }
            if (waterWaves()) {
                flags |= 0b10000; // W1: animated water wave normals
            }
            if (rrPath) {
                flags |= FRAME_FLAG_RR_GUIDES;
                if (diffusePathGuide) {
                    flags |= FRAME_FLAG_DIFFUSE_PATH_GUIDE;
                }
            }
            if (renderSizeFgGuides && RtDlssFg.requested()) {
                flags |= FRAME_FLAG_FG_GUIDES;
            }
            if (renderSizeExposureDepth) {
                flags |= FRAME_FLAG_EXPOSURE_DEPTH;
            }
            if (offlineGroundTruth) {
                flags |= FRAME_FLAG_OFFLINE_GROUND_TRUTH;
                if (OfflineGroundTruth.INSTANCE.benchmarking()) {
                    flags |= FRAME_FLAG_OFFLINE_CALIBRATING;
                }
            }

            // W1/W2 water parameters: camera-biome tint plus continuous animation time. Per-water-body tint
            // comes from the primitive; this is the fallback for a camera already inside the medium.
            float wtr = 0.25f, wtg = 0.46f, wtb = 0.9f; // neutral ocean-ish default if no level/biome
            if (level != null) {
                int wc = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
                wtr = ((wc >> 16) & 0xFF) / 255f;
                wtg = ((wc >> 8) & 0xFF) / 255f;
                wtb = (wc & 0xFF) / 255f;
            }
            float waterWaveTime = (float) ((System.nanoTime() - waterWaveEpochNanos) / 1.0e9);
            if (offlineGroundTruth) {
                if (Float.isNaN(groundTruthWaterWaveTime)) {
                    groundTruthWaterWaveTime = waterWaveTime;
                }
                waterWaveTime = groundTruthWaterWaveTime;
            } else {
                groundTruthWaterWaveTime = Float.NaN;
            }
            float previousWaveTime = previousWaterWaveTimeValid ? previousWaterWaveTime : waterWaveTime;
            previousWaterWaveTime = waterWaveTime;
            previousWaterWaveTimeValid = true;
            Float4 waterTint = linearBt2020FromRgb(wtr, wtg, wtb);
            Float4 waterParams = new Float4(waterTint.x(), waterTint.y(), waterTint.z(), waterWaveTime);
            // W1 wave-domain anchor: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so the
            // ripple pattern stays fixed in the world as the player moves and the rebase origin shifts.
            // z carries the previous frame's phase for animated-water reflection reprojection.
            Float4 waterAnchor = new Float4(terrain.blockX & WATER_ANCHOR_MASK,
                    terrain.blockZ & WATER_ANCHOR_MASK, previousWaveTime, 0f);

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired terrain geometry/table
            // generations are reclaimed by graphics-timeline completion.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. geomTableAddr
            // feeds the hit shader entity path (per-prim normal/tint) and motion vectors.
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, terrain.staticInstances(),
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            BreakEntry[] breaking = breakingEntries(terrain);
            SkyPush liveSky = skyPush();
            SkyPush sky;
            if (offlineGroundTruth) {
                // Progressive ground truth is one frozen scene. Accumulating live day/night motion into the
                // same running mean creates deterministic sky trails that more samples can never remove.
                if (offlineSkyPush == null || groundTruthAccumulationFrames == 0) offlineSkyPush = liveSky;
                sky = offlineSkyPush;
            } else {
                offlineSkyPush = null;
                sky = liveSky;
            }
            new WorldPushData(
                    frameInvViewProj,
                    new Float3((float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                            (float) (camZ - terrain.blockZ)),
                    terrain.tableAddress(),
                    worldDebugView,
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    fe.geomTableAddr(),
                    flags,
                    maxBounces(),
                    sky.sunDir(),
                    sky.lightDir(),
                    sky.lightRadiance(),
                    sky.moonDir(),
                    sky.celestial(),
                    sky.celestialRadii(),
                    sky.moonUv(),
                    waterParams,
                    waterAnchor,
                    mvCurProjView,
                    breaking.length,
                    emissiveIntensity,
                    CausticaConfig.Rt.Composite.PSR_MAX_MIRRORS.value(),
                    breaking,
                    offlineGroundTruth && pathSampleSequence != null
                            ? pathSampleSequence.deviceAddress() : blueNoiseSequence.deviceAddress(),
                    sky.environmentSky(),
                    sky.skyState(),
                    sky.skyLighting(),
                    sky.borderFogColor(),
                    sky.borderFogParams()
            ).write(push);
            if (skyViewPipeline != null && skyViewLut != null) {
                if (!skyTransmittanceReady) {
                    skyViewPipeline.dispatchTransmittance(cmd,
                            skyTransmittanceLut.width, skyTransmittanceLut.height);
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    skyTransmittanceReady = true;
                }
                float litFraction = sky.skyLighting().w();
                float solarEnvelope = sky.skyState().w();
                float lunarSource = (0.25f / 120_000.0f) * sky.skyLighting().y()
                        * litFraction * (1.0f - solarEnvelope) * sky.sunDir().w();
                boolean earthAtmosphere = Level.OVERWORLD.equals(level.dimension());
                float solarSource = solarEnvelope * sky.skyLighting().x() * sky.sunDir().w();
                if (skyViewChanged(sky.sunDir().x(), sky.sunDir().y(), sky.sunDir().z(), solarSource,
                        sky.moonDir().x(), sky.moonDir().y(), sky.moonDir().z(), lunarSource,
                        earthAtmosphere)) {
                    skyViewPipeline.dispatchSky(cmd, skyViewLut.width, skyViewLut.height,
                            sky.sunDir().x(), sky.sunDir().y(), sky.sunDir().z(), solarSource,
                            sky.moonDir().x(), sky.moonDir().y(), sky.moonDir().z(), lunarSource,
                            earthAtmosphere);
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                }
            }
            // Upload any entity textures registered this frame into the bindless set before the trace.
            boolean buildOfflineSnapshot = offlineGroundTruth && offlineTlas == null;
            if (!offlineGroundTruth || buildOfflineSnapshot) {
                RtFrameStats.FRAME.count("entityTextureSlots", RtEntityTextures.INSTANCE.usedSlots());
                RtFrameStats.FRAME.count("entityTexturePending", RtEntityTextures.INSTANCE.pendingUploads());
                RtEntityTextures.INSTANCE.uploadPending(atlasSampler(ctx), worldPipelines());
            // Re-upload the LabPBR _s atlas if extraction added sprites since the last frame (the
            // view handle is stable, so no re-bind needed). Before the trace records, like uploadPending.
            }
            // Build the entity BLAS this frame, then the TLAS that references them (+ the already-built
            // terrain BLAS), then the trace — each separated by a barrier. The frame TLAS is retired
            // KEEP_FRAMES later (entity meshes/BLAS are retired by RtEntities on the same horizon).
            if (traceGpuProfiler != null) traceGpuProfiler.beginFrame(cmd,
                    sharcCache != null && !offlineGroundTruth, RtFrameStats.enabled());
            if ((!offlineGroundTruth || buildOfflineSnapshot) && !fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blasRecord")) {
                    RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            if (traceGpuProfiler != null) traceGpuProfiler.blasEnd(cmd);
            RtAccel.PreparedTlas frameTlas = offlineGroundTruth ? offlineTlas : null;
            if (frameTlas == null) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                    frameTlas = RtAccel.prepareTlas(ctx, fe.baseInstances(), fe.dynamicInstances(), tlasRing);
                }
            }
            for (RtPipeline pipeline : worldPipelines()) if (pipeline != null) pipeline.setTlas(frameTlas.accel.handle);
            currentTlasHandle = frameTlas.accel.handle;
            if (offlineGroundTruth) {
                boolean evenPilotFrame = (groundTruthAccumulationFrames & 1) == 0;
                active.setCurrentExtraStorageImage(8, evenPilotFrame ? offlinePilotA.view : offlinePilotB.view);
                active.setCurrentExtraStorageImage(9, evenPilotFrame ? offlinePilotB.view : offlinePilotA.view);
            }
            if (!offlineGroundTruth || buildOfflineSnapshot) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                    RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace
            }
            if (traceGpuProfiler != null) traceGpuProfiler.tlasEnd(cmd);
            if (buildOfflineSnapshot) {
                offlineTlas = frameTlas;
            }

            // Push the BDA ring slot's address plus the small hot subset that rchit/rahit read on every
            // hit (tableAddr/entityTableAddr/frameIndex) as real inline push constants, so those lookups
            // don't pay for a second global-memory dereference through pcAddr.worldPushAddr first.
            if (sharcCache != null && !offlineGroundTruth) {
                float sceneScale = CausticaConfig.Rt.Sharc.SCENE_SCALE.value();
                float radianceScale = CausticaConfig.Rt.Sharc.RADIANCE_SCALE.value();
                if (level != sharcWorldIdentity) {
                    if (sharcWorldIdentity != null) sharcCache.requestReset("world or dimension changed");
                    sharcWorldIdentity = level;
                }
                if (terrain.blockX != sharcTerrainX || terrain.blockY != sharcTerrainY || terrain.blockZ != sharcTerrainZ) {
                    if (sharcTerrainX != Integer.MIN_VALUE) sharcCache.requestReset("terrain origin rebased");
                    sharcTerrainX = terrain.blockX; sharcTerrainY = terrain.blockY; sharcTerrainZ = terrain.blockZ;
                }
                if (!Float.isNaN(sharcSceneScale)
                        && Float.floatToIntBits(sceneScale) != Float.floatToIntBits(sharcSceneScale)) {
                    sharcCache.requestReset("scene scale changed");
                }
                sharcSceneScale = sceneScale;
                // Radiance scale changes the cache encoding/decoding contract; retaining old packed values
                // would reinterpret their energy. This targeted reset is intentionally not a broad signature.
                if (!Float.isNaN(sharcRadianceScale)
                        && Float.floatToIntBits(radianceScale) != Float.floatToIntBits(sharcRadianceScale)) {
                    sharcCache.requestReset("radiance encoding scale changed");
                }
                sharcRadianceScale = radianceScale;
                RtSharcCache.Frame sharcFrame = sharcCache.beginFrame(frameCounter,
                        (float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                        (float) (camZ - terrain.blockZ), renderW, renderH);
                publishSharcStats(sharcFrame.previousStats());
                if (sharcCache.recordPendingClear(cmd, stack)) {
                    CausticaMod.LOGGER.info("SHaRC cache reset #{}: {}", sharcCache.resetCount(),
                            sharcCache.lastResetReason());
                }
                ByteBuffer sharcPush = stack.malloc(SharcPushAddrData.BYTE_SIZE);
                new SharcPushAddrData(pushBuf.deviceAddress, terrain.tableAddress(), fe.geomTableAddr(),
                        RtMaterialRegistry.INSTANCE.tableAddress(), (int) frameCounter, worldDebugView,
                        sharcFrame.address()).write(sharcPush);
                boolean sharcDiagnostics = CausticaConfig.Rt.Sharc.DETAILED_STATS.value()
                        || (worldDebugView >= 9 && worldDebugView <= 16);
                boolean sharcPrimaryMode = CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE.value()
                        && sharcPrimaryQueryPipeline != null;
                if (sharcPrimaryModeKnown && sharcPrimaryMode != lastSharcPrimaryMode) {
                    RtDlssRr.INSTANCE.requestHistoryReset();
                }
                sharcPrimaryModeKnown = true;
                lastSharcPrimaryMode = sharcPrimaryMode;
                RtPipeline updatePipeline = sharcDiagnostics ? sharcDiagnosticUpdatePipeline : sharcUpdatePipeline;
                RtPipeline queryPipeline = sharcPrimaryMode
                        ? sharcDiagnostics && sharcPrimaryDiagnosticQueryPipeline != null
                                ? sharcPrimaryDiagnosticQueryPipeline : sharcPrimaryQueryPipeline
                        : sharcDiagnostics ? sharcDiagnosticQueryPipeline
                        : CausticaConfig.Rt.Sharc.GLOSSY_QUERY.value()
                        || !CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.value()
                        ? sharcQueryPipeline : sharcDiffuseQueryPipeline;
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "SHaRC sparse update");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.sharcUpdate")) {
                    int updateTileSize = CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.value();
                    updatePipeline.trace(cmd, (renderW + updateTileSize - 1) / updateTileSize,
                            (renderH + updateTileSize - 1) / updateTileSize, sharcPush);
                }
                if (traceGpuProfiler != null) traceGpuProfiler.updateEnd(cmd);
                sharcCache.updateToResolveBarrier(cmd, stack);
                try (RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.sharcResolve")) {
                    sharcResolvePipeline.dispatch(cmd, sharcFrame.address(), sharcCache.capacity());
                }
                if (traceGpuProfiler != null) traceGpuProfiler.resolveEnd(cmd);
                sharcCache.resolveToQueryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "SHaRC full query");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.sharcQuery")) {
                    queryPipeline.trace(cmd, renderW, renderH, sharcPush);
                }
                if (traceGpuProfiler != null) traceGpuProfiler.queryEnd(cmd);
            } else {
                ByteBuffer pushAddr = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
                new WorldPushConstantsData(pushBuf.deviceAddress, terrain.tableAddress(), fe.geomTableAddr(),
                        RtMaterialRegistry.INSTANCE.tableAddress(), (int) frameCounter, worldDebugView).write(pushAddr);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world trace");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.trace")) {
                    active.trace(cmd, renderW, renderH, pushAddr);
                }
                if (traceGpuProfiler != null) traceGpuProfiler.baselineEnd(cmd);
            }
            if (offlineGroundTruth) {
                groundTruthAccumulationFrames++;
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
            // DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput.
            if (rrPath) {
                boolean resetTemporal = !fgPreviousViewProjectionValid || emissiveIntensityChanged;
                dlssdDisocclusionPipeline.dispatch(cmd, renderW, renderH, resetTemporal, frameCounter);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                if (traceGpuProfiler != null) traceGpuProfiler.disocclusionEnd(cmd);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.dlssRr")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, gSpecMotion, gDisocclusion, gBiasCurrentColor,
                            gDiffuseRayDirectionHitDistance, rrOutput,
                            renderW, renderH, displayW, displayH,
                            jitterX, jitterY, frameProjection, mvCurProjView,
                            fgPreviousViewProjectionValid ? fgPreviousViewProjection : mvCurProjView,
                            frameViewRotation, camX, camY, camZ,
                            mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ,
                            resetTemporal);
                }
            } else if (traceGpuProfiler != null) {
                traceGpuProfiler.disocclusionEnd(cmd);
            }
            RtDlssRr.INSTANCE.recordFallback(rrPath, rrDone);

            // RR-sized resources retain a display-resolution fallback target. Native-resolution RT feeds the
            // display mapper directly, avoiding an otherwise redundant full-frame FP16 copy.
            RtImage displayInput = output;
            if (rrOutput != null && !rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, output, rrOutput);
                }
            }
            if (traceGpuProfiler != null) traceGpuProfiler.reconstructionEnd(cmd);
            rrProducedPreviousFrame = rrDone;
            if (rrOutput != null) {
                displayInput = rrOutput;
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // trace + reconstructed display input visible

            boolean screenshotRequested = Minecraft.getInstance().options.keyScreenshot.isDown();
            boolean refreshDisplay = !offlineGroundTruth || screenshotRequested || offlineLastPresentNanos == 0L
                    || System.nanoTime() - offlineLastPresentNanos >= OFFLINE_PRESENT_INTERVAL_NANOS;
            // Meter the canonical pre-reconstruction scene-linear trace in every renderer. DLSS-D may alter
            // local radiance statistics while demodulating/reconstructing; letting that proprietary output
            // drive the camera caused daylight to read near 2^-14 and climb to the exposure ceiling. The
            // resulting exposure is still applied after reconstruction by display.comp, so DLSS-D receives
            // neutral inputs while Offline and realtime share one physical camera domain.
            if (refreshDisplay) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                    exposure.record(ctx, cmd, stack, output, gDepth, offlineGroundTruth);
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // exposure image visible to the display mapper
                if (traceGpuProfiler != null) traceGpuProfiler.exposureEnd(cmd);

                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "optical glare")) {
                    bloomPipeline.dispatch(cmd, displayW, displayH);
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack);

                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                    displayPipeline.dispatch(cmd, displayW, displayH, RtHdr.effective());
                }
                if (traceGpuProfiler != null) traceGpuProfiler.displayEnd(cmd);
                offlineLastPresentNanos = offlineGroundTruth ? System.nanoTime() : 0L;
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            } else if (traceGpuProfiler != null) {
                traceGpuProfiler.exposureEnd(cmd);
                traceGpuProfiler.displayEnd(cmd);
            }
            hdrWrittenThisFrame = RtHdr.effective();

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            if (traceGpuProfiler != null) traceGpuProfiler.copyEnd(cmd);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        long graphicsUse = gpuExecutor.beginGraphicsTerrainUse(encoder);
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
        pendingTerrainGraphicsUse = graphicsUse;
    }

    private void clearOfflineAccumulation(VkCommandBuffer cmd, MemoryStack stack) {
        VkClearColorValue zero = VkClearColorValue.calloc(stack);
        zero.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdClearColorImage(cmd, groundTruthAccum.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
        VK10.vkCmdClearColorImage(cmd, offlinePilotA.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
        VK10.vkCmdClearColorImage(cmd, offlinePilotB.image, VK10.VK_IMAGE_LAYOUT_GENERAL, zero, range);
    }

    /**
     * Block-breaking overlay: mirrors vanilla's {@code ClientLevel.destructionProgress()} (populated
     * by network packets, independent of the cancelled {@code LevelRenderer.render()} — see
     * [[rt-native-overlay-tier1]]) into the push's {@code breaking[]} list, so {@code world.rchit} can blend
     * the matching destroy-stage crack texture into a hit terrain block's albedo. Each block's own
     * destroy-stage texture ({@code minecraft:textures/block/destroy_stage_N.png}, resolved via
     * {@link ModelBakery#DESTROY_TYPES}) is a standalone {@code Sampler0} texture, not a block-atlas sprite,
     * so it rides the same bindless entity-texture array as entity textures ({@link RtEntityTextures}).
     */
    private BreakEntry[] breakingEntries(RtTerrain terrain) {
        BreakEntry[] result = new BreakEntry[WorldPushData.BREAKING_CAPACITY];
        int count = 0;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entry : level.destructionProgress().long2ObjectEntrySet()) {
                if (count >= result.length) {
                    break;
                }
                var progresses = entry.getValue();
                if (progresses == null || progresses.isEmpty()) {
                    continue;
                }
                int stage = Mth.clamp(progresses.last().getProgress(), 0, 9);
                BlockPos pos = BlockPos.of(entry.getLongKey());
                int slot = RtEntityTextures.INSTANCE.slotFor(ModelBakery.DESTROY_TYPES.get(stage));
                result[count++] = new BreakEntry(new Int4(
                        pos.getX() - terrain.blockX,
                        pos.getY() - terrain.blockY,
                        pos.getZ() - terrain.blockZ,
                        slot));
            }
        }
        return count == result.length ? result : java.util.Arrays.copyOf(result, count);
    }

    private record SkyPush(Float4 sunDir, Float4 lightDir, Float4 lightRadiance, Float4 moonDir,
                           Float4 celestial, Float4 celestialRadii, Float4 moonUv, Float4 environmentSky,
                           Float4 skyState, Float4 skyLighting, Float4 borderFogColor,
                           Float4 borderFogParams) {}

    private record CelestialUv(Float4 sun, Float4 moon) {}

    /**
     * Project Minecraft's authoritative partial-tick clock and eight-day lunar phase onto an Earth-style
     * astronomical sky. Latitude and season change the solar declination/path; the physical sun altitude
     * then owns atmosphere, twilight, stars and direct-light gating so those systems cannot disagree.
     */
    private SkyPush skyPush() {
        final float sceneScale = 1.0f / 1024.0f;
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        float sunClockAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial)
                * (float)(Math.PI / 180.0);
        int moonPhaseIndex = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index();
        long worldDay = mc.level == null ? 0L
                : Math.floorDiv(mc.level.getLevelData().getGameTime(), 24_000L);
        AstronomicalSky.State astronomy = AstronomicalSky.calculate(sunClockAngle, worldDay, moonPhaseIndex,
                CausticaConfig.Rt.Composite.ASTRONOMICAL_LATITUDE_DEG.value(),
                CausticaConfig.Rt.Composite.DAY_OF_YEAR_OFFSET.value());
        float[] sunDirection = astronomy.sunDirection();
        float[] moonDirection = astronomy.moonDirection();
        float sunX = sunDirection[0], sunY = sunDirection[1], sunZ = sunDirection[2];
        float moonX = moonDirection[0], moonY = moonDirection[1], moonZ = moonDirection[2];
        float moonPhase = moonPhaseIndex;
        boolean earthAtmosphere = mc.level != null && Level.OVERWORLD.equals(mc.level.dimension());
        float rainBrightness = mc.level == null ? 1.0f : 1.0f - mc.level.getRainLevel(partial);
        float starBrightness = astronomy.starBrightness() * rainBrightness;
        int packedSky = probe.getValue(EnvironmentAttributes.SKY_COLOR, partial);
        float dayFactor = astronomy.dayFactor();
        float twilightFactor = astronomy.twilightFactor();
        float solarEnvelope = astronomy.solarEnvelope();
        float ambientEv = CausticaConfig.Rt.Composite.AMBIENT_LIGHT_EV.value();
        float sunlightEv = CausticaConfig.Rt.Composite.SUNLIGHT_INTENSITY_EV.value();
        float moonlightEv = CausticaConfig.Rt.Composite.MOONLIGHT_INTENSITY_EV.value();
        float airglowEv = CausticaConfig.Rt.Composite.NIGHT_AIRGLOW_EV.value();
        float sunAngularRadius = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value();
        float moonAngularRadius = CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.value();
        handleSkyDiscontinuity(sunX, sunY, sunZ, ambientEv, sunlightEv, moonlightEv, airglowEv,
                sunAngularRadius, moonAngularRadius);
        int fallbackSky = (packedSky & 0x00ffffff) != 0
                ? packedSky : probe.getValue(EnvironmentAttributes.FOG_COLOR, partial);
        Float4 environmentSky = linearBt2020FromPackedRgb(fallbackSky);
        Float4 borderFogColor = linearBt2020FromPackedRgb(
                probe.getValue(EnvironmentAttributes.FOG_COLOR, partial));
        int renderDistanceChunks = Math.max(1, mc.options.getEffectiveRenderDistance());
        float borderFogStart = Math.max(0.0f, (renderDistanceChunks - 3.0f) * 16.0f);
        float borderFogEnd = Math.max(borderFogStart + 16.0f,
                (renderDistanceChunks - 0.5f) * 16.0f);
        Float4 borderFogParams = new Float4(borderFogStart, borderFogEnd, 0.0f, 0.0f);

        float[] sunTrans = new float[3];
        float[] moonTrans = new float[3];
        atmosphereTransmittance(sunX, sunY, sunZ, sunTrans);
        atmosphereTransmittance(moonX, moonY, moonZ, moonTrans);
        float litFraction = astronomy.moonLitFraction();
        float sunMultiplier = (float)Math.pow(2.0, sunlightEv);
        float moonMultiplier = (float)Math.pow(2.0, moonlightEv);
        float airglowMultiplier = (float)Math.pow(2.0, airglowEv);
        float sunPeak = 120_000.0f * sceneScale * sunMultiplier * dayFactor * rainBrightness;
        float moonPeak = 0.25f * sceneScale * moonMultiplier * litFraction
                * (1.0f - solarEnvelope) * rainBrightness;
        float sunLuma = sunPeak * (0.2627f * sunTrans[0] + 0.6780f * sunTrans[1] + 0.0593f * sunTrans[2]);
        float moonLuma = moonPeak * (0.2627f * moonTrans[0] + 0.6780f * moonTrans[1] + 0.0593f * moonTrans[2]);
        float lx, ly, lz, rr, rg, rb, lightRadius;
        if (sunLuma >= moonLuma) {
            lx = sunX; ly = sunY; lz = sunZ;
            rr = sunPeak * sunTrans[0]; rg = sunPeak * sunTrans[1]; rb = sunPeak * sunTrans[2];
            lightRadius = sunAngularRadius;
        } else {
            lx = moonX; ly = moonY; lz = moonZ;
            rr = moonPeak * moonTrans[0]; rg = moonPeak * moonTrans[1]; rb = moonPeak * moonTrans[2];
            lightRadius = moonAngularRadius;
        }
        if (!earthAtmosphere) {
            dayFactor = twilightFactor = solarEnvelope = 0.0f;
            rr = rg = rb = 0.0f;
            starBrightness = 0.0f;
        }
        float ambientMultiplier = (float)Math.pow(2.0, ambientEv);
        publishedSunAngle = astronomy.solarHourAngle(); publishedMoonAngle = astronomy.lunarHourAngle();
        publishedDayFactor = dayFactor; publishedTwilightFactor = twilightFactor; publishedAmbientEv = ambientEv;
        publishedSunX = sunX; publishedSunY = sunY; publishedSunZ = sunZ;
        publishedMoonX = moonX; publishedMoonY = moonY; publishedMoonZ = moonZ;
        CelestialUv uv = celestialUv(moonPhase);
        return new SkyPush(
                new Float4(sunX, sunY, sunZ, rainBrightness),
                new Float4(lx, ly, lz, lightRadius),
                new Float4(rr, rg, rb, starBrightness),
                new Float4(moonX, moonY, moonZ, moonPhase),
                new Float4(astronomy.celestialPole()[0], astronomy.celestialPole()[1],
                        astronomy.celestialPole()[2], astronomy.siderealAngle()),
                new Float4(sunAngularRadius, moonAngularRadius, 0.0f, 0.0f), uv.moon(), environmentSky,
                new Float4(dayFactor, twilightFactor, ambientMultiplier, solarEnvelope),
                new Float4(sunMultiplier, moonMultiplier, airglowMultiplier, litFraction),
                borderFogColor, borderFogParams);
    }

    static float vanillaDayFactor(int packedSky) {
        return Math.max((packedSky >>> 16) & 0xff,
                Math.max((packedSky >>> 8) & 0xff, packedSky & 0xff)) / 255.0f;
    }

    static float vanillaTwilightFactor(int packedTwilight) {
        return ((packedTwilight >>> 24) & 0xff) / 255.0f;
    }

    private void handleSkyDiscontinuity(float sunX, float sunY, float sunZ,
                                        float ambientEv, float sunlightEv,
                                        float moonlightEv, float airglowEv,
                                        float sunAngularRadius, float moonAngularRadius) {
        boolean ambientChanged = !Float.isNaN(lastSkyAmbientEv)
                && Float.floatToIntBits(ambientEv) != Float.floatToIntBits(lastSkyAmbientEv);
        boolean sourceChanged = (!Float.isNaN(lastSunlightEv)
                && Float.floatToIntBits(sunlightEv) != Float.floatToIntBits(lastSunlightEv))
                || (!Float.isNaN(lastMoonlightEv)
                && Float.floatToIntBits(moonlightEv) != Float.floatToIntBits(lastMoonlightEv))
                || (!Float.isNaN(lastAirglowEv)
                && Float.floatToIntBits(airglowEv) != Float.floatToIntBits(lastAirglowEv));
        float angleDelta = Float.isNaN(lastSkySunX) ? 0.0f
                : (float)Math.acos(Math.clamp(lastSkySunX * sunX + lastSkySunY * sunY + lastSkySunZ * sunZ,
                        -1.0f, 1.0f));
        boolean commandTimeJump = commandTimeResetRequested;
        commandTimeResetRequested = false;
        boolean timeJump = commandTimeJump || angleDelta > 0.05f;
        boolean samplingChanged = (!Float.isNaN(lastSunAngularRadius)
                && Float.floatToIntBits(sunAngularRadius) != Float.floatToIntBits(lastSunAngularRadius))
                || (!Float.isNaN(lastMoonAngularRadius)
                && Float.floatToIntBits(moonAngularRadius) != Float.floatToIntBits(lastMoonAngularRadius));
        if (ambientChanged || sourceChanged || timeJump || samplingChanged) {
            String reason = timeJump ? "time-of-day jumped"
                    : (sourceChanged ? "sky light source changed"
                    : (samplingChanged ? "celestial sampling radius changed" : "ambient light changed"));
            fgReset = true;
            rrProducedPreviousFrame = false;
            RtDlssRr.INSTANCE.requestHistoryReset();
            requestSharcReset(reason);
            exposure.resetAutoHistory();
        }
        lastSkySunX = sunX;
        lastSkySunY = sunY;
        lastSkySunZ = sunZ;
        lastSkyAmbientEv = ambientEv;
        lastSunlightEv = sunlightEv;
        lastMoonlightEv = moonlightEv;
        lastAirglowEv = airglowEv;
        lastSunAngularRadius = sunAngularRadius;
        lastMoonAngularRadius = moonAngularRadius;
    }

    static boolean replacementAtlasesReady(long blockAtlas, long celestialAtlas,
                                            long boundBlockAtlas, long boundCelestialAtlas) {
        return blockAtlas != 0L && celestialAtlas != 0L
                && blockAtlas != boundBlockAtlas && celestialAtlas != boundCelestialAtlas;
    }

    private boolean skyViewChanged(float sunX, float sunY, float sunZ, float sunSource,
                                   float moonX, float moonY, float moonZ, float moonSource,
                                   boolean enabled) {
        // The 256x256 full-float table removes visible elevation rows from exact mirror reflections.
        // A 0.15-degree threshold still updates multiple times across one apparent solar diameter, and
        // offsets the doubled row count by rebuilding at most one third as often as the old table.
        boolean changed = !skyViewStateValid
                || materiallyDifferentDirection(sunX, sunY, sunZ,
                        lastSkyViewSunX, lastSkyViewSunY, lastSkyViewSunZ)
                || materiallyDifferentDirection(moonX, moonY, moonZ,
                        lastSkyViewMoonX, lastSkyViewMoonY, lastSkyViewMoonZ)
                || materiallyDifferentSource(sunSource, lastSkyViewSunSource)
                || materiallyDifferentSource(moonSource, lastSkyViewMoonSource)
                || enabled != lastSkyViewEnabled;
        if (changed) {
            skyViewStateValid = true;
            lastSkyViewSunX = sunX;
            lastSkyViewSunY = sunY;
            lastSkyViewSunZ = sunZ;
            lastSkyViewSunSource = sunSource;
            lastSkyViewMoonX = moonX;
            lastSkyViewMoonY = moonY;
            lastSkyViewMoonZ = moonZ;
            lastSkyViewMoonSource = moonSource;
            lastSkyViewEnabled = enabled;
        }
        return changed;
    }

    static boolean materiallyDifferentDirection(float x, float y, float z,
                                                  float previousX, float previousY, float previousZ) {
        return previousX * x + previousY * y + previousZ * z < 0.99999657f; // cos(0.15 degrees)
    }

    static boolean materiallyDifferentSource(float current, float previous) {
        float scale = Math.max(Math.max(Math.abs(current), Math.abs(previous)), 1.0e-6f);
        return Math.abs(current - previous) > scale * (1.0f / 512.0f);
    }

    /** Called from the vanilla clock-update packet before its new state is applied. */
    public void observeServerTime(long gameTime, long overworldDayTime) {
        if (lastPacketDayTime != Long.MIN_VALUE && lastPacketGameTime != Long.MIN_VALUE) {
            long dayDelta = overworldDayTime - lastPacketDayTime;
            long gameDelta = gameTime - lastPacketGameTime;
            if (dayDelta != 0L && dayDelta != gameDelta) commandTimeResetRequested = true;
        }
        lastPacketDayTime = overworldDayTime;
        lastPacketGameTime = gameTime;
    }

    private static Float4 linearBt2020FromPackedRgb(int packed) {
        return linearBt2020FromRgb(
                ((packed >>> 16) & 0xff) / 255.0,
                ((packed >>> 8) & 0xff) / 255.0,
                (packed & 0xff) / 255.0);
    }

    private static Float4 linearBt2020FromRgb(double encodedR, double encodedG, double encodedB) {
        double r = srgbToLinear(encodedR);
        double g = srgbToLinear(encodedG);
        double b = srgbToLinear(encodedB);
        return new Float4(
                (float) (0.6274039 * r + 0.3292830 * g + 0.0433131 * b),
                (float) (0.0690973 * r + 0.9195406 * g + 0.0113612 * b),
                (float) (0.0163916 * r + 0.0880132 * g + 0.8955953 * b),
                0.0f);
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /**
     * Push the celestials-atlas UV rects (u0,v0,u1,v1) for the sun sprite and the current moon-phase
     * sprite, so world.rmiss can sample the real vanilla textures on the discs. An unresolved atlas is
     * represented by a zero-area rect; world.rmiss skips that body and this method retries next frame.
     */
    private CelestialUv celestialUv(float moonPhaseIndex) {
        if (celestialUvAtlasHandle == 0L) {
            setCelestialUvAtlas(celestialsAtlasView());
        }
        int phase = Math.clamp((int) moonPhaseIndex, 0, MOON_IDS.length - 1);
        if (phase != celestialUvMoonPhase) {
            refreshCelestialUvCache(phase);
        }
        return new CelestialUv(
                new Float4(sunU0, sunV0, sunU1, sunV1),
                new Float4(moonU0, moonV0, moonU1, moonV1));
    }

    private void setCelestialUvAtlas(long atlasHandle) {
        if (celestialUvAtlasHandle == atlasHandle) {
            return;
        }
        celestialUvAtlasHandle = atlasHandle;
        celestialUvMoonPhase = -1;
        sunU0 = sunV0 = sunU1 = sunV1 = 0f;
        moonU0 = moonV0 = moonU1 = moonV1 = 0f;
        celestialUvFailureLogged = false;
        celestialUvResolvedLogged = false;
    }

    private void refreshCelestialUvCache(int moonPhase) {
        if (celestialUvAtlasHandle == 0L) {
            return;
        }
        try {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
            TextureAtlasSprite sun = atlas.getSprite(SUN_ID);
            TextureAtlasSprite moon = atlas.getSprite(MOON_IDS[moonPhase]);
            float nextSunU0 = sun.getU0(), nextSunV0 = sun.getV0();
            float nextSunU1 = sun.getU1(), nextSunV1 = sun.getV1();
            float nextMoonU0 = moon.getU0(), nextMoonV0 = moon.getV0();
            float nextMoonU1 = moon.getU1(), nextMoonV1 = moon.getV1();
            if (!validCelestialUvRect(nextSunU0, nextSunV0, nextSunU1, nextSunV1)
                    || !validCelestialUvRect(nextMoonU0, nextMoonV0, nextMoonU1, nextMoonV1)) {
                throw new IllegalStateException("celestial atlas returned an invalid sprite rectangle");
            }
            sunU0 = nextSunU0; sunV0 = nextSunV0; sunU1 = nextSunU1; sunV1 = nextSunV1;
            moonU0 = nextMoonU0; moonV0 = nextMoonV0; moonU1 = nextMoonU1; moonV1 = nextMoonV1;
            celestialUvMoonPhase = moonPhase;
            if (!celestialUvResolvedLogged) {
                CausticaMod.LOGGER.info("Celestial sprites resolved: sun={} uv=[{},{},{},{}], moon={} uv=[{},{},{},{}]",
                        SUN_ID, sunU0, sunV0, sunU1, sunV1, MOON_IDS[moonPhase],
                        moonU0, moonV0, moonU1, moonV1);
                celestialUvResolvedLogged = true;
            }
            celestialUvFailureLogged = false;
        } catch (Exception error) {
            sunU0 = sunV0 = sunU1 = sunV1 = 0f;
            moonU0 = moonV0 = moonU1 = moonV1 = 0f;
            celestialUvMoonPhase = -1;
            if (!celestialUvFailureLogged) {
                celestialUvFailureLogged = true;
                CausticaMod.LOGGER.warn("Celestial sprite lookup failed; hiding the discs and retrying", error);
            }
        }
    }

    static boolean validCelestialUvRect(float u0, float v0, float u1, float v1) {
        return Float.isFinite(u0) && Float.isFinite(v0) && Float.isFinite(u1) && Float.isFinite(v1)
                && u0 >= 0.0f && v0 >= 0.0f && u1 <= 1.0f && v1 <= 1.0f
                && u1 > u0 && v1 > v0;
    }

    /** Hermite smoothstep matching GLSL semantics (0 below edge0, 1 above edge1). */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * RGB transmittance from the camera to space along {@code dir} — a verbatim port of
     * {@code world.rmiss}'s {@code transmittanceToSpace} (Rayleigh + Mie + ozone optical depth, 16-step
     * march from 2 km altitude; constants must stay in lock-step with the shader). This is what colours
     * the NEE sun/moonlight: because the sky shader tints its visible discs with the identical function,
     * the light on terrain and the sky's sunset can never disagree. A direction below the geometric
     * horizon accumulates enormous optical depth, so the result rolls to zero smoothly on its own —
     * no explicit planet-shadow test needed.
     */
    static void atmosphereTransmittance(float dx, float dy, float dz, float[] out) {
        final double planetR = 6371.0, atmosR = 6471.0;
        final double[] molecularBase = {6.605e-3, 1.067e-2, 1.842e-2, 3.156e-2};
        final double[] ozoneXs = {3.472e-25, 3.914e-25, 1.349e-25, 11.03e-27};
        final double[] aerosolAbsorbXs = {2.8722e-24, 4.6168e-24, 7.9706e-24, 1.3578e-23};
        final double[] aerosolScatterXs = {1.5908e-22, 1.7711e-22, 2.0942e-22, 2.4033e-22};
        final double aerosolBaseDensity = 1.3681e20;
        final double oy = planetR + 2.0;
        double b = oy * dy;
        double tEnd = -b + Math.sqrt(Math.max(b * b - (oy * oy - atmosR * atmosR), 0.0));
        final int lightSteps = 16;
        double seg = tEnd / lightSteps;
        double[] opticalDepth = new double[4];
        for (int i = 0; i < lightSteps; i++) {
            double t = seg * (i + 0.5);
            double px = dx * t, py = oy + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - planetR;
            if (h < 0.0) {
                java.util.Arrays.fill(out, 0.0f);
                return;
            }
            double logH = Math.log(Math.max(h, 1.0e-4));
            double ozoneDensity = 3.78547397e20
                    * Math.exp(-Math.pow(logH - 3.22261, 2.0) * 5.55555555 - logH);
            double aerosolDensity = aerosolBaseDensity
                    * (Math.exp(-h / 0.73) + 2.0e6 / aerosolBaseDensity);
            double molecularDensity = Math.exp(-0.07771971 * Math.pow(Math.max(h, 0.0), 1.16364243));
            for (int wavelength = 0; wavelength < 4; wavelength++) {
                opticalDepth[wavelength] += (molecularBase[wavelength] * molecularDensity
                        + ozoneXs[wavelength] * 334.5 * ozoneDensity
                        + (aerosolAbsorbXs[wavelength] + aerosolScatterXs[wavelength]) * aerosolDensity)
                        * seg;
            }
        }
        double[] sun = {1.679, 1.828, 1.986, 1.307};
        double[] transmitted = new double[4];
        for (int i = 0; i < 4; i++) {
            transmitted[i] = sun[i] * Math.exp(-opticalDepth[i]);
        }
        double[] rgb = spectralToBt2020(transmitted);
        double[] reference = spectralToBt2020(sun);
        out[0] = (float) Math.max(rgb[0] / reference[0], 0.0);
        out[1] = (float) Math.max(rgb[1] / reference[1], 0.0);
        out[2] = (float) Math.max(rgb[2] / reference[2], 0.0);
    }

    private static double[] spectralToBt2020(double[] spectrum) {
        double x = 53.3869177386 * spectrum[0] + 43.9048444664 * spectrum[1]
                + 1.6137278252 * spectrum[2] + 20.7626686738 * spectrum[3];
        double y = 22.9813375067 * spectrum[0] + 71.3477957001 * spectrum[1]
                + 18.4229605915 * spectrum[2] + 2.3614213523 * spectrum[3];
        double z = 0.1025068680 * spectrum[1] + 31.7429211884 * spectrum[2]
                + 110.4800964325 * spectrum[3];
        return new double[] {
                1.7166512 * x - 0.3556708 * y - 0.2533663 * z,
                -0.6666844 * x + 1.6164812 * y + 0.0157685 * z,
                0.0176399 * x - 0.0427706 * y + 0.9421031 * z
        };
    }

    public void destroy() {
        // Teardown runs after the device is idle (CLIENT_STOPPING waits), so the TLAS ring's slots are no
        // longer in flight and can be freed immediately.
        tlasRing.destroy();
        // Release the Streamline RR viewport even if the setting was changed after resources were created.
        RtDlssRr.INSTANCE.destroy();
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
            hdrDisplayImage = null;
        }
        RtDlssFg.INSTANCE.beforeInputResourcesDestroyed();
        destroyFgInputSlots();
        if (fgUiAlphaPipeline != null) {
            fgUiAlphaPipeline.destroy();
            fgUiAlphaPipeline = null;
        }
        RtWorldOverlay.INSTANCE.destroy(); // overlay features/pipelines/scratch live on the same device lifetime
        if (output != null) {
            output.destroy();
            output = null;
        }
        destroyBloomImages();
        destroyGuideImages();
        exposure.destroy();
        if (skyViewPipeline != null) {
            skyViewPipeline.destroy();
            skyViewPipeline = null;
        }
        if (skyViewLut != null) {
            skyViewLut.destroy();
            skyViewLut = null;
        }
        if (skyTransmittanceLut != null) {
            skyTransmittanceLut.destroy();
            skyTransmittanceLut = null;
        }
        skyTransmittanceReady = false;
        skyViewStateValid = false;
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (bloomPipeline != null) {
            bloomPipeline.destroy();
            bloomPipeline = null;
        }
        if (hdrCompositePipeline != null) {
            hdrCompositePipeline.destroy();
            hdrCompositePipeline = null;
        }
        if (hdrUiSampler != 0L) {
            RtContext hdrCtx = RtContext.currentOrNull();
            if (hdrCtx != null) {
                VK10.vkDestroySampler(hdrCtx.vk(), hdrUiSampler, null);
            }
            hdrUiSampler = 0L;
        }
        if (sdrPresentPipeline != null) {
            sdrPresentPipeline.destroy();
            sdrPresentPipeline = null;
        }
        if (sdrPresentImage != null) {
            sdrPresentImage.destroy();
            sdrPresentImage = null;
        }
        fgReset = true;
        fgPreviousViewProjectionValid = false;
        previousWaterWaveTimeValid = false;
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        destroyOfflineResources();
        destroySharcResources();
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        if (pushRing != null) {
            for (RtBuffer b : pushRing) {
                if (b != null) {
                    b.destroy();
                }
            }
            pushRing = null;
        }
        if (blueNoiseSequence != null) {
            blueNoiseSequence.destroy();
            blueNoiseSequence = null;
        }
        if (traceGpuProfiler != null) {
            traceGpuProfiler.destroy();
            traceGpuProfiler = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
    }

    private static void publishSharcStats(RtSharcCache.Stats stats) {
        RtFrameStats.FRAME.count("sharcQueryAttempts", stats.queryAttempts());
        RtFrameStats.FRAME.count("sharcQueryHits", stats.queryHits());
        RtFrameStats.FRAME.count("sharcQueryMisses", stats.queryMisses());
        RtFrameStats.FRAME.count("sharcUpdateHits", stats.updateHits());
        RtFrameStats.FRAME.count("sharcUpdateMisses", stats.updateMisses());
        RtFrameStats.FRAME.count("sharcInsertFailures", stats.insertFailures());
        RtFrameStats.FRAME.count("sharcTerminatedBounceSum", stats.terminatedBounceSum());
        RtFrameStats.FRAME.count("sharcTerminatedPaths", stats.terminatedPaths());
        if (stats.terminatedPaths() > 0) {
            RtFrameStats.FRAME.count("sharcAverageTerminatedBounceX1000",
                    stats.terminatedBounceSum() * 1000L / stats.terminatedPaths());
        }
        RtFrameStats.FRAME.count("sharcOccupiedEntries", stats.occupiedEntries());
        RtFrameStats.FRAME.count("sharcInsertions", stats.insertions());
        RtFrameStats.FRAME.count("sharcCollisions", stats.collisions());
        RtFrameStats.FRAME.count("sharcStaleEvictions", stats.staleEvictions());
        RtFrameStats.FRAME.count("sharcNumericRisks", stats.numericRisks());
        RtFrameStats.FRAME.count("sharcResolvedSaturations", stats.resolvedSaturations());
        RtFrameStats.FRAME.count("sharcMaxCachedLumaBits", stats.maxCachedLumaBits());
        RtFrameStats.FRAME.count("sharcShortSegmentRejects", stats.shortSegmentRejects());
        RtFrameStats.FRAME.count("sharcGlossyRejects", stats.glossyRejects());
        RtFrameStats.FRAME.count("sharcDynamicRejects", stats.dynamicRejects());
        RtFrameStats.FRAME.count("sharcAllocatedBytes", sharcAllocatedBytes());
        RtFrameStats.FRAME.count("sharcResetCount", sharcResetCount());
    }

    private static long sharcAllocatedBytes() {
        RtSharcCache cache = INSTANCE.sharcCache;
        return cache == null ? 0L : cache.bytes();
    }

    private static long sharcResetCount() {
        RtSharcCache cache = INSTANCE.sharcCache;
        return cache == null ? 0L : cache.resetCount();
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }



    private static long blockAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    /** Whether the HDR present path (HDR image + combined UI -> PQ swapchain) should replace the vanilla SDR blit. */
    public boolean isHdrPresentActive() {
        return RtHdr.effective()
                && hdrWrittenThisFrame
                && hdrDisplayImage != null;
    }

    /**
     * Blit this frame's PQ-encoded HDR image straight into the swapchain image, replacing Minecraft's SDR
     * blit. Replicates {@code VulkanGpuSurface.blitFromTexture}'s barrier + acquire-wait/present-signal
     * sequence with the HDR {@link RtImage} as the (GENERAL-layout) source; an added memory barrier makes the
     * display-compute writes visible to the blit read. The SDR main target is bypassed; the combined UI image
     * is blended over the HDR image here at paper white before the swapchain blit. The magic stage/access
     * values mirror vanilla {@code blitFromTexture} exactly. Y is flipped to match the vanilla swapchain blit.
     */
    public void presentHdr(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH,
            int swapchainFormat, long acquireSem, long presentSem) {
        RtImage src = hdrDisplayImage;
        int copyW = Math.min(swapW, src.width);
        int copyH = Math.min(swapH, src.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // DLSS-FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer.
            if (RtDlssFg.enabled()) {
                captureFgHdrHudless(cmd, stack, src, swapchainFormat);
            }

            // Step C.2: composite the combined UI overlay over the HDR world image (in place) at paper white,
            // before the swapchain blit. The overlay is an MC render target kept in GENERAL layout, sampled by
            // the compute pass. A memory barrier first makes the overlay writes + the world HDR writes visible
            // to the compute; the dep1 barrier below (ALL writes -> transfer read) then covers the compute's
            // HDR write for the blit.
            long overlayView = RtUiOverlay.populatedThisFrame() ? RtUiOverlay.overlayColorView() : 0L;
            if (overlayView != 0L) {
                ensureHdrUiResources();
                if (hdrCompositePipeline != null) {
                    VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
                    VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                    hdrCompositePipeline.setImages(hdrDisplayImage.view, overlayView, hdrUiSampler);
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, CausticaConfig.Rt.Hdr.paperWhiteNits());
                }
                RtUiOverlay.markConsumed();
            }
            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit HDR (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
    }

    /** Lazily create the HDR UI-composite compute pipeline + its nearest/clamp sampler (first HDR present). */
    private void ensureHdrUiResources() {
        if (hdrCompositePipeline != null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return;
        }
        hdrCompositePipeline = RtHdrCompositePipeline.create(ctx);
    }

    /** Ensure the shared nearest/clamp sampler used to sample SDR/overlay targets in the present compute. */
    private boolean ensureUiSampler(RtContext ctx) {
        if (hdrUiSampler != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                return false;
            }
            hdrUiSampler = p.get(0);
        }
        return true;
    }

    /**
     * Whether a non-RT frame (menu, title panorama, loading screen) should be SDR-&gt;PQ converted for
     * present instead of vanilla's raw SDR blit. True when the PQ swapchain is active but this frame did
     * not produce an HDR image ({@link #isHdrPresentActive()} false).
     */
    public boolean isPqSdrPresentActive() {
        return RtHdr.effective()
                && !isHdrPresentActive();
    }

    /**
     * Present a non-RT (menu/loading) frame to the PQ swapchain: convert the SDR main target (sRGB-encoded
     * rgba8, GENERAL layout, already holding the composited panorama + UI) to PQ-encoded at paper white via
     * a compute pass into {@link #sdrPresentImage}, then blit that into the swapchain. Mirrors
     * {@link #presentHdr} barrier-for-barrier; returns false (keep vanilla SDR blit) if resources are
     * unavailable.
     */
    public boolean presentSdrToPq(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH,
            long sdrMainView, long acquireSem, long presentSem) {
        if (sdrMainView == 0L || failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return false;
        }
        if (sdrPresentPipeline == null) {
            sdrPresentPipeline = RtSdrPresentPipeline.create(ctx);
        }
        if (sdrPresentImage == null || sdrPresentImage.width != swapW || sdrPresentImage.height != swapH) {
            if (sdrPresentImage != null) {
                sdrPresentImage.destroy();
            }
            sdrPresentImage = ctx.createStorageImage(swapW, swapH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapW + "x" + swapH);
        }
        RtImage dst = sdrPresentImage;
        int copyW = Math.min(swapW, dst.width);
        int copyH = Math.min(swapH, dst.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // Make the prior GUI/overlay writes to the SDR main target visible to the compute sample.
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            sdrPresentPipeline.setImages(dst.view, sdrMainView, hdrUiSampler);
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, CausticaConfig.Rt.Hdr.paperWhiteNits());

            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the compute write visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit converted PQ image (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
        return true;
    }

    /**
     * Linear-filtered fallback from RR render resolution to display resolution. Native-resolution RT feeds
     * display mapping directly and does not use this copy.
     */
    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }

    /**
     * DLSS Frame Generation quality: normalize {@code main} (the main render target) into
     * the active input slot for Streamline's swapchain-oriented DLSS-G hudless-color tag. Call from
     * {@code GameRendererMixin} right after {@code GuiRenderer.render()} but BEFORE
     * {@link RtUiOverlay#compositeIfUsed()} — at that point, when the UI overlay redirect is active, {@code
     * main} still has no combined UI baked in (world overlays, hand/screen effects and GUI went to the
     * overlay target instead). No-op unless both FG and the UI overlay redirect are active — capturing this
     * without the redirect would just
     * copy the ALREADY-composited backbuffer, which is useless as a distinct hudless input.
     */
    public void captureFgHudless(RenderTarget main) {
        if (!RtDlssFg.requested() || RtHdr.effective() || !RtUiOverlay.enabled()
                || !hasCurrentFrameForFg() || main == null || main.getColorTexture() == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        int outputWidth = main.width;
        int outputHeight = main.height;
        if (ctx == null || outputWidth <= 0 || outputHeight <= 0) {
            return;
        }
        FgInputSlot slot = activeFgInputSlot(ctx);
        if (slot == null || !RtDlssFg.INSTANCE.beforeFrameInputs()) {
            return;
        }
        long srcImage;
        try {
            srcImage = vkImage(main.getColorTexture());
        } catch (IllegalStateException e) {
            return; // not a Vulkan-backed texture (shouldn't happen on this backend)
        }
        if (slot.hudlessSdr == null || slot.hudlessSdr.width != outputWidth
                || slot.hudlessSdr.height != outputHeight) {
            if (slot.hudlessSdr != null) {
                slot.hudlessSdr.destroy();
            }
            slot.hudlessSdr = ctx.createStorageImage(outputWidth, outputHeight, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                    "FG hudless capture slot " + RtDlssFg.INSTANCE.activeInputSlot() + " "
                            + outputWidth + "x" + outputHeight);
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make writes into `main` visible to the copy (the combined UI has not touched `main` yet this
            // frame — it went to the UI overlay target instead).
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            blitFlipped(cmd, stack, srcImage, main.width, main.height, slot.hudlessSdr, VK10.VK_FILTER_NEAREST);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        encoder.execute(cmd);
    }

    /**
     * HDR counterpart of {@link #captureFgHudless} — copies {@code src} (this frame's {@code hdrDisplayImage},
     * before the combined UI overlay is blended in) into the active input slot for Streamline's HDR10
     * hudless-color tag. The compute encode converts the renderer's float PQ image to the swapchain's
     * RGB10A2 format with a format-safe image blit. Called from {@link #presentHdr} using its already-open {@code cmd}/
     * {@code stack}, right before that method's own combined-UI composite dispatch overwrites
     * {@code hdrDisplayImage} in place — same "capture before the UI gets baked back in" timing as the SDR
     * version, just within a single method instead of split across a mixin hook.
     */
    private void captureFgHdrHudless(VkCommandBuffer cmd, MemoryStack stack, RtImage src,
            int swapchainFormat) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null || (swapchainFormat != VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32
                && swapchainFormat != VK10.VK_FORMAT_A2R10G10B10_UNORM_PACK32)) {
            return;
        }
        FgInputSlot slot = activeFgInputSlot(ctx);
        if (slot == null || !RtDlssFg.INSTANCE.beforeFrameInputs()) {
            return;
        }
        if (slot.hudlessHdr == null || slot.hudlessHdr.width != src.width
                || slot.hudlessHdr.height != src.height || slot.hudlessHdr.format != swapchainFormat) {
            if (slot.hudlessHdr != null) {
                slot.hudlessHdr.destroy();
            }
            slot.hudlessHdr = ctx.createSampledTransferImage(src.width, src.height, swapchainFormat,
                    "FG HDR10 hudless slot " + RtDlssFg.INSTANCE.activeInputSlot() + " "
                            + src.width + "x" + src.height);
        }
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        blitFlipped(cmd, stack, src.image, src.width, src.height, slot.hudlessHdr, VK10.VK_FILTER_NEAREST);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }


    /** Submit this real frame's Streamline constants and input tags before the intercepted present. */
    public boolean submitStreamlineFrame(VulkanCommandEncoder encoder, int swapWidth, int swapHeight,
            int swapchainFormat, boolean hdr) {
        if (!RtDlssFg.requested() || !RtDlssFg.INSTANCE.isAvailable()
                || !hasCurrentFrameForFg()) {
            return false;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return false;
        }
        FgInputSlot slot = activeFgInputSlot(ctx);
        if (slot == null || !RtDlssFg.INSTANCE.beforeFrameInputs()) {
            return false;
        }
        RtImage hudless = hdr ? slot.hudlessHdr : slot.hudlessSdr;
        if (hudless == null) {
            return false;
        }
        int hudlessFormat = hdr ? swapchainFormat : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (hdr && hudless.format != swapchainFormat) {
            return false;
        }
        boolean uiValid = RtUiOverlay.populatedForFrameGeneration()
                && CausticaConfig.Rt.Fg.UI_RECOMPOSITION.value()
                && RtUiOverlay.overlayColorView() != 0L;
        RtImage uiAlpha = null;
        int contentWidth = hudless.width;
        int contentHeight = hudless.height;
        if (uiValid && ensureUiSampler(ctx)) {
            if (fgUiAlphaPipeline == null) {
                fgUiAlphaPipeline = RtFgUiAlphaPipeline.create(ctx);
            }
            if (slot.uiAlpha == null || slot.uiAlpha.width != contentWidth
                    || slot.uiAlpha.height != contentHeight) {
                if (slot.uiAlpha != null) {
                    slot.uiAlpha.destroy();
                }
                slot.uiAlpha = ctx.createStorageImage(contentWidth, contentHeight, VK10.VK_FORMAT_R32_SFLOAT,
                        "DLSS-G UI alpha slot " + RtDlssFg.INSTANCE.activeInputSlot() + " "
                                + contentWidth + "x" + contentHeight);
            }
            uiAlpha = slot.uiAlpha;
        }
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ensureFgGuideImages(ctx, slot, renderW, renderH);
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            blitFlipped(commandBuffer, stack, gDepth.image, gDepth.width, gDepth.height,
                    slot.depth, VK10.VK_FILTER_NEAREST);
            blitFlipped(commandBuffer, stack, gMotion.image, gMotion.width, gMotion.height,
                    slot.motion, VK10.VK_FILTER_NEAREST);
            if (uiAlpha != null) {
                fgUiAlphaPipeline.setImages(uiAlpha.view, RtUiOverlay.overlayColorView(), hdrUiSampler);
                fgUiAlphaPipeline.dispatch(commandBuffer, contentWidth, contentHeight);
            }
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
        }
        boolean submitted = RtDlssFg.INSTANCE.submitFrame(commandBuffer.address(), swapWidth, swapHeight,
                swapchainFormat, renderW, renderH, slot.depth, slot.motion, hudless, hudlessFormat,
                uiAlpha,
                frameProjection, mvCurProjView,
                fgPreviousViewProjectionValid ? fgPreviousViewProjection : mvCurProjView,
                frameViewRotation, frameJitterX, frameJitterY, camX, camY, camZ,
                mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ,
                fgReset || !fgPreviousViewProjectionValid);
        if (VK10.vkEndCommandBuffer(commandBuffer) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(Streamline DLSS-G tags) failed");
        }
        encoder.execute(commandBuffer);
        if (submitted) {
            fgReset = false;
        }
        return submitted;
    }

    private void ensureFgGuideImages(RtContext ctx, FgInputSlot slot, int width, int height) {
        if (slot.depth == null || slot.depth.width != width || slot.depth.height != height) {
            if (slot.depth != null) {
                slot.depth.destroy();
            }
            slot.depth = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R32_SFLOAT,
                    "DLSS-G normalized depth slot " + RtDlssFg.INSTANCE.activeInputSlot() + " "
                            + width + "x" + height);
        }
        if (slot.motion == null || slot.motion.width != width || slot.motion.height != height) {
            if (slot.motion != null) {
                slot.motion.destroy();
            }
            slot.motion = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16_SFLOAT,
                    "DLSS-G normalized motion slot " + RtDlssFg.INSTANCE.activeInputSlot() + " "
                            + width + "x" + height);
        }
    }

    private FgInputSlot activeFgInputSlot(RtContext ctx) {
        int count = RtDlssFg.INSTANCE.inputSlotCount();
        int active = RtDlssFg.INSTANCE.activeInputSlot();
        if (count <= 0 || active < 0 || active >= count) {
            return null;
        }
        if (fgInputSlots.length != count) {
            destroyFgInputSlots();
            fgInputSlots = new FgInputSlot[count];
            for (int slot = 0; slot < count; slot++) {
                fgInputSlots[slot] = new FgInputSlot();
            }
        }
        return fgInputSlots[active];
    }

    private void destroyFgInputSlots() {
        for (FgInputSlot slot : fgInputSlots) {
            if (slot != null) {
                slot.destroy();
            }
        }
        fgInputSlots = new FgInputSlot[0];
    }

    private static final class FgInputSlot {
        private RtImage hudlessSdr;
        private RtImage hudlessHdr;
        private RtImage depth;
        private RtImage motion;
        private RtImage uiAlpha;

        private void destroy() {
            for (RtImage image : new RtImage[] {hudlessSdr, hudlessHdr, depth, motion, uiAlpha}) {
                if (image != null) {
                    image.destroy();
                }
            }
            hudlessSdr = null;
            hudlessHdr = null;
            depth = null;
            motion = null;
            uiAlpha = null;
        }
    }

    private static void blitFlipped(VkCommandBuffer cmd, MemoryStack stack, long sourceImage,
            int sourceWidth, int sourceHeight, RtImage destination, int filter) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(sourceWidth, sourceHeight, 1);
        region.get(0).dstOffsets(0).set(0, destination.height, 0);
        region.get(0).dstOffsets(1).set(destination.width, 0, 1);
        VK10.vkCmdBlitImage(cmd, sourceImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                destination.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, filter);
    }
}
