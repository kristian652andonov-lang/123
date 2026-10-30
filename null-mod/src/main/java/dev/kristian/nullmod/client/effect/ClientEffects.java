package dev.kristian.nullmod.client.effect;

import dev.kristian.nullmod.NullMod;
import dev.kristian.nullmod.network.ClientEffectPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Client effect ids and their handlers. Server events send {@link ClientEffectPayload}s;
 * client systems may also start effects locally with {@link #trigger}. Ids use a prefix per
 * system: {@code visual.}, {@code block.}, {@code audio.}, {@code moon.}, {@code chat.} ...
 */
public final class ClientEffects {
    private static final Map<String, ClientEffectHandler> HANDLERS = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();

    private ClientEffects() {
    }

    public static void register(String id, ClientEffectHandler handler) {
        if (HANDLERS.putIfAbsent(id, handler) != null) {
            throw new IllegalStateException("Duplicate NULL client effect " + id);
        }
    }

    public static boolean has(String id) {
        return HANDLERS.containsKey(id);
    }

    public static void dispatch(ClientEffectPayload effect) {
        ClientEffectHandler handler = HANDLERS.get(effect.effect());
        if (handler == null) {
            if (WARNED.add(effect.effect())) {
                NullMod.LOGGER.warn("Unknown NULL client effect '{}'", effect.effect());
            }
            return;
        }
        try {
            handler.run(effect);
        } catch (RuntimeException e) {
            NullMod.LOGGER.error("NULL client effect '{}' failed", effect.effect(), e);
        }
    }

    /** Starts an effect from client code, exactly as if the server had sent it. */
    public static void trigger(String id, long seed, float intensity, int duration) {
        dispatch(new ClientEffectPayload(id, seed, intensity, duration));
    }

    public static void trigger(String id, long seed, float intensity, int duration, BlockPos pos) {
        dispatch(new ClientEffectPayload(id, seed, intensity, duration, pos));
    }

    public static void trigger(String id, long seed, float intensity, int duration, Optional<BlockPos> pos, int data, String text) {
        dispatch(new ClientEffectPayload(id, seed, intensity, duration, pos, data, text));
    }
}
