package ru.ruby.skin.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ruby.skin.RubySkinClient;
import ru.ruby.skin.model.BBModelRenderer;

/** First-person hands: draw the custom model's arm instead of the vanilla skin arm. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void rubyskin$rightHand(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, boolean sleeve, CallbackInfo ci) {
        if (rubyskin$hand(poseStack, collector, light, true)) ci.cancel();
    }

    @Inject(method = "renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void rubyskin$leftHand(PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin, boolean sleeve, CallbackInfo ci) {
        if (rubyskin$hand(poseStack, collector, light, false)) ci.cancel();
    }

    private static boolean rubyskin$hand(PoseStack poseStack, SubmitNodeCollector collector, int light, boolean right) {
        if (!RubySkinClient.config().firstPersonArm) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !RubySkinClient.shouldReplace(mc.player.getId())) return false;
        BBModelRenderer renderer = RubySkinClient.renderer();
        if (renderer == null) return false;
        renderer.submitFirstPersonArm(poseStack, collector, light, right);
        return true;
    }
}
