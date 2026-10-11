package ru.ruby.skin.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ruby.skin.RubySkinClient;

/** Hides worn armor on players rendered with the custom model. */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void rubyskin$hideArmor(PoseStack poseStack, SubmitNodeCollector collector, int light, HumanoidRenderState state,
                                    float yRot, float xRot, CallbackInfo ci) {
        if (RubySkinClient.config().hideArmor && state instanceof AvatarRenderState avatar && RubySkinClient.shouldReplace(avatar.id)) {
            ci.cancel();
        }
    }
}
