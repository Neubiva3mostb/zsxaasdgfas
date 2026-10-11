package ru.ruby.skin.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.ruby.skin.RubySkinClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a parsed {@link BBModel} in vanilla "model space" (the space a vanilla EntityModel is rendered in:
 * y pointing down, x mirrored, 1 unit = 1/16 block, origin at the top of a 32px tall humanoid).
 */
public class BBModelRenderer {
    private static final float DEG = (float) (Math.PI / 180.0);

    private final BBModel model;
    private final List<RBone> roots = new ArrayList<>();
    private final Identifier[] textureIds;
    private final RenderType[] renderTypes;
    private final boolean[] textureUsed;
    private boolean texturesUploaded = false;
    private static int instanceCounter = 0;
    private final int instance = instanceCounter++;

    /** Animation values captured at submit time (render states are reused between frames). */
    public record Anim(float headYaw, float headPitch, float limbSwing, float limbAmount, float age,
                       boolean passenger, float attackTime, boolean attackRight,
                       boolean rightHoldsItem, boolean leftHoldsItem, boolean animate, float scale) {
        public static Anim from(HumanoidRenderState s, boolean animate, float scale) {
            boolean attackRight = s.attackArm != HumanoidArm.LEFT;
            return new Anim(s.yRot, s.xRot, s.walkAnimationPos, s.walkAnimationSpeed, s.ageInTicks,
                    s.isPassenger, s.attackTime, attackRight,
                    s.rightArmPose != null && s.rightArmPose != HumanoidModel.ArmPose.EMPTY,
                    s.leftArmPose != null && s.leftArmPose != HumanoidModel.ArmPose.EMPTY,
                    animate, scale);
        }

        /** Body yaw produced by the attack (mining / placing) swing, vanilla formula. */
        public float attackBodyYaw() {
            if (attackTime <= 0f) return 0f;
            float yaw = Mth.sin(Mth.sqrt(attackTime) * Mth.TWO_PI) * 0.2f;
            return attackRight ? yaw : -yaw;
        }
    }

    enum Part { NONE, HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

    private static final class RBone {
        final String name;
        final Part part;
        final Vector3f origin;           // model space
        final Quaternionf rotation;      // model space (base rotation from Blockbench)
        final boolean hasRotation;
        final List<RBone> children = new ArrayList<>();
        final List<RCube> cubes = new ArrayList<>();

        RBone(BBModel.Bone b) {
            this.name = b.name;
            this.part = classify(b.name);
            this.origin = conv(b.origin[0], b.origin[1], b.origin[2]);
            this.rotation = bbQuat(b.rotation);
            this.hasRotation = b.rotation[0] != 0 || b.rotation[1] != 0 || b.rotation[2] != 0;
        }
    }

    private static final class RCube {
        final Vector3f origin;
        final Quaternionf rotation;
        final boolean hasRotation;
        final List<RQuad> quads = new ArrayList<>();

        RCube(BBModel.Cube c) {
            this.origin = conv(c.origin[0], c.origin[1], c.origin[2]);
            this.rotation = bbQuat(c.rotation);
            this.hasRotation = c.rotation[0] != 0 || c.rotation[1] != 0 || c.rotation[2] != 0;
        }
    }

    private static final class RQuad {
        final int texture;
        final float[] pos = new float[12]; // 4 vertices, model space
        final float[] uv = new float[8];
        final Vector3f normal;             // model space, local (before cube/bone rotation)

        RQuad(int texture, Vector3f normal) {
            this.texture = texture;
            this.normal = normal;
        }
    }

