package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds the RT option widgets shown from the vanilla Video Settings screen.
 */
public final class RtVideoOptions {
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private RtVideoOptions() {
    }

    public static OptionInstance<?>[] exposureOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
        };
    }

    public static Button tonemappingButton(Screen parent) {
        return tonemappingButton(parent, () -> {
        });
    }

    public static Button tonemappingButton(Screen parent, Runnable beforeOpen) {
        return Button.builder(
                Component.translatable("caustica.options.rt.tonemapping"),
                button -> {
                    beforeOpen.run();
                    Minecraft.getInstance().setScreenAndShow(
                            new RtTonemapOptionsScreen(parent, Minecraft.getInstance().options));
                })
            .width(Button.BIG_WIDTH)
            .tooltip(Tooltip.create(Component.translatable("caustica.options.rt.tonemapping.tooltip")))
            .build();
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by OptionsList.addSmall. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            spp(),
            maxBounces(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            dlssQuality(),
            debugView(),
        };
    }

    public static OptionInstance<?>[] tonemapOutputOptions() {
        return new OptionInstance<?>[] {
            sdrTonemap(),
            hdrEnabled(),
            hdrTonemap(),
            hdrPaperWhite(),
            hdrPeak(),
        };
    }

    public static OptionInstance<?>[] sdrAgxOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrAgxContrast", CausticaConfig.Rt.Sdr.AGX_CONTRAST, 100, 0, 200, 2),
            scaledFloat("caustica.options.rt.sdrAgxSaturation", CausticaConfig.Rt.Sdr.AGX_SATURATION, 100, 0, 300, 2),
        };
    }

    public static OptionInstance<?>[] sdrPbrNeutralOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrPbrStartCompression", CausticaConfig.Rt.Sdr.PBR_START_COMPRESSION, 100, 0, 99, 2),
            scaledFloat("caustica.options.rt.sdrPbrDesaturation", CausticaConfig.Rt.Sdr.PBR_DESATURATION, 100, 0, 100, 2),
        };
    }

    public static OptionInstance<?>[] sdrReinhardOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrReinhardWhitePoint", CausticaConfig.Rt.Sdr.REINHARD_WHITE_POINT, 10, 10, 200, 1),
        };
    }

    public static OptionInstance<?>[] sdrAcesOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrAcesExposure", CausticaConfig.Rt.Sdr.ACES_EXPOSURE, 100, 0, 400, 2),
        };
    }

    public static OptionInstance<?>[] sdrLottesOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrLottesContrast", CausticaConfig.Rt.Sdr.LOTTES_CONTRAST, 100, 10, 500, 2),
            scaledFloat("caustica.options.rt.sdrLottesShoulder", CausticaConfig.Rt.Sdr.LOTTES_SHOULDER, 100, 10, 500, 2),
            scaledFloat("caustica.options.rt.sdrLottesHdrMax", CausticaConfig.Rt.Sdr.LOTTES_HDR_MAX, 10, 10, 640, 1),
            scaledFloat("caustica.options.rt.sdrLottesMidIn", CausticaConfig.Rt.Sdr.LOTTES_MID_IN, 100, 1, 100, 2),
            scaledFloat("caustica.options.rt.sdrLottesMidOut", CausticaConfig.Rt.Sdr.LOTTES_MID_OUT, 100, 1, 100, 2),
        };
    }

    public static OptionInstance<?>[] sdrFrostbiteOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrFrostbiteLinearEnd", CausticaConfig.Rt.Sdr.FROSTBITE_LINEAR_END, 100, 0, 100, 2),
            scaledFloat("caustica.options.rt.sdrFrostbiteShoulderStrength", CausticaConfig.Rt.Sdr.FROSTBITE_SHOULDER_STRENGTH, 100, 0, 800, 2),
        };
    }

    public static OptionInstance<?>[] sdrUncharted2Options() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrUnchartedA", CausticaConfig.Rt.Sdr.UNCHARTED_A, 100, 1, 100, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedB", CausticaConfig.Rt.Sdr.UNCHARTED_B, 100, 1, 200, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedC", CausticaConfig.Rt.Sdr.UNCHARTED_C, 100, 0, 100, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedD", CausticaConfig.Rt.Sdr.UNCHARTED_D, 100, 1, 200, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedE", CausticaConfig.Rt.Sdr.UNCHARTED_E, 100, 0, 100, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedF", CausticaConfig.Rt.Sdr.UNCHARTED_F, 100, 1, 200, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedWhitePoint", CausticaConfig.Rt.Sdr.UNCHARTED_WHITE_POINT, 10, 10, 320, 1),
        };
    }

    public static OptionInstance<?>[] sdrGtOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrGtContrast", CausticaConfig.Rt.Sdr.GT_CONTRAST, 100, 10, 400, 2),
            scaledFloat("caustica.options.rt.sdrGtLinearStart", CausticaConfig.Rt.Sdr.GT_LINEAR_START, 100, 1, 99, 2),
            scaledFloat("caustica.options.rt.sdrGtLinearLength", CausticaConfig.Rt.Sdr.GT_LINEAR_LENGTH, 100, 1, 400, 2),
            scaledFloat("caustica.options.rt.sdrGtBlackCurve", CausticaConfig.Rt.Sdr.GT_BLACK_CURVE, 100, 10, 400, 2),
            scaledFloat("caustica.options.rt.sdrGtBlackLift", CausticaConfig.Rt.Sdr.GT_BLACK_LIFT, 100, -50, 50, 2),
        };
    }

    public static OptionInstance<?>[] sdrPsychoOptions() {
        return new OptionInstance<?>[] {
            scaledFloat("caustica.options.rt.sdrPsychoPeak", CausticaConfig.Rt.Sdr.PSYCHO_PEAK, 10, 5, 80, 1),
        };
    }

    public static OptionInstance<?>[] psychoOptions() {
        return new OptionInstance<?>[] {
            percent("caustica.options.rt.hdrPsychoHighlights", CausticaConfig.Rt.Hdr.PSYCHO_HIGHLIGHTS, 0, 300),
            percent("caustica.options.rt.hdrPsychoShadows", CausticaConfig.Rt.Hdr.PSYCHO_SHADOWS, 0, 300),
            percent("caustica.options.rt.hdrPsychoContrast", CausticaConfig.Rt.Hdr.PSYCHO_CONTRAST, 0, 300),
            percent("caustica.options.rt.hdrPsychoPurity", CausticaConfig.Rt.Hdr.PSYCHO_PURITY, 0, 300),
            percent("caustica.options.rt.hdrPsychoBleaching", CausticaConfig.Rt.Hdr.PSYCHO_BLEACHING, 0, 100),
            percent("caustica.options.rt.hdrPsychoHueRestore", CausticaConfig.Rt.Hdr.PSYCHO_HUE_RESTORE, 0, 100),
            percent("caustica.options.rt.hdrPsychoAdaptContrast", CausticaConfig.Rt.Hdr.PSYCHO_ADAPT_CONTRAST, 0, 300),
            scaledFloat("caustica.options.rt.hdrPsychoClipPoint", CausticaConfig.Rt.Hdr.PSYCHO_CLIP_POINT, 10, 10, 10000, 1),
            psychoWhiteCurve(),
            scaledFloat("caustica.options.rt.hdrPsychoConeExponent", CausticaConfig.Rt.Hdr.PSYCHO_CONE_EXPONENT, 100, 10, 300, 2),
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
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

    private static OptionInstance<String> sdrTonemap() {
        StringSetting setting = CausticaConfig.Rt.Sdr.TONEMAP_MODE;
        return new OptionInstance<>(
            "caustica.options.rt.sdrTonemap",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sdrTonemap.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.sdrTonemap." + value),
            new OptionInstance.Enum<>(List.of(
                    CausticaConfig.Rt.Sdr.TONEMAP_AGX,
                    CausticaConfig.Rt.Sdr.TONEMAP_PBR_NEUTRAL,
                    CausticaConfig.Rt.Sdr.TONEMAP_REINHARD,
                    CausticaConfig.Rt.Sdr.TONEMAP_ACES,
                    CausticaConfig.Rt.Sdr.TONEMAP_LOTTES,
                    CausticaConfig.Rt.Sdr.TONEMAP_FROSTBITE,
                    CausticaConfig.Rt.Sdr.TONEMAP_UNCHARTED2,
                    CausticaConfig.Rt.Sdr.TONEMAP_GT,
                    CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1f deg", tenths / 10.0f))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<String> hdrTonemap() {
        StringSetting setting = CausticaConfig.Rt.Hdr.TONEMAP_MODE;
        return new OptionInstance<>(
            "caustica.options.rt.hdrTonemap",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrTonemap.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.hdrTonemap." + value),
            new OptionInstance.Enum<>(List.of(
                    CausticaConfig.Rt.Hdr.TONEMAP_EETF,
                    CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV,
                    CausticaConfig.Rt.Hdr.TONEMAP_CAUSTICA), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<String> psychoWhiteCurve() {
        StringSetting setting = CausticaConfig.Rt.Hdr.PSYCHO_WHITE_CURVE;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPsychoWhiteCurve",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPsychoWhiteCurve.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.hdrPsychoWhiteCurve." + value),
            new OptionInstance.Enum<>(List.of("naka-rushton", "neutwo"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7), Codec.INT),
            Math.clamp(setting.value(), 0, 7),
            setting::set);
    }

    private static OptionInstance<Integer> percent(String captionKey, FloatSetting setting, int min, int max) {
        return scaledFloat(captionKey, setting, 100, min, max, 2);
    }

    private static OptionInstance<Integer> scaledFloat(
            String captionKey, FloatSetting setting, int scale, int min, int max, int decimals) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.literal(decimal(value / (float) scale, decimals))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.value() * scale), min, max),
            value -> setting.set(value / (float) scale));
    }

    private static String decimal(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
