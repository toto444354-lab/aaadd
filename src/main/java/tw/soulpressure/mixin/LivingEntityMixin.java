package tw.soulpressure.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tw.soulpressure.SoulPressureMod;

/** Cancels normal movement/AI-turn logic while the server has an entity pinned. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void soulpressure$haltMovement(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (!entity.getWorld().isClient && SoulPressureMod.isFrozen(entity.getUuid())) {
            ci.cancel();
        }
    }
}
