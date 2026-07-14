package dev.comfyfluffy.caustica.streamline;

import com.mojang.blaze3d.systems.GpuSurface;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import net.minecraft.client.Minecraft;

import java.util.Collection;

/** Coordinates DLSS-G plugin ownership with Minecraft's existing surface reconfiguration transaction. */
public final class StreamlineSwapchainCoordinator {
    public static final StreamlineSwapchainCoordinator INSTANCE = new StreamlineSwapchainCoordinator();

    private boolean configuring;
    private boolean configured;
    private boolean pluginForSwapchain;
    private boolean vsync;
    private boolean vsyncRequested;
    private boolean mailboxSupported;
    private boolean mailboxVsyncCompatibility;
    private GpuSurface.PresentMode presentMode;
    private int width;
    private int height;
    private int format;
    private int imageCount;
    private long generation;
    private boolean reconfigureRequested;

    private StreamlineSwapchainCoordinator() {
    }

    /** Request Minecraft's normal, render-thread surface recreation rather than replacing its lifecycle. */
    public void requestReconfigure() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        reconfigureRequested = true;
        minecraft.execute(minecraft::invalidateSurfaceConfiguration);
    }

    /** Reconcile config/TOML changes that did not originate in the options screen. */
    public void synchronizeRequestedState() {
        if (!configured || configuring || reconfigureRequested) {
            return;
        }
        boolean desiredPlugin = CausticaConfig.Rt.Fg.requested()
                && (!isVsyncRequested() || mailboxSupported);
        if (desiredPlugin != pluginForSwapchain) {
            requestReconfigure();
        }
    }

    /** Called at configure HEAD, before Minecraft waits and destroys its old swapchain. */
    public void configureStarting() {
        configuring = true;
        configured = false;
        reconfigureRequested = false;
        RtDlssFg.INSTANCE.suspendForSwapchainChange();
    }

    /**
     * Preserve Minecraft's VSync option while keeping Vulkan DLSS-G on a supported presentation path.
     *
     * <p>Streamline 2.12 cannot run Vulkan DLSS-G on a FIFO swapchain. RADSER's proven low-latency
     * compatibility contract is to use MAILBOX when VSync and Reflex are requested: MAILBOX remains
     * tear-free because display replacement occurs at vblank. The separate Auto Cap option can provide
     * below-refresh pacing, but VSync never forces that limiter on.
     * A surface without MAILBOX stays on the requested FIFO mode and DLSS-G fails closed.</p>
     */
    public GpuSurface.Configuration normalizeConfiguration(GpuSurface.Configuration configuration,
            Collection<GpuSurface.PresentMode> supportedPresentModes) {
        vsyncRequested = isVsyncConfiguration(configuration);
        mailboxSupported = supportedPresentModes.contains(GpuSurface.PresentMode.MAILBOX);
        mailboxVsyncCompatibility = false;
        if (!CausticaConfig.Rt.Fg.requested() || !vsyncRequested
                || !mailboxSupported) {
            presentMode = configuration.presentMode();
            return configuration;
        }
        mailboxVsyncCompatibility = true;
        presentMode = GpuSurface.PresentMode.MAILBOX;
        CausticaMod.LOGGER.info(
                "DLSS-G VSync compatibility: requested {} -> MAILBOX (tear-free vblank replacement; Auto Cap is independent)",
                configuration.presentMode());
        return new GpuSurface.Configuration(configuration.width(), configuration.height(), presentMode);
    }

    /** Called after the old swapchain is destroyed and immediately before replacement creation. */
    public boolean prepareReplacement(GpuSurface.Configuration configuration) {
        vsync = isVsyncConfiguration(configuration);
        presentMode = configuration.presentMode();
        boolean desiredPlugin = CausticaConfig.Rt.Fg.requested() && !vsync;
        // On the initial Off swapchain, capture adapter support while DLSS-G is still loaded from slInit;
        // the feature is deliberately unloaded immediately below to remove disabled-present overhead.
        RtDlssFg.INSTANCE.probeAvailabilityOnce();
        boolean prepared = StreamlineRuntime.prepareSwapchain(desiredPlugin);
        pluginForSwapchain = desiredPlugin && prepared;
        if (desiredPlugin && !prepared) {
            CausticaMod.LOGGER.warn("DLSS-G swapchain proxy could not be enabled; replacement remains native");
        }
        return pluginForSwapchain;
    }

    /** Called only after Minecraft has successfully created and enumerated the replacement swapchain. */
    public void configured(GpuSurface.Configuration configuration, int actualWidth, int actualHeight,
            int nativeFormat, int buffers) {
        configuring = false;
        configured = true;
        // The successfully created physical swapchain is authoritative. Streamline receives the visible
        // render target separately through its extent-only backbuffer tag.
        width = actualWidth;
        height = actualHeight;
        format = nativeFormat;
        imageCount = buffers;
        generation++;
        RtDlssFg.INSTANCE.onSwapchainConfigured(width, height, format, imageCount, vsync, pluginForSwapchain,
                generation);
        CausticaMod.LOGGER.info(
                "Streamline swapchain generation {}: {}x{}, format={}, images={}, plugin={}, presentMode={}, vsyncRequested={}, mailboxVsyncCompatibility={}",
                generation, width, height, format, imageCount, pluginForSwapchain, presentMode, vsyncRequested,
                mailboxVsyncCompatibility);
    }

    public void configureFailed() {
        configuring = false;
        configured = false;
        RtDlssFg.INSTANCE.onSwapchainConfigurationFailed();
    }

    public void closing() {
        configured = false;
        configuring = false;
        RtDlssFg.INSTANCE.suspendForSwapchainChange();
    }

    public boolean configured() {
        return configured;
    }

    public boolean configuring() {
        return configuring;
    }

    public long generation() {
        return generation;
    }

    public boolean vsyncRequested() {
        return vsyncRequested;
    }

    public boolean mailboxVsyncCompatibility() {
        return mailboxVsyncCompatibility;
    }

    public boolean mailboxSupported() {
        return mailboxSupported;
    }

    public String presentMode() {
        return presentMode == null ? "unknown" : presentMode.name();
    }

    private static boolean isVsyncRequested() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.options != null && minecraft.options.enableVsync().get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isVsyncConfiguration(GpuSurface.Configuration configuration) {
        return configuration.presentMode() == GpuSurface.PresentMode.FIFO
                || configuration.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
    }
}
