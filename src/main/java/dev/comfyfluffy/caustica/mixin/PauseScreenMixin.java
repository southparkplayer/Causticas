package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CausticaOptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Caustica one click from gameplay without disturbing the vanilla pause-menu grid. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Shadow @Final private boolean showPauseMenu;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void caustica$addPauseMenuEntry(CallbackInfo ci) {
        if (!showPauseMenu) {
            return;
        }
        int buttonWidth = Math.min(204, Math.max(120, width - 16));
        addRenderableWidget(Button.builder(Component.translatable("caustica.options.title"), button ->
                        minecraft.setScreenAndShow(new CausticaOptionsScreen(this, minecraft.options)))
                .bounds(width - buttonWidth - 8, 8, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("caustica.options.tooltip")))
                .build());
    }
}
