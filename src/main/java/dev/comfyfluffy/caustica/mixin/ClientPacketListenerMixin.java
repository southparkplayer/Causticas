package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.RtComposite;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.clock.WorldClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detects command-driven clock discontinuities without treating ordinary TOD progression as a reset. */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleSetTime", at = @At("HEAD"))
    private void caustica$observeTimeUpdate(ClientboundSetTimePacket packet, CallbackInfo ci) {
        packet.clockUpdates().forEach((clock, state) -> {
            if (clock.is(WorldClocks.OVERWORLD)) {
                RtComposite.INSTANCE.observeServerTime(packet.gameTime(), state.totalTicks());
            }
        });
    }
}
