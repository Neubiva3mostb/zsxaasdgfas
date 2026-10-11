package ru.ruby.skin.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ruby.skin.RubySkinClient;
import ru.ruby.skin.model.BBModelRenderer;

/** PlayerModel overrides translateToHand without calling super, so hook it directly. */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {

    @Inject(
            method = "translateToHand(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void rubyskin$translateToHand(AvatarRenderState state, HumanoidArm arm, PoseStack poseStack, CallbackInfo ci) {
        if (!state.isSpectator && RubySkinClient.shouldReplace(state.id)) {
            BBModelRenderer renderer = RubySkinClient.renderer();
            if (renderer != null) {
                BBModelRenderer.Anim anim = BBModelRenderer.Anim.from(state,
                        RubySkinClient.config().animate, RubySkinClient.config().scale);
                renderer.translateToHand(poseStack, anim, arm == HumanoidArm.RIGHT);
                ci.cancel();
            }
        }
    }
}
