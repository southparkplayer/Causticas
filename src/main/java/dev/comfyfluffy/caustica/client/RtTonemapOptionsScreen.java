package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Tone-mapping mode and tuning controls for Caustica RT. */
public final class RtTonemapOptionsScreen extends OptionsSubScreen {
    public RtTonemapOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.rt.tonemapping.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.output"));
        this.list.addSmall(RtVideoOptions.tonemapOutputOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrAgx"));
        this.list.addSmall(RtVideoOptions.sdrAgxOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrPbrNeutral"));
        this.list.addSmall(RtVideoOptions.sdrPbrNeutralOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrReinhard"));
        this.list.addSmall(RtVideoOptions.sdrReinhardOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrAces"));
        this.list.addSmall(RtVideoOptions.sdrAcesOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrLottes"));
        this.list.addSmall(RtVideoOptions.sdrLottesOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrFrostbite"));
        this.list.addSmall(RtVideoOptions.sdrFrostbiteOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrUncharted2"));
        this.list.addSmall(RtVideoOptions.sdrUncharted2Options());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrGt"));
        this.list.addSmall(RtVideoOptions.sdrGtOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.sdrPsycho"));
        this.list.addSmall(RtVideoOptions.sdrPsychoOptions());

        this.list.addHeader(Component.translatable("caustica.options.rt.tonemapping.section.psychoShared"));
        this.list.addSmall(RtVideoOptions.psychoOptions());
    }

    @Override
    public void removed() {
        super.removed();
        CausticaConfig.save();
    }
}
