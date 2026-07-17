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
    private boolean physicalFifo;
    private boolean vsyncRequested;
    private boolean mailboxSupported;
    private boolean mailboxVsyncCompatibility;
    private GpuSurface.PresentMode requestedPresentMode;
    private GpuSurface.PresentMode presentMode;
    private int width;
    private int height;
    private int format;
    private int imageCount;
    private long generation;
    private boolean reconfigureRequested;
    private StreamlineRuntime.SwapchainTrace nativeSwapchain = StreamlineRuntime.SwapchainTrace.unknown();

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
        nativeSwapchain = StreamlineRuntime.SwapchainTrace.unknown();
        RtDlssFg.INSTANCE.suspendForSwapchainChange();
    }

    /**
     * Preserve Minecraft's VSync option while keeping Vulkan DLSS-G on a supported presentation path.
     *
     * <p>Streamline 2.12 cannot run Vulkan DLSS-G on a FIFO swapchain. Use MAILBOX when VSync is
     * requested: MAILBOX remains tear-free because display replacement occurs at vblank and it owns
     * presentation cadence without an application-side frame limiter.
     * A surface without MAILBOX stays on the requested FIFO mode and DLSS-G fails closed.</p>
     */
    public GpuSurface.Configuration normalizeConfiguration(GpuSurface.Configuration configuration,
            Collection<GpuSurface.PresentMode> supportedPresentModes) {
        requestedPresentMode = configuration.presentMode();
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
        CausticaMod.LOGGER.debug(
                "DLSS-G VSync compatibility: requested {} -> MAILBOX (no application frame limiter)",
                configuration.presentMode());
        return new GpuSurface.Configuration(configuration.width(), configuration.height(), presentMode);
    }

    /** Called after the old swapchain is destroyed and immediately before replacement creation. */
    public boolean prepareReplacement(GpuSurface.Configuration configuration) {
        physicalFifo = isVsyncConfiguration(configuration);
        presentMode = configuration.presentMode();
        boolean desiredPlugin = CausticaConfig.Rt.Fg.requested() && !physicalFifo;
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
        nativeSwapchain = StreamlineRuntime.nativeSwapchainTrace();
        RtDlssFg.INSTANCE.onSwapchainConfigured(width, height, format, imageCount, vsyncRequested,
                physicalFifo, pluginForSwapchain, generation);
        CausticaMod.LOGGER.debug(
                "Streamline swapchain generation {}: {}x{}, format={}, applicationImages={}, plugin={}, requestedPresentMode={}, normalizedPresentMode={}, vsyncRequested={}, mailboxVsyncCompatibility={}, nativePresentMode={} (value={}), requestedNativeMinImages={}, proxyVisibleImages={}, nativeCreateResult={}, nativeProxyDispatch={}, nativeSwapchain={}",
                generation, width, height, format, imageCount, pluginForSwapchain, requestedPresentMode, presentMode, vsyncRequested,
                mailboxVsyncCompatibility, nativeSwapchain.presentMode(), nativeSwapchain.presentModeValue(),
                nativeSwapchain.minImageCount(), nativeSwapchain.imageCount(), nativeSwapchain.createResult(),
                nativeSwapchain.proxyDispatch(), nativeSwapchain.handleHex());
        if (mailboxVsyncCompatibility && nativeSwapchain.presentModeKnown()
                && !"MAILBOX".equals(nativeSwapchain.presentMode())) {
            CausticaMod.LOGGER.error(
                    "DLSS-G MAILBOX VSync proof failed: requested MAILBOX but native proxy observed {} (value={})",
                    nativeSwapchain.presentMode(), nativeSwapchain.presentModeValue());
        }
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

    public String requestedPresentMode() {
        return requestedPresentMode == null ? "unknown" : requestedPresentMode.name();
    }

    public String normalizedPresentMode() {
        return presentMode();
    }

    public String nativePresentMode() {
        return nativeSwapchain.presentMode();
    }

    public int nativePresentModeValue() {
        return nativeSwapchain.presentModeValue();
    }

    public boolean nativePresentModeKnown() {
        return nativeSwapchain.presentModeKnown();
    }

    /** Requested minimum in the intercepted native create info; not a physical-image enumeration. */
    public int requestedNativeMinImageCount() {
        return nativeSwapchain.minImageCount();
    }

    /** Image count returned through the Streamline proxy to the application. */
    public int proxyVisibleImageCount() {
        return nativeSwapchain.imageCount();
    }

    public int applicationImageCount() {
        return imageCount;
    }

    public int nativeCreateResult() {
        return nativeSwapchain.createResult();
    }

    public boolean nativeProxyDispatch() {
        return nativeSwapchain.proxyDispatch();
    }

    public String nativeSwapchainHandle() {
        return nativeSwapchain.handleHex();
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
