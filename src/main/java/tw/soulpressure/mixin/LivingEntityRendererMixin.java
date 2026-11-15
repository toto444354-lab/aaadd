package tw.soulpressure.mixin;

import net.minecraft.client.model.Model;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import tw.soulpressure.SoulPressureClient;

/**
 * Client-side ghostly gray, semitransparent appearance for soul-pressured mobs.
 *
 * Important: getTexture() is declared in EntityRenderer (the parent class),
 * NOT in LivingEntityRenderer. Do not @Shadow it here; doing so crashes on launch.
 * Use the renderer instance's public getTexture() method instead.
 *
 * Model alpha must be applied to vertex colors, not RenderSystem shader color,
 * because entity vertex buffers can be drawn after this render() call returns.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Inject(method = "getRenderLayer", at = @At("HEAD"), cancellable = true)
    private void soulpressure$forceTranslucentLayer(T entity, boolean showBody,
                                                     boolean translucent, boolean showOutline,
                                                     CallbackInfoReturnable<RenderLayer> cir) {
        if (SoulPressureClient.isClientFrozen(entity.getUuid())) {
            @SuppressWarnings("unchecked")
            LivingEntityRenderer<T, M> renderer = (LivingEntityRenderer<T, M>) (Object) this;
            cir.setReturnValue(RenderLayer.getEntityTranslucent(renderer.getTexture(entity)));
        }
    }

    // Some compiled builds call render via EntityModel, others via its Model parent.
    // Both injectors are optional; only the actual invocation is modified.
    @ModifyArgs(method = "render", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/render/entity/model/EntityModel;render(" +
        "Lnet/minecraft/client/util/math/MatrixStack;" +
        "Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V"), require = 0)
    private void soulpressure$grayVertexColorsEntityModel(Args args, T entity, float yaw,
                                                           float tickDelta, MatrixStack matrices,
                                                           VertexConsumerProvider buffers, int light) {
        if (SoulPressureClient.isClientFrozen(entity.getUuid())) applyGrayAlpha(args);
    }

    @ModifyArgs(method = "render", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/model/Model;render(" +
        "Lnet/minecraft/client/util/math/MatrixStack;" +
        "Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V"), require = 0)
    private void soulpressure$grayVertexColorsModel(Args args, T entity, float yaw,
                                                    float tickDelta, MatrixStack matrices,
                                                    VertexConsumerProvider buffers, int light) {
        if (SoulPressureClient.isClientFrozen(entity.getUuid())) applyGrayAlpha(args);
    }

    private static void applyGrayAlpha(Args args) {
        // Signature: render(matrices, vertices, light, overlay, red, green, blue, alpha)
        args.set(4, 0.68F);
        args.set(5, 0.70F);
        args.set(6, 0.73F);
        args.set(7, 0.46F);
    }
}
