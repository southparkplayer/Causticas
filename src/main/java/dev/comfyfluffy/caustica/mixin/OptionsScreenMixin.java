package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.CausticaOptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Makes Caustica discoverable from the general Options screen. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
            index = 0)
    private LayoutElement caustica$addOptionsEntry(LayoutElement vanillaGrid) {
        LinearLayout content = LinearLayout.vertical().spacing(4);
        content.defaultCellSetting().alignHorizontallyCenter();
        content.addChild(vanillaGrid);
        content.addChild(Button.builder(Component.translatable("caustica.options.title"), button ->
                        minecraft.setScreenAndShow(new CausticaOptionsScreen(this, minecraft.options)))
                .width(308)
                .tooltip(Tooltip.create(Component.translatable("caustica.options.tooltip")))
                .build());
        return content;
    }
}
