package dev.kristian.nullmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.showcase.ShowcaseManager;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /null showcase [stage <1-6> | next | stop | <seconds>]}: an operator's guided tour through
 * the stages ({@link ShowcaseManager}). Permission level 2, like the rest of {@code /null}; Brigadier
 * merges it into the existing {@code /null} command.
 */
public final class ShowcaseCommand {
    private ShowcaseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("null")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("showcase")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> start(ctx.getSource(), null))
                        .then(Commands.literal("stage")
                                .then(Commands.argument("stage", IntegerArgumentType.integer(1, CorruptionStage.HARVEST.number()))
                                        .executes(ctx -> start(ctx.getSource(),
                                                CorruptionStage.byNumber(IntegerArgumentType.getInteger(ctx, "stage"))))))
                        .then(Commands.literal("next").executes(ctx -> next(ctx.getSource())))
                        .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(ShowcaseManager.MIN_STEP_SECONDS,
                                        ShowcaseManager.MAX_STEP_SECONDS))
                                .executes(ctx -> seconds(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconds"))))));
    }

    private static int start(CommandSourceStack source, @Nullable CorruptionStage stage) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        ShowcaseManager manager = ShowcaseManager.INSTANCE;
        if (manager.active()) {
            UUID viewer = manager.viewer();
            ServerPlayer watching = viewer == null ? null : server.getPlayerList().getPlayer(viewer);
            String name = watching == null ? "?" : watching.getGameProfile().getName();
            source.sendFailure(Component.translatable("null.showcase.error.running", name));
            return 0;
        }
        if (!manager.start(player, stage)) {
            source.sendFailure(Component.translatable("null.showcase.error.running", "?"));
            return 0;
        }
        int seconds = manager.stepSeconds();
        Component intro = stage == null
                ? Component.translatable("null.showcase.start.full", seconds)
                : Component.translatable("null.showcase.start.stage", stage.number(), stage.title(), seconds);
        source.sendSuccess(() -> Component.translatable("null.showcase.title").withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                .append(intro.copy().withStyle(ChatFormatting.WHITE)), true);
        source.sendSuccess(() -> Component.translatable("null.showcase.start.help").withStyle(ChatFormatting.GRAY), false);
        if (server.getPlayerList().getPlayers().size() > 1) {
            source.sendSuccess(() -> Component.translatable("null.showcase.start.others").withStyle(ChatFormatting.YELLOW), false);
        }
        ShowcaseManager.Saved saved = manager.saved();
        CorruptionStage first = stage == null ? CorruptionStage.START : stage;
        if (saved != null && saved.stage().number() > first.number() && saved.stage().atLeast(CorruptionStage.UNDER_MIDDLE)) {
            // Going back to an earlier stage undoes what only belongs to the later ones; it grows back afterwards.
            CorruptionStage world = saved.stage();
            source.sendSuccess(() -> Component.translatable("null.showcase.start.later", world.number(), world.title())
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return manager.steps().size();
    }

    private static int next(CommandSourceStack source) {
        if (!ShowcaseManager.INSTANCE.next()) {
            source.sendFailure(Component.translatable(ShowcaseManager.INSTANCE.active()
                    ? "null.showcase.error.ending" : "null.showcase.error.none"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("null.showcase.next").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        if (!ShowcaseManager.INSTANCE.stop(source.getServer())) {
            source.sendFailure(Component.translatable(ShowcaseManager.INSTANCE.active()
                    ? "null.showcase.error.ending" : "null.showcase.error.none"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("null.showcase.stopping").withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    private static int seconds(CommandSourceStack source, int seconds) {
        ShowcaseManager.INSTANCE.setStepSeconds(seconds);
        int set = ShowcaseManager.INSTANCE.stepSeconds();
        source.sendSuccess(() -> Component.translatable("null.showcase.seconds", set).withStyle(ChatFormatting.GRAY), true);
        return set;
    }
}
