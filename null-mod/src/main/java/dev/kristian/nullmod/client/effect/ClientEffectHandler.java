package dev.kristian.nullmod.client.effect;

import dev.kristian.nullmod.network.ClientEffectPayload;

/** Runs a client effect. Always called on the render thread. */
@FunctionalInterface
public interface ClientEffectHandler {
    void run(ClientEffectPayload effect);
}
