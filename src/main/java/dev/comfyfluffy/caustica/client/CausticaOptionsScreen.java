package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Single discoverable home for every player-facing Caustica setting and specialized submenu. */
public final class CausticaOptionsScreen extends OptionsSubScreen {
    public CausticaOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.title"));
    }

    @Override
    protected void addOptions() {
        list.addHeader(Component.translatable("caustica.options.section.general"));
        for (var option : RtVideoOptions.generalOptions()) {
            list.addBig(option);
        }
        list.addBig(RtVideoOptions.tonemappingButton(this, list::applyUnsavedChanges));
        list.addBig(RtVideoOptions.frameGenerationButton(this, list::applyUnsavedChanges));

        list.addHeader(Component.translatable("caustica.options.rt.header"));
        for (var option : RtVideoOptions.runtimeOptions()) {
            list.addBig(option);
        }

        list.addHeader(Component.translatable("caustica.options.rt.firstPerson.header"));
        for (var option : RtVideoOptions.firstPersonOptions()) {
            list.addBig(option);
        }
    }

    @Override
    public void removed() {
        super.removed();
        CausticaConfig.save();
    }
}
