package dev.kristian.nullmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kristian.nullmod.config.NullConfig;
import dev.kristian.nullmod.core.CorruptionContext;
import dev.kristian.nullmod.core.CorruptionData;
import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.CorruptionStage;
import dev.kristian.nullmod.core.CorruptionStageManager;
import dev.kristian.nullmod.core.Ticks;
import dev.kristian.nullmod.event.NullEventDefinition;
import dev.kristian.nullmod.event.NullEventRegistry;
import dev.kristian.nullmod.event.RandomEventManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /null status|stage|reset|pause|resume|speed|events|event}. Needs permission level 2
 * (operators, or cheats enabled in singleplayer).
 */
public final class NullCommand {
    private static final long RESET_CONFIRM_TICKS = 20L * 15L;
    private static long resetRequestedAt = Long.MIN_VALUE;

    private NullCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Commands are registered for every server start: a confirmation pending in a world that
        // was closed must never confirm a reset in the next one (game times are per world).
        resetRequestedAt = Long.MIN_VALUE;
        dispatcher.register(Commands.literal("null")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("stage")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.argument("stage", IntegerArgumentType.integer(1, 5))
                                .executes(ctx -> stage(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "stage")))))
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())))
                .then(Commands.literal("pause").executes(ctx -> pause(ctx.getSource(), true)))
                .then(Commands.literal("resume").executes(ctx -> pause(ctx.getSource(), false)))
                .then(Commands.literal("speed")
                        .executes(ctx -> showSpeed(ctx.getSource()))
                        .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.0, 100.0))
                                .executes(ctx -> speed(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "multiplier")))))
                .then(Commands.literal("events").executes(ctx -> listEvents(ctx.getSource())))
                .then(Commands.literal("event")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        NullEventRegistry.all().stream().map(NullEventDefinition::id), builder))
                                .executes(ctx -> event(ctx, List.of(ctx.getSource().getPlayerOrException())))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> event(ctx, EntityArgument.getPlayers(ctx, "targets")))))));
    }

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        CorruptionData data = CorruptionManager.data(server);
        CorruptionStage stage = data.stage();
        double ticks = data.corruptionTicks();
        int stagePercent = Math.round(CorruptionStageManager.progressWithin(stage, ticks) * 100.0F);
        double untilNext = CorruptionStageManager.ticksUntilNext(stage, ticks);
        double effectiveSpeed = NullConfig.get(NullConfig.PROGRESSION_SPEED) * data.speed();

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("NULL ").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                .append(Component.translatable("null.command.status.stage", stage.number(), stage.displayName(), stagePercent)
                        .withStyle(ChatFormatting.WHITE)));
        lines.add(Component.translatable("null.command.status.time",
                Ticks.format(data.activeTicks()), Ticks.format(ticks)).withStyle(ChatFormatting.GRAY));
        if (untilNext >= 0.0) {
            boolean moving = effectiveSpeed > 0.0 && !data.paused() && NullConfig.get(NullConfig.PROGRESSION_ENABLED);
            String eta = moving ? Ticks.format(untilNext / effectiveSpeed) : "-";
            lines.add(Component.translatable("null.command.status.next", Ticks.format(untilNext), eta).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("null.command.status.final").withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("null.command.status.speed",
                String.format("%.2f", data.speed()), String.format("%.2f", NullConfig.get(NullConfig.PROGRESSION_SPEED)),
                Component.translatable(data.paused() ? "null.command.status.paused" : "null.command.status.running"))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("null.command.status.moon",
                Math.round(data.moonProgress() * 100.0F),
                Component.translatable(data.moonGone() ? "null.command.status.moon_gone" : "null.command.status.moon_present"))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("null.command.status.highest", data.highestStage().number(), NullEventRegistry.size())
                .withStyle(ChatFormatting.DARK_GRAY));
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return stage.number();
    }

    private static int stage(CommandSourceStack source, int number) {
        CorruptionStage stage = CorruptionStage.byNumber(number);
        CorruptionManager.setStage(source.getServer(), stage);
        source.sendSuccess(() -> Component.translatable("null.command.stage.set", stage.number(), stage.displayName()), true);
        return number;
    }

    private static int reset(CommandSourceStack source) {
        long now = source.getServer().overworld().getGameTime();
        boolean pending = resetRequestedAt != Long.MIN_VALUE && now >= resetRequestedAt
                && now - resetRequestedAt <= RESET_CONFIRM_TICKS;
        if (!pending) {
            resetRequestedAt = now;
            source.sendSuccess(() -> Component.translatable("null.command.reset.confirm").withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        resetRequestedAt = Long.MIN_VALUE;
        CorruptionManager.reset(source.getServer());
        source.sendSuccess(() -> Component.translatable("null.command.reset.done"), true);
        return 1;
    }

    private static int pause(CommandSourceStack source, boolean paused) {
        CorruptionManager.setPaused(source.getServer(), paused);
        source.sendSuccess(() -> Component.translatable(paused ? "null.command.paused" : "null.command.resumed"), true);
        return 1;
    }

    private static int showSpeed(CommandSourceStack source) {
        CorruptionData data = CorruptionManager.data(source.getServer());
        source.sendSuccess(() -> Component.translatable("null.command.speed.current",
                String.format("%.2f", data.speed()), String.format("%.2f", NullConfig.get(NullConfig.PROGRESSION_SPEED))), false);
        return (int) Math.round(data.speed() * 100.0);
    }

    private static int speed(CommandSourceStack source, double multiplier) {
        CorruptionManager.setSpeed(source.getServer(), multiplier);
        source.sendSuccess(() -> Component.translatable("null.command.speed.set", String.format("%.2f", multiplier)), true);
        return (int) Math.round(multiplier * 100.0);
    }

    private static int listEvents(CommandSourceStack source) {
        CorruptionStage stage = CorruptionManager.stage(source.getServer());
        MutableComponent header = Component.translatable("null.command.events.header", NullEventRegistry.size());
        source.sendSuccess(() -> header, false);
        for (CorruptionStage s : CorruptionStage.values()) {
            List<String> ids = new ArrayList<>();
            for (NullEventDefinition event : NullEventRegistry.all()) {
                if (event.minStage() == s) {
                    ids.add(event.id());
                }
            }
            if (ids.isEmpty()) {
                continue;
            }
            ChatFormatting color = s == stage ? ChatFormatting.WHITE : ChatFormatting.GRAY;
            Component line = Component.literal(s.number() + " " + s.title() + ": ").withStyle(ChatFormatting.DARK_RED)
                    .append(Component.literal(String.join(", ", ids)).withStyle(color));
            source.sendSuccess(() -> line, false);
        }
        return NullEventRegistry.size();
    }

    private static int event(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        Optional<NullEventDefinition> event = NullEventRegistry.get(id);
        if (event.isEmpty()) {
            source.sendFailure(Component.translatable("null.command.event.unknown", id));
            return 0;
        }
        CorruptionContext corruption = CorruptionManager.context(source.getServer());
        int ran = 0;
        for (ServerPlayer player : targets) {
            if (RandomEventManager.INSTANCE.force(player, event.get(), corruption)) {
                ran++;
            }
        }
        int count = ran;
        if (count == 0) {
            source.sendFailure(Component.translatable("null.command.event.failed", id));
        } else {
            source.sendSuccess(() -> Component.translatable("null.command.event.ran", id, count), true);
        }
        return count;
    }
}
