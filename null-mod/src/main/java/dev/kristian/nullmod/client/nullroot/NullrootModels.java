package dev.kristian.nullmod.client.nullroot;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.entity.nullroot.NullrootVariant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The NULLROOT models, built in code: one layer per variant plus a unit tendril segment for the
 * tendrils drawn at run time. Every model is authored growing "up" (+Y) out of the block face it
 * is rooted in, with its origin at the centre of that face, in model pixels (1/16 block); the
 * renderer turns it to the real face. Tendrils are chains of thin boxes, each segment the child of
 * the one before, so bending every segment a little curls the whole chain.
 * <p>
 * While a model is built, the paths of its tendril chains, its pulsating bulbs and its head are
 * recorded, so {@link NullrootModel} can find them again after baking.
 */
public final class NullrootModels {
    /** Every NULLROOT texture is this many texels square. */
    public static final int TEXTURE_SIZE = 128;
    public static final ModelLayerLocation SEGMENT = new ModelLayerLocation(NullMod.id("nullroot"), "segment");
    /** Size of the unit segment box in pixels: 4 x 16 x 4, i.e. 0.25 x 1 x 0.25 blocks. */
    public static final float SEGMENT_WIDTH = 0.25F;

    /** A built layer and where its animated parts are. Paths are child names joined by '/'. */
    public record Built(LayerDefinition definition, List<String> chains, List<String> bulbs, @Nullable String head) {
    }

    private static final ModelLayerLocation[] LAYERS = new ModelLayerLocation[NullrootVariant.values().length];
    private static final Map<NullrootVariant, Built> BUILT = new EnumMap<>(NullrootVariant.class);

    static {
        for (NullrootVariant variant : NullrootVariant.values()) {
            LAYERS[variant.ordinal()] = new ModelLayerLocation(NullMod.id("nullroot"), variant.id());
            BUILT.put(variant, build(variant));
        }
    }

    private NullrootModels() {
    }

    public static ModelLayerLocation layer(NullrootVariant variant) {
        return LAYERS[variant.ordinal()];
    }

    public static Built built(NullrootVariant variant) {
        return BUILT.get(variant);
    }

    static void register(EntityRenderersEvent.RegisterLayerDefinitions event) {
        for (NullrootVariant variant : NullrootVariant.values()) {
            Built built = BUILT.get(variant);
            event.registerLayerDefinition(layer(variant), built::definition);
        }
        event.registerLayerDefinition(SEGMENT, NullrootModels::segment);
    }

