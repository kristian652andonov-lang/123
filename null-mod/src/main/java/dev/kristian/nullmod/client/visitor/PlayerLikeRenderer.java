package dev.kristian.nullmod.client.visitor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kristian.nullmod.client.ClientLimits;
import dev.kristian.nullmod.entity.PlayerLikeEntity;
import dev.kristian.nullmod.entity.SkinSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;

/**
 * Draws Visitors and simulated player bodies exactly like other players: the vanilla player
 * model (wide or slim arms, chosen per skin), the player scale, a player's shadow and a
 * player's name tag (shown within 64 blocks, through walls unless sneaking, with the
 * scoreboard's below-name line). On top of that only small wrong details: some name tags
 * are partly unreadable, some bodies drop out for single frames (scaled by the flashing
 * setting), black skins have faintly glowing eyes, and one that stares at you turns its
 * head and body to your camera every frame.
 */
public class PlayerLikeRenderer<T extends PlayerLikeEntity> extends LivingEntityRenderer<T, PlayerModel<T>> {
    private static final float PLAYER_SCALE = 0.9375F;

    private final PlayerModel<T> wideModel;
    private final PlayerModel<T> slimModel;

    public PlayerLikeRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.addLayer(new GlowingEyesLayer<>(this));
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = SkinSet.slim(entity.skinIndex()) ? slimModel : wideModel;
        if (entity.hasFlag(PlayerLikeEntity.FLAG_FLICKER) && flickeredOut(entity)) {
            return;
        }
        if (entity.hasFlag(PlayerLikeEntity.FLAG_STARE)) {
            faceLocalCamera(entity, partialTick);
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return SkinSet.texture(entity.skinIndex());
    }

    @Override
    protected void scale(T entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE);
    }

    @Override
    protected void renderNameTag(T entity, Component displayName, PoseStack poseStack, MultiBufferSource buffer, int packedLight, float partialTick) {
        if (entity.hasFlag(PlayerLikeEntity.FLAG_HIDE_NAME)) {
            return;
        }
        Component name = displayName;
        if (entity.hasFlag(PlayerLikeEntity.FLAG_CORRUPT_NAME) && !entity.hasCustomName() && !entity.username().isEmpty()) {
            name = glitchName(entity.username(), entity.getId() * 0x9E3779B97F4A7C15L, entity.level().getGameTime() * 50L);
        }
        double distanceSqr = this.entityRenderDispatcher.distanceToSqr(entity);
        poseStack.pushPose();
        if (distanceSqr < 100.0) {
            // Like a player: a below-name objective shows a score line (nobody here has a score).
            Scoreboard scoreboard = entity.level().getScoreboard();
            Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BELOW_NAME);
            if (objective != null) {
                Component score = ReadOnlyScoreInfo.safeFormatValue(null, objective.numberFormatOrDefault(StyledFormat.NO_STYLE));
                super.renderNameTag(entity, Component.empty().append(score).append(CommonComponents.SPACE).append(objective.getDisplayName()),
                        poseStack, buffer, packedLight, partialTick);
                poseStack.translate(0.0F, 9.0F * 1.15F * 0.025F, 0.0F);
            }
        }
        super.renderNameTag(entity, name, poseStack, buffer, packedLight, partialTick);
        poseStack.popPose();
    }

    /** A body flagged to flicker drops out for one short slot every couple of seconds. */
    private static boolean flickeredOut(PlayerLikeEntity entity) {
        float flashing = ClientLimits.flashing();
        if (flashing <= 0.05F) {
            return false;
        }
        long slot = entity.level().getGameTime() / 3L;
        long h = mix(slot * 0x9E3779B97F4A7C15L + entity.getId() * 0xC2B2AE3D27D4EB4FL);
        return (h & 1023L) < (long) (60.0F * flashing);
    }

    /**
     * For the player it is staring at, the head and body follow the camera exactly every
     * frame instead of the slightly delayed synced rotation.
     */
    private static void faceLocalCamera(PlayerLikeEntity entity, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || entity.watchedId() != minecraft.player.getId()) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.getEntity() != minecraft.player) {
            return;
        }
        Vec3 eye = camera.getPosition();
        Vec3 pos = entity.getPosition(partialTick);
        double dx = eye.x - pos.x;
        double dz = eye.z - pos.z;
        double dy = eye.y - (pos.y + entity.getEyeHeight());
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-3) {
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = Mth.clamp((float) (-(Mth.atan2(dy, horizontal) * (180.0 / Math.PI))), -70.0F, 70.0F);
        entity.yHeadRot = yaw;
        entity.yHeadRotO = yaw;
        entity.yBodyRot = yaw;
        entity.yBodyRotO = yaw;
        entity.setXRot(pitch);
        entity.xRotO = pitch;
    }

    /**
     * A name with some letters unreadable. With flashing allowed the hidden letters churn
     * (obfuscated text); without it a few letters are simply wrong and change slowly.
     *
     * @param seed   stable per name owner
     * @param timeMs a clock in milliseconds
     */
    public static MutableComponent glitchName(String name, long seed, long timeMs) {
        boolean churn = ClientLimits.flashing() > 0.3F;
        long slot = timeMs / (churn ? 400L : 1500L);
        MutableComponent out = Component.empty();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            long h = mix(seed ^ (i * 0x632BE59BD9B4E019L) ^ ((slot + i / 3) * 0x9E3779B97F4A7C15L));
            char c = name.charAt(i);
            boolean broken = (h & 7L) < 2L;
            if (!broken) {
                plain.append(c);
                continue;
            }
            if (plain.length() > 0) {
                out.append(Component.literal(plain.toString()));
                plain.setLength(0);
            }
            if (churn) {
                out.append(Component.literal(String.valueOf(c)).withStyle(style -> style.withObfuscated(true)));
            } else {
                out.append(Component.literal(String.valueOf(substitute(c, h))));
            }
        }
        if (plain.length() > 0) {
            out.append(Component.literal(plain.toString()));
        }
        return out;
    }

    private static char substitute(char c, long h) {
        return switch (Character.toLowerCase(c)) {
            case 'a' -> '4';
            case 'e' -> '3';
            case 'i' -> '1';
            case 'o' -> '0';
            case 's' -> '5';
            case 't' -> '7';
            default -> (h & 8L) == 0L ? '_' : '?';
        };
    }

    private static long mix(long h) {
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** Faint, slowly breathing eyes for the black skins that have them; visible in total darkness. */
    private static final class GlowingEyesLayer<T extends PlayerLikeEntity> extends RenderLayer<T, PlayerModel<T>> {
        GlowingEyesLayer(RenderLayerParent<T, PlayerModel<T>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            ResourceLocation eyes = SkinSet.eyesTexture(entity.skinIndex());
            if (eyes == null || entity.isInvisible()) {
                return;
            }
            float strength = 0.42F + 0.1F * Mth.sin(ageInTicks * 0.045F + entity.getId());
            int v = Mth.clamp((int) (strength * 255.0F), 0, 255);
            VertexConsumer consumer = buffer.getBuffer(RenderType.eyes(eyes));
            // Equal channels, so the colour reads the same whatever the argument order.
            this.getParentModel().renderToBuffer(poseStack, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    FastColor.ARGB32.color(v, v, v, v));
        }
    }
}
