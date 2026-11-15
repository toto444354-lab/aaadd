package tw.soulpressure.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tw.soulpressure.SoulPressureClient;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {
    @Shadow
    public abstract Identifier getTexture(T entity);

    @Inject(method = "render", at = @At("HEAD"))
    private void soulpressure$startGrayRender(T livingEntity, float f, float g,
                                              MatrixStack matrixStack,
                                              VertexConsumerProvider vertexConsumerProvider,
                                              int i, CallbackInfo ci) {
        if (SoulPressureClient.isClientFrozen(livingEntity.getUuid())) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(0.72F, 0.74F, 0.78F, 0.48F);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void soulpressure$endGrayRender(T livingEntity, float f, float g,
                                            MatrixStack matrixStack,
                                            VertexConsumerProvider vertexConsumerProvider,
                                            int i, CallbackInfo ci) {
        if (SoulPressureClient.isClientFrozen(livingEntity.getUuid())) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
        }
    }

    @Inject(method = "getRenderLayer", at = @At("HEAD"), cancellable = true)
    private void soulpressure$forceTranslucent(T entity, boolean showBody, boolean translucent, boolean showOutline,
                                               CallbackInfoReturnable<RenderLayer> cir) {
        if (SoulPressureClient.isClientFrozen(entity.getUuid())) {
            cir.setReturnValue(RenderLayer.getEntityTranslucent(getTexture(entity)));
        }
    }
}
