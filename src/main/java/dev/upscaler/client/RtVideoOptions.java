package dev.upscaler.client;

import com.mojang.serialization.Codec;
import dev.upscaler.UpscalerConfig;
import dev.upscaler.UpscalerConfig.BooleanSetting;
import dev.upscaler.UpscalerConfig.FloatSetting;
import dev.upscaler.UpscalerConfig.IntSetting;
import dev.upscaler.UpscalerConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link UpscalerConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device,
 * pipeline, or buffer-pool rebuild (worker threads, render scale, OMM, max-entity capacities, PBR material
 * flags, DLSS-RR feature init) are intentionally left to the {@code -Dupscaler.*} startup surface.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            debugView(),
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = UpscalerConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "upscaler.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("upscaler.options.rt.exposureMode.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable("upscaler.options.rt.exposureMode." + value)),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = UpscalerConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "upscaler.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("upscaler.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = UpscalerConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "upscaler.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("upscaler.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = UpscalerConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "upscaler.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("upscaler.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("upscaler.options.rt.entities", UpscalerConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("upscaler.options.rt.particles", UpscalerConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("upscaler.options.rt.waterWaves", UpscalerConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = UpscalerConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "upscaler.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("upscaler.options.rt.debugView.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.translatable("upscaler.options.rt.debugView." + value)),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7), Codec.INT),
            Math.clamp(setting.value(), 0, 7),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
