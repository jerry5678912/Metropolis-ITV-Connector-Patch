package io.github.jerry5678912.metropolispatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The ITV is now rendered through the normal terrain model path. Suppress the
 * original Metropolis BER so geometry is not emitted twice and the old manual
 * world-tessellation crash path cannot run.
 */
@Mixin(targets = "team.dovecotmc.metropolis.client.block.entity.ITVMonitorBlockEntityRenderer", remap = false)
public abstract class ITVMonitorRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void metropolisPatch$suppressLegacyRenderer(CallbackInfo ci) {
        ci.cancel();
    }
}