    public BBModelRenderer(BBModel model) {
        this.model = model;
        this.textureIds = new Identifier[model.textures.size()];
        this.renderTypes = new RenderType[model.textures.size()];
        this.textureUsed = new boolean[model.textures.size()];
        for (int i = 0; i < textureIds.length; i++) {
            textureIds[i] = Identifier.fromNamespaceAndPath(RubySkinClient.MOD_ID, "dynamic/" + instance + "_" + i);
            renderTypes[i] = RenderTypes.entityTranslucent(textureIds[i]);
        }
        prepareDecals(model);
        for (BBModel.Bone b : model.roots) {
            if (b.visible) roots.add(build(b));
        }
        // measure the arm length so held items end up in the hand
        List<RBone> path = new ArrayList<>();
        for (RBone root : roots) {
            if (findPath(root, Part.RIGHT_ARM, path)) break;
        }
        if (!path.isEmpty()) {
            RBone arm = path.get(path.size() - 1);
            float maxY = maxY(arm, arm.origin.y);
            float length = maxY - arm.origin.y;            // blocks, +y is down in model space
            armHandOffset = Math.max(0f, length - 10f / 16f);
        }
    }

    private static float maxY(RBone b, float cur) {
        for (RCube c : b.cubes) for (RQuad q : c.quads) for (int i = 0; i < 4; i++) cur = Math.max(cur, q.pos[i * 3 + 1]);
        for (RBone ch : b.children) cur = maxY(ch, cur);
        return cur;
    }

    // ------------------------------------------------------------------ building

    /** Blockbench space (x right, y up, 1 unit = 1/16 block) -> vanilla model space in blocks (x mirrored, y down). */
    private static Vector3f conv(float x, float y, float z) {
        return new Vector3f(-x / 16f, (24f - y) / 16f, z / 16f);
    }

    /** Blockbench ZYX euler (degrees, Blockbench space) -> quaternion in vanilla model space. */
    private static Quaternionf bbQuat(float[] rot) {
        return new Quaternionf().rotationZYX(rot[2] * DEG, -rot[1] * DEG, -rot[0] * DEG);
    }

    private static Part classify(String name) {
        if (name == null) return Part.NONE;
        String n = name.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        return switch (n) {
            case "head" -> Part.HEAD;
            case "body" -> Part.BODY;
            case "rightarm", "armright" -> Part.RIGHT_ARM;
            case "leftarm", "armleft" -> Part.LEFT_ARM;
            case "rightleg", "legright" -> Part.RIGHT_LEG;
            case "leftleg", "legleft" -> Part.LEFT_LEG;
            default -> Part.NONE;
        };
    }

    private RBone build(BBModel.Bone b) {
        RBone rb = new RBone(b);
        for (BBModel.Cube c : b.cubes) {
            if (c.visible) rb.cubes.add(buildCube(c));
        }
        for (BBModel.Bone ch : b.children) {
            if (ch.visible) rb.children.add(build(ch));
        }
        return rb;
    }

    /** Offsets (in 1/16 units) applied to zero-thickness "decal" cubes to avoid z-fighting. */
    private static final float DECAL_BASE = 0.08f;
    private static final float DECAL_STEP = 0.05f;
    private final Map<BBModel.Cube, Float> decalOffsets = new HashMap<>();
    private final Map<BBModel.Cube, BBModel.BBFace> decalFaces = new HashMap<>();

