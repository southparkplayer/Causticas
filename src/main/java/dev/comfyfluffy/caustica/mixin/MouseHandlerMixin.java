package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.client.OfflineGroundTruth;
import dev.comfyfluffy.caustica.client.UltraScreenshot;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emits the Reflex trigger-flash marker for real left-button press samples. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void caustica$freezeUltraScreenshotCamera(double frameTime, CallbackInfo ci) {
        if (UltraScreenshot.INSTANCE.active() || OfflineGroundTruth.INSTANCE.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"))
    private void caustica$reflexTriggerFlash(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (action == 1 && button.button() == 0
                && RtDlssFg.INSTANCE.flashIndicatorDriverControlled()) {
            RtDlssFg.INSTANCE.triggerFlash();
        }
    }
}
