package ru.ruby.skin.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.ruby.skin.RubySkinClient;
import ru.ruby.skin.model.BBModelRenderer;

/**
 * Swaps the vanilla player model submission for our Blockbench model. Everything else of the vanilla
 * pipeline (rotations, crouch/swim transforms, armor / held item / cape layers, name tag) keeps working.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Redirect(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"
            )
    )
    private <S> void rubyskin$submitModel(SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack poseStack,
                                          RenderType renderType, int light, int overlay, int tint,
                                          TextureAtlasSprite sprite, int outlineColor,
                                          ModelFeatureRenderer.CrumblingOverlay crumbling) {
        if (state instanceof AvatarRenderState avatar && !avatar.isSpectator && RubySkinClient.shouldReplace(avatar.id)) {
            BBModelRenderer renderer = RubySkinClient.renderer();
            if (renderer != null) {
                BBModelRenderer.Anim anim = BBModelRenderer.Anim.from(avatar,
                        RubySkinClient.config().animate, RubySkinClient.config().scale);
                renderer.submit(poseStack, collector, anim, light, overlay);
                return;
            }
        }
        collector.submitModel(model, state, poseStack, renderType, light, overlay, tint, sprite, outlineColor, crumbling);
    }
}
