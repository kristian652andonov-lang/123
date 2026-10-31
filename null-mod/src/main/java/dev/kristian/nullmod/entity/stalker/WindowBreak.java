package dev.kristian.nullmod.entity.stalker;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A window near the player shatters: the glass breaking sound and shards for everyone near, then
 * (from Stage 2 on) heavy footsteps going away outside, and late in the stages, for a moment, the
 * figure standing outside the empty frame. The pane is put back exactly as it was by
 * {@link TemporaryChanges} (soon after nobody is looking, within a minute at most, at once when the
 * player leaves, before its chunk unloads and when the server stops).
 */
final class WindowBreak extends Encounter {
    @Nullable
    private BlockPos pane;
    @Nullable
    private StalkerSpots.Window outside;
    private int delay;
    private boolean broken;
    private final List<BlockPos> steps = new ArrayList<>();
    private int glimpseTicks;

    WindowBreak(ServerPlayer owner, float boldness, boolean forced, RandomSource random) {
        super(StalkerKind.WINDOW_BREAKER, owner, boldness, forced, random);
    }

    @Override
    boolean begin(ServerPlayer player) {
        if (!StalkerSettings.windowBreaking()) {
            return false;
        }
        pane = StalkerSpots.breakablePane(level, player, random, forced);
        if (pane == null) {
            return false;
        }
        // Worked out while the glass is still there: where something outside it could stand.
        outside = StalkerSpots.windowAt(level, player, player.getEyePosition(), pane);
        delay = forced ? 5 : 10 + random.nextInt(30);
        return true;
    }

    @Override
    boolean tick(@Nullable ServerPlayer player) {
        if (player == null || pane == null) {
            return false;
        }
        age++;
        if (!broken) {
            if (age < delay) {
                return true;
            }
            int min = 20 * 6 + random.nextInt(20 * 5);
            if (!TemporaryChanges.INSTANCE.shatter(level, pane, player, min, 20 * 45)) {
                return false;
            }
            broken = true;
            if (outside != null && (showcase || (boldness >= 0.5F && random.nextFloat() < 0.4F))) {
                glimpse(player);
            }
            if (figure == null && boldness >= 0.25F && outside != null) {
                for (int k = 2; k <= 6; k += 2) {
                    steps.add(outside.feet().relative(outside.outward(), k));
                }
            }
            return true;
        }
        if (figure != null) {
            if (figureGone()) {
                return false;
            }
            drive();
            figure.lookAtEyes(player);
            glimpseTicks++;
            boolean stared = StalkerSight.starer(figure) != null;
            return !(stared && glimpseTicks > 6) && glimpseTicks < 20 * 4;
        }
        if (!steps.isEmpty()) {
            if (age % 12 == 0) {
                soundAt(StalkerSounds.FOOTSTEPS.get(), steps.remove(0), 0.8F, pitch(0.06F), 32.0);
            }
            return true;
        }
        return age < delay + 40;
    }

    /** For a moment it stands outside the empty frame, looking in. */
    private void glimpse(ServerPlayer player) {
        Vec3 feet = Vec3.atBottomCenterOf(outside.feet());
        StalkerEntity created = spawnFigure(feet, outside.yaw(), player);
        if (created != null) {
            created.setAct(StalkerEntity.ACT_WINDOW);
            created.lookAtEyes(player);
        }
    }
}
