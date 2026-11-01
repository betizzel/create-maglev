package dev.createmaglev.client;

import dev.createmaglev.client.content.MaglevRenderers;
import net.fabricmc.api.ClientModInitializer;

public final class CreateMaglevClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MaglevRenderers.register();
    }
}
