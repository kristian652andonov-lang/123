package dev.kristian.nullmod.client.nullroot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.entity.nullroot.NullrootVariant;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;

/**
 * One baked NULLROOT model and its animation: slow, slightly irregular swaying of every tendril
 * chain (a wave running from base to tip), bulbs that breathe and swell on a pulse, a head that
 * turns on its own, and a jolt that makes everything shiver for a moment.
 */
final class NullrootModel {
    private final ModelPart root;
    private final ModelPart body;
    @Nullable
    private final ModelPart head;
    private final ModelPart[][] chains;
    private final PartPose[][] chainPoses;
    private final float[] chainPhase;
    private final float[] chainSpeed;
    private final ModelPart[] bulbs;
    private final PartPose[] bulbPoses;
    private final float swayAmount;
    private final float breathAmount;

    NullrootModel(NullrootVariant variant, ModelPart root, NullrootModels.Built spec) {
        this.root = root;
        this.body = root.getChild("body");
        this.head = spec.head() == null ? null : find(root, spec.head());
        List<String> chainPaths = spec.chains();
        this.chains = new ModelPart[chainPaths.size()][];
        this.chainPoses = new PartPose[chainPaths.size()][];
        this.chainPhase = new float[chainPaths.size()];
        this.chainSpeed = new float[chainPaths.size()];
        for (int i = 0; i < chainPaths.size(); i++) {
            List<ModelPart> segments = new ArrayList<>();
            ModelPart part = find(root, chainPaths.get(i));
            segments.add(part);
            while (part.hasChild("s")) {
                part = part.getChild("s");
                segments.add(part);
            }
            chains[i] = segments.toArray(new ModelPart[0]);
            chainPoses[i] = new PartPose[chains[i].length];
            for (int k = 0; k < chains[i].length; k++) {
                chainPoses[i][k] = chains[i][k].getInitialPose();
            }
            chainPhase[i] = i * 2.399963F;
            chainSpeed[i] = 0.034F + (i % 4) * 0.007F;
        }
        List<String> bulbPaths = spec.bulbs();
        this.bulbs = new ModelPart[bulbPaths.size()];
        this.bulbPoses = new PartPose[bulbPaths.size()];
        for (int i = 0; i < bulbPaths.size(); i++) {
            bulbs[i] = find(root, bulbPaths.get(i));
            bulbPoses[i] = bulbs[i].getInitialPose();
        }
        this.swayAmount = switch (variant) {
            case CENTRAL -> 0.07F;
            case LARGE -> 0.09F;
            case CEILING -> 0.1F;
            default -> 0.13F;
        };
        this.breathAmount = variant == NullrootVariant.CENTRAL ? 0.035F : 0.05F;
    }

    private static ModelPart find(ModelPart root, String path) {
        ModelPart part = root;
        for (String name : path.split("/")) {
            part = part.getChild(name);
        }
        return part;
    }

    /**
     * Poses every part for one frame.
     *
     * @param time      animation clock in ticks
     * @param shift     per-organism phase offset (jumps when it twitches)
     * @param pulse     0..1 swell right after a pulse
     * @param jolt      0..1 shiver after a twitch, a hit or a grab
     * @param headTurn  extra turn of the head (observers look harder than the rest of them)
     * @param tension   0..1 how strained it is (reaching or holding): faster, tighter movement
     */
    void pose(float time, float shift, float pulse, float jolt, float headTurn, float tension) {
        // Not quite steady: the clock itself speeds up and slows down.
        float t = time + 3.0F * Mth.sin(time * 0.0137F + shift) + shift * 40.0F;
        float speedUp = 1.0F + tension * 1.6F;
        float shiver = jolt * 0.22F;

        body.resetPose();
        body.xRot = Mth.sin(t * 0.021F) * 0.035F + Mth.sin(time * 1.9F) * shiver * 0.3F;
        body.zRot = Mth.cos(t * 0.017F + 1.3F) * 0.035F + Mth.cos(time * 2.3F) * shiver * 0.3F;

        for (int i = 0; i < bulbs.length; i++) {
            ModelPart bulb = bulbs[i];
            bulb.loadPose(bulbPoses[i]);
            float breath = 0.5F + 0.5F * Mth.sin(t * 0.07F + i * 1.7F);
            float swell = 1.0F + breathAmount * breath + pulse * (0.12F + 0.03F * (i % 3));
            bulb.xScale = swell;
            bulb.zScale = swell;
            bulb.yScale = 1.0F + (swell - 1.0F) * 0.6F;
        }

        if (head != null) {
            head.resetPose();
            head.yRot = headTurn;
            head.xRot = Mth.sin(t * 0.05F) * 0.08F - 0.12F;
            head.zRot = Mth.sin(time * 2.7F) * shiver;
        }

        for (int i = 0; i < chains.length; i++) {
            ModelPart[] chain = chains[i];
            PartPose[] poses = chainPoses[i];
            float phase = chainPhase[i] + shift;
            float speed = chainSpeed[i] * speedUp;
            int n = chain.length;
            for (int k = 0; k < n; k++) {
                ModelPart segment = chain[k];
                segment.loadPose(poses[k]);
                float along = (k + 1.0F) / n;
                float amount = swayAmount * (0.35F + 0.65F * along) * (1.0F - 0.45F * tension) + shiver * along;
                segment.xRot += Mth.sin(t * speed + phase + k * 0.65F) * amount;
                segment.zRot += Mth.cos(t * speed * 0.8F + phase * 1.3F + k * 0.5F) * amount;
                if (tension > 0.0F) {
                    // Strained tendrils curl in on themselves.
                    segment.xRot += tension * 0.12F * along;
                }
            }
        }
    }

    void render(PoseStack poseStack, VertexConsumer consumer, int light, int overlay, int color) {
        root.render(poseStack, consumer, light, overlay, color);
    }
}