    /** One box, 0.25 x 1 x 0.25 blocks, standing on its origin: scaled and turned into every run-time tendril piece. */
    private static LayerDefinition segment() {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("segment", CubeListBuilder.create().texOffs(0, 64).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 16.0F, 4.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    private static Built build(NullrootVariant variant) {
        Builder b = new Builder();
        switch (variant) {
            case SMALL -> small(b);
            case LARGE -> large(b);
            case TENDRIL -> tendril(b);
            case CEILING -> ceiling(b);
            case WALL -> wall(b);
            case UNDERGROUND -> underground(b);
            case CENTRAL -> central(b);
        }
        return new Built(LayerDefinition.create(b.mesh, TEXTURE_SIZE, TEXTURE_SIZE), List.copyOf(b.chains), List.copyOf(b.bulbs), b.head);
    }

    // ------------------------------------------------------------------ the variants

    /** A small black plant: a bulb, a short stem and a head that watches. */
    private static void small(Builder b) {
        Node body = b.body();
        b.bulb(body, "bulb0", -3.0F, 0.0F, -3.0F, 6.0F, 3.5F, 6.0F);
        b.bulb(body, "bulb1", -1.25F, 3.0F, -1.25F, 2.5F, 3.6F, 2.5F);
        Node head = b.part(body, "head", PartPose.offset(0.0F, 6.2F, 0.0F),
                b.box(-2.5F, 0.0F, -2.5F, 5.0F, 4.0F, 5.0F), b.box(-1.5F, 1.2F, 2.2F, 3.0F, 1.4F, 1.0F));
        b.head = head.path;
        for (int i = 0; i < 4; i++) {
            float angle = i * (float) Math.PI * 0.5F + 0.4F;
            b.chain(body, "chain" + i, (float) Math.sin(angle) * 2.4F, 2.4F, (float) Math.cos(angle) * 2.4F,
                    0.95F, angle, 0.0F, 3, 2.6F, 1.4F, 0.6F, 0.25F);
        }
    }

    /** A tall plant: lumpy roots, a swaying stalk and a heavy crown with drooping tendrils. */
    private static void large(Builder b) {
        Node body = b.body();
        b.bulb(body, "bulb0", -7.0F, 0.0F, -7.0F, 14.0F, 4.0F, 14.0F);
        b.bulb(body, "bulb1", -4.5F, 3.0F, -4.5F, 9.0F, 4.5F, 9.0F);
        Node stalk = b.part(body, "stalk", PartPose.offset(0.0F, 7.0F, 0.0F), b.box(-2.4F, 0.0F, -2.4F, 4.8F, 6.6F, 4.8F));
        b.chains.add(stalk.path);
        Node segment = stalk;
        for (int k = 1; k < 4; k++) {
            float t = 4.6F - k * 0.35F;
            segment = b.part(segment, "s", PartPose.offset(0.0F, 6.5F, 0.0F), b.box(-t / 2.0F, 0.0F, -t / 2.0F, t, 6.6F, t));
        }
        Node crown = b.part(segment, "crown", PartPose.offset(0.0F, 6.2F, 0.0F),
                b.box(-6.0F, 0.0F, -6.0F, 12.0F, 7.0F, 12.0F), b.box(-4.0F, 6.5F, -4.0F, 8.0F, 3.0F, 8.0F));
        b.bulbs.add(crown.path);
        for (int i = 0; i < 5; i++) {
            float angle = i * (float) Math.PI * 0.4F;
            b.chain(crown, "chain" + i, (float) Math.sin(angle) * 4.8F, 3.0F, (float) Math.cos(angle) * 4.8F,
                    2.35F, angle, 0.0F, 4, 4.2F, 1.8F, 0.7F, 0.18F);
        }
    }

    /** A knot of long hunting tendrils. */
    private static void tendril(Builder b) {
        Node body = b.body();
        b.bulb(body, "bulb0", -4.0F, 0.0F, -4.0F, 8.0F, 4.0F, 8.0F);
        b.bulb(body, "bulb1", -3.0F, 3.5F, -3.0F, 6.0F, 3.0F, 6.0F);
        for (int i = 0; i < 6; i++) {
            float angle = i * (float) Math.PI / 3.0F + 0.3F;
            float lean = 0.25F + (i % 3) * 0.18F;
            b.chain(body, "chain" + i, (float) Math.sin(angle) * 1.8F, 5.5F, (float) Math.cos(angle) * 1.8F,
                    lean, angle, 0.0F, 6, 3.4F, 1.8F, 0.6F, 0.09F);
        }
    }

    /** A mass on a cave ceiling with long drips hanging down (the model's +Y is down in the world). */
    private static void ceiling(Builder b) {
        Node body = b.body();
        b.bulb(body, "bulb0", -5.0F, 0.0F, -5.0F, 10.0F, 4.5F, 10.0F);
        b.bulb(body, "bulb1", -3.5F, 4.0F, -3.5F, 7.0F, 4.0F, 7.0F);
        for (int i = 0; i < 5; i++) {
            float angle = i * (float) Math.PI * 0.4F + 0.2F;
            float r = i == 0 ? 0.0F : 3.2F;
            Node end = b.chain(body, "chain" + i, (float) Math.sin(angle) * r, 6.0F, (float) Math.cos(angle) * r,
                    i == 0 ? 0.0F : 0.22F, angle, 0.0F, 4, 3.6F, 1.6F, 0.8F, 0.0F);
            // A droplet hangs at the end of every drip.
            b.part(end, "drop", PartPose.offset(0.0F, 3.4F, 0.0F), b.box(-1.25F, 0.0F, -1.25F, 2.5F, 3.0F, 2.5F));
        }
    }

    /** A flat growth on a wall with tendrils sticking out of it (the model's +Y points away from the wall). */
    private static void wall(Builder b) {
        Node body = b.body();
        b.bulb(body, "bulb0", -6.5F, 0.0F, -6.5F, 13.0F, 2.5F, 13.0F);
        b.bulb(body, "bulb1", -4.0F, 2.0F, -4.0F, 8.0F, 3.5F, 8.0F);
        b.bulb(body, "bulb2", 1.0F, 1.5F, -5.5F, 4.0F, 3.0F, 4.0F);
        for (int i = 0; i < 4; i++) {
            float angle = i * (float) Math.PI * 0.5F + 0.7F;
            b.chain(body, "chain" + i, (float) Math.sin(angle) * 2.8F, 4.0F, (float) Math.cos(angle) * 2.8F,
                    0.55F, angle, 0.0F, 4, 3.0F, 1.5F, 0.6F, 0.12F);
        }
    }

    /** A lumpy cluster of bulbs and stubby tendrils. */
    private static void underground(Builder b) {
        Node body = b.body();
        b.bulb(body, "bulb0", -6.0F, 0.0F, -6.0F, 12.0F, 6.0F, 12.0F);
        b.bulb(body, "bulb1", -7.0F, 2.0F, 0.0F, 6.0F, 7.0F, 6.0F);
        b.bulb(body, "bulb2", 1.0F, 1.0F, -6.5F, 6.0F, 9.0F, 6.0F);
        b.bulb(body, "bulb3", -3.0F, 5.0F, -3.0F, 7.0F, 8.0F, 7.0F);
        b.bulb(body, "bulb4", -1.0F, 12.0F, -1.0F, 3.5F, 4.5F, 3.5F);
        for (int i = 0; i < 4; i++) {
            float angle = i * (float) Math.PI * 0.5F + 1.1F;
            b.chain(body, "chain" + i, (float) Math.sin(angle) * 4.0F, 6.0F, (float) Math.cos(angle) * 4.0F,
                    0.7F, angle, 0.0F, 5, 3.0F, 2.0F, 0.7F, 0.15F);
        }
    }

    /** The centre of a colony: a heaped mass with a swollen top and many tendrils, most of them rooting back into the ground. */
    private static void central(Builder b) {
        Node body = b.body();
        b.bulb(body, "bulb0", -16.0F, 0.0F, -16.0F, 32.0F, 13.0F, 32.0F);
        b.bulb(body, "bulb1", -13.0F, 12.0F, -13.0F, 26.0F, 12.0F, 26.0F);
        b.bulb(body, "bulb2", -9.0F, 23.0F, -9.0F, 18.0F, 10.0F, 18.0F);
        b.bulb(body, "bulb3", -19.0F, 0.0F, -8.0F, 8.0F, 9.0F, 14.0F);
        b.bulb(body, "bulb4", 12.0F, 0.0F, -6.0F, 8.0F, 11.0F, 12.0F);
        b.bulb(body, "bulb5", -6.0F, 0.0F, 12.0F, 14.0F, 8.0F, 8.0F);
        b.bulb(body, "bulb6", -6.0F, 32.0F, -6.0F, 12.0F, 8.0F, 12.0F);
        for (int i = 0; i < 10; i++) {
            float angle = i * (float) Math.PI * 0.2F + 0.15F;
            boolean root = i % 2 == 0;
            float radius = root ? 14.0F : 10.0F;
            float height = root ? 6.0F : 20.0F + (i % 3) * 3.0F;
            // Roots arch out and back down into the ground; the others reach up and out.
            b.chain(body, "chain" + i, (float) Math.sin(angle) * radius, height, (float) Math.cos(angle) * radius,
                    root ? 1.45F : 0.75F, angle, 0.0F, root ? 5 : 7, root ? 5.0F : 6.0F, root ? 3.4F : 3.0F, 1.0F, root ? 0.32F : 0.12F);
        }
    }

    // ------------------------------------------------------------------ building

    /** A part being built and its path. */
    private record Node(PartDefinition part, String path) {
    }

    private static final class Builder {
        private final MeshDefinition mesh = new MeshDefinition();
        private final List<String> chains = new ArrayList<>();
        private final List<String> bulbs = new ArrayList<>();
        @Nullable
        private String head;
        private int u;
        private int v;
        private int rowHeight;

        Node body() {
            return new Node(mesh.getRoot().addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO), "body");
        }

        /** A box with its own spot on the texture (packed in rows; anything past the bottom wraps around). */
        float[] box(float x, float y, float z, float w, float h, float d) {
            return new float[]{x, y, z, w, h, d};
        }

        Node part(Node parent, String name, PartPose pose, float[]... boxes) {
            CubeListBuilder cubes = CubeListBuilder.create();
            for (float[] box : boxes) {
                int width = (int) Math.ceil(2.0F * (box[3] + box[5]));
                int height = (int) Math.ceil(box[4] + box[5]);
                if (u + width > TEXTURE_SIZE) {
                    u = 0;
                    v += rowHeight + 1;
                    rowHeight = 0;
                }
                if (v + height > TEXTURE_SIZE) {
                    v = 0;
                }
                cubes.texOffs(u, v).addBox(box[0], box[1], box[2], box[3], box[4], box[5]);
                u += width + 1;
                rowHeight = Math.max(rowHeight, height);
            }
            return new Node(parent.part().addOrReplaceChild(name, cubes, pose), parent.path() + "/" + name);
        }

        /** A pulsating lump, its own part so it can swell around its base. */
        void bulb(Node parent, String name, float x, float y, float z, float w, float h, float d) {
            // The pivot sits at the bottom centre of the lump, so it swells upward and outward.
            float px = x + w / 2.0F;
            float pz = z + d / 2.0F;
            Node node = part(parent, name, PartPose.offset(px, y, pz), box(-w / 2.0F, 0.0F, -d / 2.0F, w, h, d));
            bulbs.add(node.path());
        }

        /**
         * A tendril: {@code segments} boxes of {@code length} pixels, thinning from {@code thick0} to
         * {@code thick1}, starting at (x, y, z) and pointing along the given rotation; every segment
         * after the first is curled by {@code curl} radians. Returns the last segment.
         */
        Node chain(Node parent, String name, float x, float y, float z, float xRot, float yRot, float zRot, int segments,
                   float length, float thick0, float thick1, float curl) {
            Node node = part(parent, name, PartPose.offsetAndRotation(x, y, z, xRot, yRot, zRot),
                    box(-thick0 / 2.0F, 0.0F, -thick0 / 2.0F, thick0, length, thick0));
            chains.add(node.path());
            for (int k = 1; k < segments; k++) {
                float t = thick0 + (thick1 - thick0) * k / Math.max(1, segments - 1);
                node = part(node, "s", PartPose.offsetAndRotation(0.0F, length * 0.95F, 0.0F, curl, 0.0F, 0.0F),
                        box(-t / 2.0F, 0.0F, -t / 2.0F, t, length, t));
            }
            return node;
        }
    }
}
