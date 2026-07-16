package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.OfflineGroundTruth;
import dev.comfyfluffy.caustica.client.UltraScreenshot;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Vanilla's video-settings screen already shows a red "restart required" banner
 * ({@code Options.isRestartRequiredToApplyVideoSettings}) when the Graphics API or exclusive-fullscreen
 * choice differs from what was active at startup. Our HDR toggle has the exact same constraint — the
 * swapchain's pixel format is fixed at surface-creation time — so this folds it into the same check,
 * reusing vanilla's existing banner instead of building a parallel one.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow @Final @Mutable public KeyMapping[] keyMappings;

    @Inject(method = "<init>", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;",
            shift = At.Shift.AFTER))
    private void caustica$addUltraScreenshotKey(CallbackInfo ci) {
        int originalLength = this.keyMappings.length;
        this.keyMappings = Arrays.copyOf(this.keyMappings, originalLength + 2);
        this.keyMappings[originalLength] = UltraScreenshot.KEY;
        this.keyMappings[originalLength + 1] = OfflineGroundTruth.KEY;
    }

    @ModifyReturnValue(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"))
    private boolean caustica$alsoRestartForHdr(boolean original) {
        return original || CausticaConfig.Rt.Hdr.pendingRestart();
    }
}
