package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Full-width numerical controls kept one level below the minimal F7 workflow. */
public final class OfflineRendererAdvancedOptionsScreen extends OptionsSubScreen {
    public OfflineRendererAdvancedOptionsScreen(Screen parent, Options options) {
        super(parent, options, Component.literal("Offline Renderer — Advanced"));
    }

    @Override
    protected void addOptions() {
        for (var option : RtVideoOptions.offlineOptions()) {
            list.addBig(option);
        }
    }

    @Override
    public void removed() {
        super.removed();
        CausticaConfig.save();
    }
}
