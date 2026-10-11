package ru.ruby.skin.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ruby.skin.RubySkinClient;
import ru.ruby.skin.model.BBModelRenderer;

/** Makes held items (ItemInHandLayer) follow the custom model's arms instead of the vanilla ones. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    @Inject(
            method = "translateToHand(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void rubyskin$translateToHand(HumanoidRenderState state, HumanoidArm arm, PoseStack poseStack, CallbackInfo ci) {
        if (state instanceof AvatarRenderState avatar && !avatar.isSpectator && RubySkinClient.shouldReplace(avatar.id)) {
            BBModelRenderer renderer = RubySkinClient.renderer();
            if (renderer != null) {
                BBModelRenderer.Anim anim = BBModelRenderer.Anim.from(avatar,
                        RubySkinClient.config().animate, RubySkinClient.config().scale);
                renderer.translateToHand(poseStack, anim, arm == HumanoidArm.RIGHT);
                ci.cancel();
            }
        }
    }
}