    /** Finds flat cubes, decides which side of them is visible and gives them stacked depth offsets. */
    private void prepareDecals(BBModel model) {
        List<BBModel.Cube> all = new ArrayList<>();
        for (BBModel.Bone b : model.roots) collectCubes(b, all);
        // group: axis+direction -> list of (plane coordinate, cube)
        Map<BBModel.BBFace, List<BBModel.Cube>> groups = new HashMap<>();
        for (BBModel.Cube c : all) {
            if (!c.visible || c.inflate != 0) continue;
            int flat = -1;
            for (int i = 0; i < 3; i++) if (Math.abs(c.from[i] - c.to[i]) < 1e-4f) { if (flat >= 0) { flat = -2; break; } flat = i; }
            if (flat < 0) continue;
            float pos = c.from[flat];
            BBModel.BBFace face = switch (flat) {
                case 0 -> pos >= 0 ? BBModel.BBFace.EAST : BBModel.BBFace.WEST;
                case 1 -> pos >= 16 ? BBModel.BBFace.UP : BBModel.BBFace.DOWN;
                default -> pos <= 0 ? BBModel.BBFace.NORTH : BBModel.BBFace.SOUTH;
            };
            decalFaces.put(c, face);
            boolean rotated = c.rotation[0] != 0 || c.rotation[1] != 0 || c.rotation[2] != 0;
            if (rotated) {
                decalOffsets.put(c, DECAL_BASE);
            } else {
                groups.computeIfAbsent(face, k -> new ArrayList<>()).add(c);
            }
        }
        for (Map.Entry<BBModel.BBFace, List<BBModel.Cube>> e : groups.entrySet()) {
            BBModel.BBFace face = e.getKey();
            int axis = switch (face) { case EAST, WEST -> 0; case UP, DOWN -> 1; default -> 2; };
            boolean positive = face == BBModel.BBFace.EAST || face == BBModel.BBFace.UP || face == BBModel.BBFace.SOUTH;
            // sort back -> front along the outward direction
            List<BBModel.Cube> list = e.getValue();
            list.sort((a, b) -> Float.compare(positive ? a.from[axis] : -a.from[axis], positive ? b.from[axis] : -b.from[axis]));
            int rank = 0;
            float last = Float.NaN;
            for (BBModel.Cube c : list) {
                if (!Float.isNaN(last) && Math.abs(c.from[axis] - last) > 1e-4f) rank++;
                last = c.from[axis];
                decalOffsets.put(c, DECAL_BASE + DECAL_STEP * rank);
            }
        }
    }

    private static void collectCubes(BBModel.Bone b, List<BBModel.Cube> out) {
        out.addAll(b.cubes);
        for (BBModel.Bone ch : b.children) collectCubes(ch, out);
    }

