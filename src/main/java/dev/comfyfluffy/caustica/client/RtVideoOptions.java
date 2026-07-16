package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
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
     private static final List<Integer> DEBUG_VIEW_ORDER = List.of(
             0, 1, 2, 3, 4, 5, 6, 7,
             CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON);

    /** A tone-mapping widget plus the value it should restore when Shift+Left-clicked. */
    public record TonemapControl(OptionInstance<?> option, Runnable reset) {
        public void resetToDefault() {
            reset.run();
        }
    }

    private RtVideoOptions() {
    }

    private static <T> TonemapControl control(OptionInstance<T> option, T defaultValue) {
        return new TonemapControl(option, () -> option.set(defaultValue));
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

    public static Button frameGenerationButton(Screen parent, Runnable beforeOpen) {
        return Button.builder(
                Component.translatable("caustica.options.rt.frameGeneration"),
                button -> {
                    beforeOpen.run();
                    Minecraft.getInstance().setScreenAndShow(
                            new RtFrameGenerationOptionsScreen(parent, Minecraft.getInstance().options));
                })
            .width(Button.BIG_WIDTH)
            .tooltip(Tooltip.create(Component.translatable("caustica.options.rt.frameGeneration.tooltip")))
            .build();
    }

    public static OptionInstance<?>[] frameGenerationOptions() {
        List<String> modes = List.of("off", "fixed");
        int reportedMaximum = RtDlssFg.INSTANCE.multiFrameCountMax();
        // Streamline 2.12 supports at most five generated frames (6x). Until the plugin has been attached
        // to a swapchain and reports this adapter's cap, keep the complete range discoverable; submission
        // clamps the saved selection to the reported limit before it reaches Streamline.
        int maximumGenerated = reportedMaximum > 0 ? reportedMaximum : 5;
        java.util.ArrayList<OptionInstance<?>> options = new java.util.ArrayList<>();
        options.add(new OptionInstance<>(
                "caustica.options.rt.fg.mode",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fg.mode.tooltip")),
                (caption, value) -> Component.translatable("caustica.options.rt.fg.mode." + value),
                new OptionInstance.Enum<>(modes, Codec.STRING),
                modes.contains(CausticaConfig.Rt.Fg.configuredMode())
                        ? CausticaConfig.Rt.Fg.configuredMode() : "fixed",
                CausticaConfig.Rt.Fg::setMode));
        options.add(new OptionInstance<>(
                "caustica.options.rt.fg.multiplier",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fg.multiplier.tooltip")),
                (caption, generated) -> Options.genericValueLabel(caption,
                        Component.literal((generated + 1) + "x")),
                new OptionInstance.IntRange(1, maximumGenerated),
                Math.clamp(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.configuredValue(), 1, maximumGenerated),
                CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT::set));
        options.add(bool("caustica.options.rt.fg.uiRecomposition", CausticaConfig.Rt.Fg.UI_RECOMPOSITION));
        options.add(bool("caustica.options.rt.fg.fullscreenMenu", CausticaConfig.Rt.Fg.FULLSCREEN_MENU_DETECTION));
        options.add(reflexMode());
        if (!StreamlineRuntime.productionVariant()) {
            options.add(bool("caustica.options.rt.fg.showInterpolated", CausticaConfig.Rt.Fg.SHOW_ONLY_INTERPOLATED));
        }
        return options.toArray(OptionInstance<?>[]::new);
    }

    private static OptionInstance<String> reflexMode() {
        String current = !CausticaConfig.Rt.Reflex.ENABLED.configuredValue() ? "off"
                : CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.configuredValue() ? "boost" : "on";
        return new OptionInstance<>(
                "caustica.options.rt.fg.reflex",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fg.reflex.tooltip")),
                (caption, value) -> Component.translatable("caustica.options.rt.fg.reflex." + value),
                new OptionInstance.Enum<>(List.of("off", "on", "boost"), Codec.STRING),
                current,
                value -> {
                    CausticaConfig.Rt.Reflex.ENABLED.set(!"off".equals(value));
                    CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.set("boost".equals(value));
                });
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
            torchIntensity(),
            psrMirrorDepth(),
            dlssRrEnabled(),
            dlssQuality(),
            debugView(),
        };
    }

    public static OptionInstance<?>[] firstPersonOptions() {
        return new OptionInstance<?>[] {
            bool("caustica.options.rt.firstPerson.enabled", CausticaConfig.Rt.FirstPerson.ENABLED),
            bool("caustica.options.rt.firstPerson.disableVanillaModel",
                    CausticaConfig.Rt.FirstPerson.DISABLE_VANILLA_MODEL),
            offset("caustica.options.rt.firstPerson.forward", CausticaConfig.Rt.FirstPerson.FORWARD_OFFSET, -30, 30),
            offset("caustica.options.rt.firstPerson.vertical", CausticaConfig.Rt.FirstPerson.VERTICAL_OFFSET, -30, 30),
            offset("caustica.options.rt.firstPerson.lateral", CausticaConfig.Rt.FirstPerson.LATERAL_OFFSET, -20, 20),
        };
    }

    public static TonemapControl[] tonemapOutputOptions() {
        return new TonemapControl[] {
            sdrTonemap(),
            hdrEnabled(),
            hdrTonemap(),
            hdrPaperWhite(),
            hdrPeak(),
        };
    }

    public static TonemapControl[] sdrAgxOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrAgxContrast", CausticaConfig.Rt.Sdr.AGX_CONTRAST, 100, 0, 200, 2),
            scaledFloat("caustica.options.rt.sdrAgxSaturation", CausticaConfig.Rt.Sdr.AGX_SATURATION, 100, 0, 300, 2),
        };
    }

    public static TonemapControl[] sdrPbrNeutralOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrPbrStartCompression", CausticaConfig.Rt.Sdr.PBR_START_COMPRESSION, 100, 0, 99, 2),
            scaledFloat("caustica.options.rt.sdrPbrDesaturation", CausticaConfig.Rt.Sdr.PBR_DESATURATION, 100, 0, 100, 2),
        };
    }

    public static TonemapControl[] sdrReinhardOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrReinhardWhitePoint", CausticaConfig.Rt.Sdr.REINHARD_WHITE_POINT, 10, 10, 200, 1),
        };
    }

    public static TonemapControl[] sdrAcesOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrAcesExposure", CausticaConfig.Rt.Sdr.ACES_EXPOSURE, 100, 0, 400, 2),
        };
    }

    public static TonemapControl[] sdrLottesOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrLottesContrast", CausticaConfig.Rt.Sdr.LOTTES_CONTRAST, 100, 10, 500, 2),
            scaledFloat("caustica.options.rt.sdrLottesShoulder", CausticaConfig.Rt.Sdr.LOTTES_SHOULDER, 100, 10, 500, 2),
            scaledFloat("caustica.options.rt.sdrLottesHdrMax", CausticaConfig.Rt.Sdr.LOTTES_HDR_MAX, 10, 10, 640, 1),
            scaledFloat("caustica.options.rt.sdrLottesMidIn", CausticaConfig.Rt.Sdr.LOTTES_MID_IN, 100, 1, 100, 2),
            scaledFloat("caustica.options.rt.sdrLottesMidOut", CausticaConfig.Rt.Sdr.LOTTES_MID_OUT, 100, 1, 100, 2),
        };
    }

    public static TonemapControl[] sdrFrostbiteOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrFrostbiteLinearEnd", CausticaConfig.Rt.Sdr.FROSTBITE_LINEAR_END, 100, 0, 100, 2),
            scaledFloat("caustica.options.rt.sdrFrostbiteShoulderStrength", CausticaConfig.Rt.Sdr.FROSTBITE_SHOULDER_STRENGTH, 100, 0, 800, 2),
        };
    }

    public static TonemapControl[] sdrUncharted2Options() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrUnchartedA", CausticaConfig.Rt.Sdr.UNCHARTED_A, 100, 1, 100, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedB", CausticaConfig.Rt.Sdr.UNCHARTED_B, 100, 1, 200, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedC", CausticaConfig.Rt.Sdr.UNCHARTED_C, 100, 0, 100, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedD", CausticaConfig.Rt.Sdr.UNCHARTED_D, 100, 1, 200, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedE", CausticaConfig.Rt.Sdr.UNCHARTED_E, 100, 0, 100, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedF", CausticaConfig.Rt.Sdr.UNCHARTED_F, 100, 1, 200, 2),
            scaledFloat("caustica.options.rt.sdrUnchartedWhitePoint", CausticaConfig.Rt.Sdr.UNCHARTED_WHITE_POINT, 10, 10, 320, 1),
        };
    }

    public static TonemapControl[] sdrGtOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrGtContrast", CausticaConfig.Rt.Sdr.GT_CONTRAST, 100, 10, 400, 2),
            scaledFloat("caustica.options.rt.sdrGtLinearStart", CausticaConfig.Rt.Sdr.GT_LINEAR_START, 100, 1, 99, 2),
            scaledFloat("caustica.options.rt.sdrGtLinearLength", CausticaConfig.Rt.Sdr.GT_LINEAR_LENGTH, 100, 1, 400, 2),
            scaledFloat("caustica.options.rt.sdrGtBlackCurve", CausticaConfig.Rt.Sdr.GT_BLACK_CURVE, 100, 10, 400, 2),
            scaledFloat("caustica.options.rt.sdrGtBlackLift", CausticaConfig.Rt.Sdr.GT_BLACK_LIFT, 100, -50, 50, 2),
        };
    }

    public static TonemapControl[] sdrPsychoOptions() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrPsychoPeak", CausticaConfig.Rt.Sdr.PSYCHO_PEAK, 10, 5, 80, 1),
        };
    }

    /** PsychoV23 controls: SDR has its own peak; compression and gamut stages are shared across outputs. */
    public static TonemapControl[] sdrPsychoV23Options() {
        return new TonemapControl[] {
            scaledFloat("caustica.options.rt.sdrPsychoV23Peak", CausticaConfig.Rt.Sdr.PSYCHOV23_PEAK,
                    10, 5, 80, 1),
            psychoV23Compression("caustica.options.rt.psychoV23Compression",
                    CausticaConfig.Rt.PsychoV23.COMPRESSION),
            scaledFloat("caustica.options.rt.psychoV23GamutCompression",
                    CausticaConfig.Rt.PsychoV23.GAMUT_COMPRESSION, 100, 0, 100, 2),
        };
    }

    /** Controls shared by the legacy PsychoV and PsychoV23 maps on both output paths. */
    public static TonemapControl[] psychoOptions() {
        return new TonemapControl[] {
            percent("caustica.options.rt.hdrPsychoHighlights", CausticaConfig.Rt.Hdr.PSYCHO_HIGHLIGHTS, 0, 300),
            percent("caustica.options.rt.hdrPsychoShadows", CausticaConfig.Rt.Hdr.PSYCHO_SHADOWS, 0, 300),
            percent("caustica.options.rt.hdrPsychoContrast", CausticaConfig.Rt.Hdr.PSYCHO_CONTRAST, 0, 300),
            percent("caustica.options.rt.hdrPsychoPurity", CausticaConfig.Rt.Hdr.PSYCHO_PURITY, 0, 300),
            percent("caustica.options.rt.hdrPsychoHueRestore", CausticaConfig.Rt.Hdr.PSYCHO_HUE_RESTORE, 0, 100),
            percent("caustica.options.rt.hdrPsychoAdaptContrast", CausticaConfig.Rt.Hdr.PSYCHO_ADAPT_CONTRAST, 0, 300),
            scaledFloat("caustica.options.rt.hdrPsychoConeExponent",
                    CausticaConfig.Rt.Hdr.PSYCHO_CONE_EXPONENT, 100, 10, 300, 2),
        };
    }

    /** Controls used only by the original HDR PsychoV white-compression path. */
    public static TonemapControl[] hdrPsychoOptions() {
        return new TonemapControl[] {
            percent("caustica.options.rt.hdrPsychoBleaching", CausticaConfig.Rt.Hdr.PSYCHO_BLEACHING, 0, 100),
            scaledFloat("caustica.options.rt.hdrPsychoClipPoint", CausticaConfig.Rt.Hdr.PSYCHO_CLIP_POINT, 10, 10, 10000, 1),
            psychoWhiteCurve(),
        };
    }

    public static OptionInstance<?>[] generalOptions() {
        return new OptionInstance<?>[] {
            bool("caustica.options.enabled", CausticaConfig.Rt.ENABLED),
            bool("caustica.options.frameStats", CausticaConfig.Rt.FrameStats.ENABLED),
            exposureMode(),
            manualEv(),
        };
    }

    public static Button causticaButton(Screen parent, Runnable beforeOpen) {
        return Button.builder(Component.translatable("caustica.options.title"), button -> {
            beforeOpen.run();
            Minecraft.getInstance().setScreenAndShow(
                    new CausticaOptionsScreen(parent, Minecraft.getInstance().options));
        }).width(Button.BIG_WIDTH)
                .tooltip(Tooltip.create(Component.translatable("caustica.options.tooltip")))
                .build();
    }

    public static TonemapControl[] legacyPsychoOptions() {
        TonemapControl[] sdr = sdrPsychoOptions();
        TonemapControl[] hdr = hdrPsychoOptions();
        TonemapControl[] combined = new TonemapControl[sdr.length + hdr.length];
        System.arraycopy(sdr, 0, combined, 0, sdr.length);
        System.arraycopy(hdr, 0, combined, sdr.length, hdr.length);
        return combined;
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.configuredValue(),
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
            Math.clamp(Math.round(setting.configuredValue() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static TonemapControl sdrTonemap() {
        StringSetting setting = CausticaConfig.Rt.Sdr.TONEMAP_MODE;
        OptionInstance<String> option = new OptionInstance<>(
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
                    CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV,
                    CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV23), Codec.STRING),
            setting.configuredValue(),
            setting::set);
        return control(option, setting.defaultValue());
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.configuredValue(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.configuredValue(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(
                Math.round((float) Math.toDegrees(setting.configuredValue()) * 10.0f), 1, 50);
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

    private static OptionInstance<Integer> torchIntensity() {
        FloatSetting setting = CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER;
        return new OptionInstance<>(
            "caustica.options.rt.torchIntensity",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.torchIntensity.tooltip")),
            (caption, step) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1f", step / 10.0f))),
            new OptionInstance.IntRange(0, 10),
            Math.clamp(Math.round(setting.configuredValue() * 10.0f), 0, 10),
            step -> setting.set(step / 10.0f));
    }

    private static OptionInstance<Integer> psrMirrorDepth() {
        IntSetting setting = CausticaConfig.Rt.Composite.PSR_MAX_MIRRORS;
        return new OptionInstance<>(
            "caustica.options.rt.psrMirrorDepth",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.psrMirrorDepth.tooltip")),
            (caption, depth) -> Options.genericValueLabel(caption, depth),
            new OptionInstance.IntRange(1, 32),
            Math.clamp(setting.configuredValue(), 1, 32),
            setting::set);
    }

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.configuredValue())
                ? setting.configuredValue() : 0;
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

    private static OptionInstance<Boolean> dlssRrEnabled() {
        return OptionInstance.createBoolean(
                "caustica.options.rt.dlssRr",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssRr.tooltip")),
                CausticaConfig.Rt.DlssRr.ENABLED.configuredValue(),
                enabled -> {
                    CausticaConfig.Rt.DlssRr.ENABLED.set(enabled);
                    RtDlssRr.INSTANCE.requestHistoryReset();
                });
    }

    private static TonemapControl hdrEnabled() {
        BooleanSetting setting = CausticaConfig.Rt.Hdr.ENABLED;
        return control(bool("caustica.options.rt.hdr", setting), setting.defaultValue());
    }

    private static TonemapControl hdrTonemap() {
        StringSetting setting = CausticaConfig.Rt.Hdr.TONEMAP_MODE;
        OptionInstance<String> option = new OptionInstance<>(
            "caustica.options.rt.hdrTonemap",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrTonemap.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.hdrTonemap." + value),
            new OptionInstance.Enum<>(List.of(
                    CausticaConfig.Rt.Hdr.TONEMAP_EETF,
                    CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV,
                    CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV23,
                    CausticaConfig.Rt.Hdr.TONEMAP_CAUSTICA), Codec.STRING),
            setting.configuredValue(),
            setting::set);
        return control(option, setting.defaultValue());
    }

    private static TonemapControl psychoWhiteCurve() {
        StringSetting setting = CausticaConfig.Rt.Hdr.PSYCHO_WHITE_CURVE;
        OptionInstance<String> option = new OptionInstance<>(
            "caustica.options.rt.hdrPsychoWhiteCurve",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPsychoWhiteCurve.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.hdrPsychoWhiteCurve." + value),
            new OptionInstance.Enum<>(List.of("naka-rushton", "neutwo"), Codec.STRING),
            setting.configuredValue(),
            setting::set);
        return control(option, setting.defaultValue());
    }

    private static TonemapControl hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.configuredValue()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
        return control(option, Math.clamp(Math.round(setting.defaultValue()), 80, 1000));
    }

    private static TonemapControl hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.configuredValue()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
        return control(option, Math.clamp(Math.round(setting.defaultValue()), 80, 10000));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7,
                    CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON), Codec.INT),
            Math.clamp(setting.configuredValue(), 0,
                    CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON),
            setting::set);
    }

    private static TonemapControl percent(String captionKey, FloatSetting setting, int min, int max) {
        return scaledFloat(captionKey, setting, 100, min, max, 2);
    }

    private static OptionInstance<Integer> offset(String captionKey, FloatSetting setting, int min, int max) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, centimeters) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%+.2f m", centimeters / 100.0f))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.configuredValue() * 100.0f), min, max),
            centimeters -> setting.set(centimeters / 100.0f));
    }

    private static TonemapControl scaledFloat(
            String captionKey, FloatSetting setting, int scale, int min, int max, int decimals) {
        OptionInstance<Integer> option = new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.literal(decimal(value / (float) scale, decimals))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.configuredValue() * scale), min, max),
            value -> setting.set(value / (float) scale));
        return control(option, Math.clamp(Math.round(setting.defaultValue() * scale), min, max));
    }

    private static TonemapControl psychoV23Compression(String captionKey, FloatSetting setting) {
        int min = 0;
        int max = 800;
        int scale = 100;
        OptionInstance<Integer> option = new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    value == 0
                            ? Component.translatable(captionKey + ".auto")
                            : Component.literal(decimal(value / (float) scale, 2))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.configuredValue() * scale), min, max),
            value -> setting.set(value / (float) scale));
        return control(option, Math.clamp(Math.round(setting.defaultValue() * scale), min, max));
    }

    private static OptionInstance<Integer> intOption(
            String captionKey, IntSetting setting, int min, int max) {
        return new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, value),
                new OptionInstance.IntRange(min, max),
                Math.clamp(setting.configuredValue(), min, max),
                setting::set);
    }

    private static OptionInstance<Integer> enumInt(
            String captionKey, IntSetting setting, List<Integer> values) {
        int initial = values.contains(setting.configuredValue())
                ? setting.configuredValue() : values.getFirst();
        return new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, value),
                new OptionInstance.Enum<>(values, Codec.INT),
                initial,
                setting::set);
    }

    private static String decimal(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.configuredValue(),
            setting::set);
    }
}
