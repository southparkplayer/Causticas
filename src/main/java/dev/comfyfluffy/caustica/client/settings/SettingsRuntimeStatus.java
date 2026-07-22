package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.RuntimeSetting;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** Shared concise status used by both unified and dedicated settings screens. */
public final class SettingsRuntimeStatus {
    private SettingsRuntimeStatus() {
    }

    public static Component hdr() {
        return Component.literal(RtHdr.statusDescription());
    }

    public static Component frameGeneration() {
        RtDlssFg fg = RtDlssFg.INSTANCE;
        Component availability = fg.isActive() ? Component.translatable("caustica.feature.active")
                : fg.isAvailable() && fg.hasGeneratedFrames()
                ? Component.translatable("caustica.feature.verifiedSuspendedMenu")
                : fg.isAvailable() && RtDlssFg.requested() ? Component.literal(fg.submissionStatus())
                : fg.isAvailable() ? Component.translatable("caustica.feature.available")
                : fg.unavailableReason().isBlank() ? Component.translatable("caustica.feature.unavailable")
                : Component.literal(fg.unavailableReason());
        return fg.multiFrameCountMax() > 0
                ? Component.translatable("caustica.options.rt.fg.status.maximum", availability,
                fg.multiFrameCountMax() + 1)
                : Component.translatable("caustica.options.rt.fg.status", availability);
    }

    public static Component vsync(Options options) {
        Component state = !options.enableVsync().get()
                ? Component.translatable("caustica.options.rt.fg.vsync.off")
                : StreamlineSwapchainCoordinator.INSTANCE.mailboxSupported()
                ? Component.translatable("caustica.options.rt.fg.vsync.mailbox")
                : Component.translatable("caustica.options.rt.fg.vsync.fifoFallback");
        return Component.translatable("caustica.options.rt.fg.vsync.status", state);
    }

    public static int maximumGeneratedFrames() {
        int reported = RtDlssFg.INSTANCE.multiFrameCountMax();
        return reported > 0 ? reported : 5;
    }

    public static Component configuredEffective(Component label, RuntimeSetting<?> setting) {
        Component configured = value(setting.configuredValue());
        Component effective = value(setting.get());
        return setting.isOverridden()
                ? Component.translatable("caustica.options.status.overridden", label, configured, effective,
                Component.literal(String.valueOf(setting.overrideSource())))
                : Component.translatable("caustica.options.status.effective", label, effective);
    }

    public static int overrideCount() {
        return CausticaConfig.activeOverrides().size();
    }

    private static Component value(Object value) {
        return value instanceof Boolean booleanValue
                ? Component.translatable(booleanValue ? "options.on" : "options.off")
                : Component.literal(String.valueOf(value));
    }
}