    private RCube buildCube(BBModel.Cube c) {
        RCube rc = new RCube(c);
        float x1 = Math.min(c.from[0], c.to[0]) - c.inflate, x2 = Math.max(c.from[0], c.to[0]) + c.inflate;
        float y1 = Math.min(c.from[1], c.to[1]) - c.inflate, y2 = Math.max(c.from[1], c.to[1]) + c.inflate;
        float z1 = Math.min(c.from[2], c.to[2]) - c.inflate, z2 = Math.max(c.from[2], c.to[2]) + c.inflate;

        BBModel.BBFace onlyFace = decalFaces.get(c);
        float off = decalOffsets.getOrDefault(c, 0f);
        if (onlyFace != null && off != 0f) {
            switch (onlyFace) {
                case NORTH -> { z1 -= off; z2 -= off; }
                case SOUTH -> { z1 += off; z2 += off; }
                case EAST  -> { x1 += off; x2 += off; }
                case WEST  -> { x1 -= off; x2 -= off; }
                case UP    -> { y1 += off; y2 += off; }
                default    -> { y1 -= off; y2 -= off; }
            }
        }

        for (BBModel.BBFace f : BBModel.BBFace.values()) {
            BBModel.Face face = c.faces[f.ordinal()];
            if (face == null) continue;
            if (onlyFace != null && f != onlyFace) continue; // flat cube: only the outward side
            // skip zero-area faces
            switch (f) {
                case NORTH, SOUTH -> { if (x2 - x1 < 1e-5f || y2 - y1 < 1e-5f) continue; }
                case EAST, WEST   -> { if (z2 - z1 < 1e-5f || y2 - y1 < 1e-5f) continue; }
                default           -> { if (x2 - x1 < 1e-5f || z2 - z1 < 1e-5f) continue; }
            }
            // Corners in Blockbench space, ordered TL, TR, BR, BL as seen from outside the face.
            float[][] corners;
            float nx = 0, ny = 0, nz = 0;
            switch (f) {
                case NORTH -> { corners = new float[][]{{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}}; nz = -1; }
                case SOUTH -> { corners = new float[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}}; nz = 1; }
                case EAST  -> { corners = new float[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}}; nx = 1; }
                case WEST  -> { corners = new float[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}}; nx = -1; }
                case UP    -> { corners = new float[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}}; ny = 1; }
                default    -> { corners = new float[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}}; ny = -1; }
            }
            float[][] uvs = {{face.u0, face.v0}, {face.u1, face.v0}, {face.u1, face.v1}, {face.u0, face.v1}};
            int shift = ((face.rotation / 90) % 4 + 4) % 4;

            RQuad q = new RQuad(face.texture, new Vector3f(-nx, -ny, nz));
            for (int i = 0; i < 4; i++) {
                Vector3f p = conv(corners[i][0], corners[i][1], corners[i][2]);
                q.pos[i * 3] = p.x;
                q.pos[i * 3 + 1] = p.y;
                q.pos[i * 3 + 2] = p.z;
                float[] uv = uvs[(i + shift) % 4];
                q.uv[i * 2] = uv[0];
                q.uv[i * 2 + 1] = uv[1];
            }
            rc.quads.add(q);
            textureUsed[face.texture] = true;
        }
        return rc;
    }

    // ------------------------------------------------------------------ textures

    private void ensureTextures() {
        if (texturesUploaded) return;
        texturesUploaded = true;
        for (int i = 0; i < model.textures.size(); i++) {
            BBModel.Texture t = model.textures.get(i);
            if (t.png == null) {
                RubySkinClient.LOGGER.warn("[RubySkin] texture '{}' has no embedded image", t.name);
                continue;
            }
            try {
                NativeImage img = NativeImage.read(t.png);
                final String n = "rubyskin_" + t.name;
                DynamicTexture dyn = new DynamicTexture(() -> n, img);
                Minecraft.getInstance().getTextureManager().register(textureIds[i], dyn);
            } catch (Exception e) {
                RubySkinClient.LOGGER.error("[RubySkin] cannot upload texture '{}'", t.name, e);
            }
        }
    }

    public void close() {
        if (!texturesUploaded) return;
        for (Identifier id : textureIds) {
            try {
                Minecraft.getInstance().getTextureManager().release(id);
            } catch (Exception ignored) {
            }
        }
        texturesUploaded = false;
    }

    // ------------------------------------------------------------------ rendering

    /**
     * @param poseStack pose already transformed by the vanilla living entity pipeline (model space)
     */
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, Anim anim, int light, int overlay) {
        ensureTextures();
        for (int i = 0; i < renderTypes.length; i++) {
            if (!textureUsed[i]) continue;
            final int tex = i;
            collector.submitCustomGeometry(poseStack, renderTypes[i],
                    (pose, vc) -> render(pose, vc, tex, anim, light, overlay));
        }
    }

    private void render(PoseStack.Pose pose, VertexConsumer vc, int tex, Anim anim, int light, int overlay) {
        PoseStack ps = new PoseStack();
        ps.last().set(pose);
        if (anim.scale() != 1f) {
            ps.translate(0f, 1.5f, 0f);
            ps.scale(anim.scale(), anim.scale(), anim.scale());
            ps.translate(0f, -1.5f, 0f);
        }
        for (RBone b : roots) {
            renderBone(b, ps, vc, tex, anim, light, overlay);
        }
    }

    private void renderBone(RBone bone, PoseStack ps, VertexConsumer vc, int tex, Anim anim, int light, int overlay) {
        ps.pushPose();
        applyBoneTransform(bone, ps, anim);
        for (RCube c : bone.cubes) {
            renderCube(c, ps, vc, tex, light, overlay);
        }
        for (RBone ch : bone.children) {
            renderBone(ch, ps, vc, tex, anim, light, overlay);
        }
        ps.popPose();
    }

    private void applyBoneTransform(RBone bone, PoseStack ps, Anim a) {
        float[] r = boneRotation(bone, a);
        boolean animated = r != null && (r[0] != 0 || r[1] != 0 || r[2] != 0);
        if (!animated && !bone.hasRotation) return;

        ps.translate(bone.origin.x, bone.origin.y, bone.origin.z);
        if (bone.hasRotation) ps.mulPose(bone.rotation);
        if (animated) ps.mulPose(new Quaternionf().rotationZYX(r[2], r[1], r[0]));
        ps.translate(-bone.origin.x, -bone.origin.y, -bone.origin.z);
    }

    /** Vanilla-like animation rotation (x, y, z in radians, model space) for a bone, or null if none. */
    private static float[] boneRotation(RBone bone, Anim a) {
        if (!a.animate() || bone.part == Part.NONE) return null;
        float rx = 0, ry = 0, rz = 0;
        float swing = a.limbSwing();
        float amount = a.limbAmount();
        float age = a.age();
        float bodyYaw = a.attackBodyYaw();
        switch (bone.part) {
            case HEAD -> {
                ry = a.headYaw() * DEG;
                rx = a.headPitch() * DEG;
            }
            case BODY -> ry = bodyYaw;
            case RIGHT_ARM, LEFT_ARM -> {
                boolean right = bone.part == Part.RIGHT_ARM;
                float side = right ? 1f : -1f;
                rx = Mth.cos(swing * 0.6662f + (right ? Mth.PI : 0f)) * 2.0f * amount * 0.5f;
                if (a.passenger()) rx -= 0.62831855f;
                // holding an item: vanilla ArmPose.ITEM
                if (right ? a.rightHoldsItem() : a.leftHoldsItem()) rx = rx * 0.5f - 0.31415927f;
                // idle bobbing
                rz = side * (Mth.cos(age * 0.09f) * 0.05f + 0.05f);
                rx += side * Mth.sin(age * 0.067f) * 0.05f;
                // attack / mining / placing swing
                if (a.attackTime() > 0f) {
                    if (!right) rx += bodyYaw;
                    boolean isAttackArm = right == a.attackRight();
                    if (isAttackArm) {
                        float f = 1f - a.attackTime();
                        f *= f;
                        f *= f;
                        f = 1f - f;
                        float g = Mth.sin(f * Mth.PI);
                        float h = Mth.sin(a.attackTime() * Mth.PI) * -(a.headPitch() * DEG - 0.7f) * 0.75f;
                        rx -= g * 1.2f + h;
                        ry += bodyYaw * 2f;
                        rz += Mth.sin(a.attackTime() * Mth.PI) * -0.4f;
                    }
                }
            }
            case RIGHT_LEG -> {
                rx = Mth.cos(swing * 0.6662f) * 1.4f * amount;
                if (a.passenger()) { rx = -1.4137167f; ry = 0.31415927f; rz = 0.07853982f; }
            }
            case LEFT_LEG -> {
                rx = Mth.cos(swing * 0.6662f + Mth.PI) * 1.4f * amount;
                if (a.passenger()) { rx = -1.4137167f; ry = -0.31415927f; rz = -0.07853982f; }
            }
            default -> { return null; }
        }
        return new float[]{rx, ry, rz};
    }

    // ------------------------------------------------------------------ first person arm

    /**
     * Renders the arm subtree in the pose vanilla uses for the first-person hand: no animation, the shoulder
     * pivot placed where the vanilla arm pivot is (x = -/+5, y = 2, model units).
     */
    public void submitFirstPersonArm(PoseStack poseStack, SubmitNodeCollector collector, int light, boolean right) {
        ensureTextures();
        Part want = right ? Part.RIGHT_ARM : Part.LEFT_ARM;
        List<RBone> path = new ArrayList<>();
        for (RBone root : roots) {
            if (findPath(root, want, path)) break;
        }
        if (path.isEmpty()) return;
        final RBone arm = path.get(path.size() - 1);
        final float dx = (right ? -5f / 16f : 5f / 16f) - arm.origin.x;
        final float dy = 2f / 16f - arm.origin.y;
        final float dz = -arm.origin.z;
        for (int i = 0; i < renderTypes.length; i++) {
            if (!textureUsed[i]) continue;
            final int tex = i;
            collector.submitCustomGeometry(poseStack, renderTypes[i], (pose, vc) -> {
                PoseStack ps = new PoseStack();
                ps.last().set(pose);
                ps.translate(dx, dy, dz);
                renderBoneStatic(arm, ps, vc, tex, light, OverlayTexture.NO_OVERLAY);
            });
        }
    }

    private void renderBoneStatic(RBone bone, PoseStack ps, VertexConsumer vc, int tex, int light, int overlay) {
        ps.pushPose();
        if (bone.hasRotation && bone.part == Part.NONE) {
            ps.translate(bone.origin.x, bone.origin.y, bone.origin.z);
            ps.mulPose(bone.rotation);
            ps.translate(-bone.origin.x, -bone.origin.y, -bone.origin.z);
        }
        for (RCube c : bone.cubes) renderCube(c, ps, vc, tex, light, overlay);
        for (RBone ch : bone.children) renderBoneStatic(ch, ps, vc, tex, light, overlay);
        ps.popPose();
    }

    // ------------------------------------------------------------------ held items

    /**
     * Equivalent of HumanoidModel.translateToHand: moves the pose stack (in model space) into the hand of the
     * given arm so that held items follow the custom arm. Leaves the stack at the arm pivot with the same
     * convention as vanilla (local +y pointing down the arm).
     */
    public void translateToHand(PoseStack ps, Anim anim, boolean right) {
        if (anim.scale() != 1f) {
            ps.translate(0f, 1.5f, 0f);
            ps.scale(anim.scale(), anim.scale(), anim.scale());
            ps.translate(0f, -1.5f, 0f);
        }
        Part want = right ? Part.RIGHT_ARM : Part.LEFT_ARM;
        List<RBone> path = new ArrayList<>();
        for (RBone root : roots) {
            if (findPath(root, want, path)) break;
        }
        if (path.isEmpty()) {
            // no arm bone: fall back to the vanilla pivot
            ps.translate(right ? -5f / 16f : 5f / 16f, 2f / 16f, 0f);
            return;
        }
        for (RBone b : path) applyBoneTransform(b, ps, anim);
        RBone arm = path.get(path.size() - 1);
        ps.translate(arm.origin.x, arm.origin.y, arm.origin.z);
        // our arm is a little longer than the vanilla 10px one - push the item down to the hand
        ps.translate(0f, armHandOffset, 0f);
    }

    private float armHandOffset = 0f;

    private boolean findPath(RBone bone, Part want, List<RBone> path) {
        path.add(bone);
        if (bone.part == want) return true;
        for (RBone c : bone.children) {
            if (findPath(c, want, path)) return true;
        }
        path.remove(path.size() - 1);
        return false;
    }

    private final Vector3f tmpNormal = new Vector3f();

    private void renderCube(RCube cube, PoseStack ps, VertexConsumer vc, int tex, int light, int overlay) {
        boolean pushed = false;
        if (cube.hasRotation) {
            ps.pushPose();
            pushed = true;
            ps.translate(cube.origin.x, cube.origin.y, cube.origin.z);
            ps.mulPose(cube.rotation);
            ps.translate(-cube.origin.x, -cube.origin.y, -cube.origin.z);
        }
        PoseStack.Pose pose = ps.last();
        for (RQuad q : cube.quads) {
            if (q.texture != tex) continue;
            pose.transformNormal(q.normal, tmpNormal);
            float nx = tmpNormal.x, ny = tmpNormal.y, nz = tmpNormal.z;
            for (int i = 0; i < 4; i++) {
                vc.addVertex(pose, q.pos[i * 3], q.pos[i * 3 + 1], q.pos[i * 3 + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(q.uv[i * 2], q.uv[i * 2 + 1])
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(nx, ny, nz);
            }
        }
        if (pushed) ps.popPose();
    }
}
