package io.github.jerry5678912.metropolispatch.mixin;

import io.github.jerry5678912.metropolispatch.itv.ITVModelHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "team.dovecotmc.metropolis.block.BlockITVMonitor", remap = false)
public abstract class ITVBlockRenderShapeMixin {
    /** Runtime SRG name for AbstractBlock#getRenderShape(BlockState) on 1.20.1. */
    @Inject(method = {"m_7514_", "method_9604"}, at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void metropolisPatch$renderAsTerrainModel(CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(ITVModelHooks.modelRenderShape());
    }
}
