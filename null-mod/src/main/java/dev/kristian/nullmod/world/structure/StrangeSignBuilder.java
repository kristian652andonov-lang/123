package dev.kristian.nullmod.world.structure;

import dev.kristian.nullmod.core.CorruptionManager;
import dev.kristian.nullmod.core.WorldMemory;
import dev.kristian.nullmod.world.GenerationRequest;
import dev.kristian.nullmod.world.LocationFinder;
import dev.kristian.nullmod.world.PlacementJob;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.RotationSegment;

/**
 * A plain oak sign standing on the grass a few blocks away, usually behind the player, turned
 * to face them, with a few short lines nobody wrote: "turn around", "it was here first", the
 * player's own name, a name the player has seen above a Visitor, unreadable scrambled text.
 * The sign is waxed, so its text cannot be changed.
 */
final class StrangeSignBuilder {
    /** One message: lang keys for its lines; {@code @name}, {@code @visitor} and {@code @noise} are filled in. */
    private record Message(String id, String english, String... lines) {
    }

    private static final Message[] MESSAGES = {
            new Message("turn_around", "turn around", "null.world.sign.turn_around.1"),
            new Message("here_first", "it was here first", "null.world.sign.here_first.1", "null.world.sign.here_first.2"),
            new Message("not_alone", "you are not alone here", "null.world.sign.not_alone.1", "null.world.sign.not_alone.2"),
            new Message("dont_dig", "do not dig down", "null.world.sign.dont_dig.1", "null.world.sign.dont_dig.2"),
            new Message("lights_on", "we left the lights on", "null.world.sign.lights_on.1", "null.world.sign.lights_on.2"),
            new Message("who_placed", "who placed this sign?", "null.world.sign.who_placed.1", "null.world.sign.who_placed.2"),
            new Message("not_found", "world.dat not found", "null.world.sign.not_found.1", "null.world.sign.not_found.2"),
            new Message("remembers", "it remembers your name", "null.world.sign.remembers.1", "null.world.sign.remembers.2"),
            new Message("go_back", "go back go back go back", "null.world.sign.go_back.1", "null.world.sign.go_back.1", "null.world.sign.go_back.1"),
            new Message("still_here", "@name is still here", "@name", "null.world.sign.still_here.2"),
            new Message("watching", "it watches from the treeline", "null.world.sign.watching.1", "null.world.sign.watching.2", "null.world.sign.watching.3"),
            new Message("not_saved", "nothing here was saved", "null.world.sign.not_saved.1", "null.world.sign.not_saved.2"),
            new Message("wrong_world", "this is not your world", "null.world.sign.wrong_world.1", "null.world.sign.wrong_world.2"),
            new Message("left_behind", "you left something behind", "null.world.sign.left_behind.1", "null.world.sign.left_behind.2", "null.world.sign.left_behind.3"),
            new Message("name", "@name", "@name"),
            new Message("noise", "(unreadable)", "@noise", "@noise"),
            new Message("visitor", "@visitor was here", "@visitor", "null.world.sign.visitor.2"),
            new Message("sky", "do not look at the sky", "null.world.sign.sky.1", "null.world.sign.sky.2")
    };

    private StrangeSignBuilder() {
    }

    static boolean generate(GenerationRequest req) {
        ServerLevel level = req.level();
        if (!LocationFinder.canHaveSurface(level)) {
            return false;
        }
        RandomSource random = req.random();
        ServerPlayer player = req.player();
        int attempts = req.attempts(5);
        for (int i = 0; i < attempts; i++) {
            BlockPos ground = LocationFinder.findGround(level, player, random, req.minDistance(6.0, 4.0), 14.0,
                    req.view(LocationFinder.View.BEHIND), 4, pos -> true);
            if (ground == null) {
                continue;
            }
            BlockPos at = ground.above();
            Message message = pick(req, random);
            String visitor = visitorName(req);
            List<Component> lines = lines(message, player, visitor, random);
            DyeColor color = DyeColor.BLACK;
            boolean glowing = req.stageNumber() >= 5 && random.nextFloat() < 0.3F;
            if (glowing) {
                color = DyeColor.WHITE;
            }
            SignText front = Blueprint.text(lines, color, glowing);
            SignText back = random.nextFloat() < 0.2F
                    ? Blueprint.text(List.of(Component.empty(), Component.translatable("null.world.sign.back.1"), Component.empty(), Component.empty()),
                    DyeColor.BLACK, false)
                    : null;
            // Turned to face the player, the way a sign faces whoever placed it.
            double dx = at.getX() + 0.5 - player.getX();
            double dz = at.getZ() + 0.5 - player.getZ();
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            int rotation = RotationSegment.convertToSegment(yaw + 180.0F);
            Blueprint bp = new Blueprint();
            bp.sign(0, 0, 0, rotation, front, back, true);
            PlacementJob job = new PlacementJob(level, "strange_sign").atomic();
            bp.appendTo(job, at, Rotation.NONE, Mirror.NONE);
            String detail = message.english().replace("@name", player.getScoreboardName())
                    .replace("@visitor", visitor == null ? "someone" : visitor);
            if (CorruptedStructureGenerator.submit(req, job, "strange_sign", detail, at)) {
                return true;
            }
        }
        return false;
    }

    private static Message pick(GenerationRequest req, RandomSource random) {
        boolean visitorKnown = visitorName(req) != null;
        boolean skyRelevant = req.stageNumber() >= 4 || CorruptionManager.data(req.server()).moonProgress() > 0.3F;
        List<Message> pool = new ArrayList<>();
        for (Message m : MESSAGES) {
            if ((m.id().equals("visitor") && !visitorKnown) || (m.id().equals("sky") && !skyRelevant)) {
                continue;
            }
            pool.add(m);
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /** A Visitor name this player has seen lately, if any (remembered by the Visitors). */
    @Nullable
    private static String visitorName(GenerationRequest req) {
        for (WorldMemory.Entry entry : WorldMemory.recentFor(req.server(), req.player().getUUID(), 20L * 60L * 60L)) {
            if (entry.key().equals("visitor:seen") && !entry.detail().isBlank()) {
                return entry.detail();
            }
        }
        return null;
    }

    private static List<Component> lines(Message message, ServerPlayer player, @Nullable String visitor, RandomSource random) {
        List<Component> text = new ArrayList<>();
        for (String line : message.lines()) {
            switch (line) {
                case "@name" -> text.add(Component.literal(player.getScoreboardName()));
                case "@visitor" -> text.add(Component.literal(visitor == null ? "" : visitor));
                case "@noise" -> text.add(Component.literal(noise(random)).withStyle(ChatFormatting.OBFUSCATED));
                default -> text.add(Component.translatable(line));
            }
        }
        // Centre the message on the four lines of the sign.
        List<Component> out = new ArrayList<>();
        int pad = text.size() >= 3 ? (text.size() == 3 && random.nextBoolean() ? 1 : 0) : 1;
        for (int i = 0; i < pad; i++) {
            out.add(Component.empty());
        }
        out.addAll(text);
        while (out.size() < 4) {
            out.add(Component.empty());
        }
        return out.subList(0, 4);
    }

    private static String noise(RandomSource random) {
        int length = 6 + random.nextInt(7);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }
}
