package io.github.jerry5678912.metropolispatch.mixin;

import io.github.jerry5678912.metropolispatch.itv.ITVModelHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(targets = "net.minecraft.client.renderer.block.BlockModelShaper", remap = false)
public abstract class ITVModelShaperMixin {
    /** Runtime SRG name for BlockModelShaper#replaceCache(Map) on 1.20.1. */
    @Inject(method = "m_245515_", at = @At("TAIL"), require = 1, remap = false)
    private void metropolisPatch$wrapCachedITVModels(Map<?, ?> models, CallbackInfo ci) {
        ITVModelHooks.wrapModelCache(this);
    }

}
