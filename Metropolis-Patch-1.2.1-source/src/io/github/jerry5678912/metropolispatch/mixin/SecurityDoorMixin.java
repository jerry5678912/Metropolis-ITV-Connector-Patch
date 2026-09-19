package io.github.jerry5678912.metropolispatch.mixin;

import io.github.jerry5678912.metropolispatch.securitydoor.SecurityDoorGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "team.dovecotmc.old.metropolis.block.BlockSecurityDoor", remap = false)
public abstract class SecurityDoorMixin {
    /**
     * Runtime SRG name for Block#destroy(LevelAccessor, BlockPos, BlockState),
     * which Connector maps from Fabric intermediary method_9585 on 1.20.1.
     */
    @Inject(method = {"m_6786_", "method_9585"}, at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void metropolisPatch$guardMissingDoorHalf(
            LevelAccessor world, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!SecurityDoorGuard.canRunOriginal(world, pos, state)) {
            ci.cancel();
        }
    }
}
