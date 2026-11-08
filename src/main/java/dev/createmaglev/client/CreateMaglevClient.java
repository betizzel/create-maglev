package dev.createmaglev.client;

import dev.createmaglev.client.content.MaglevRenderers;
import dev.createmaglev.client.ponder.MaglevPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import net.fabricmc.api.ClientModInitializer;

public final class CreateMaglevClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PonderIndex.addPlugin(new MaglevPonderPlugin());
        MaglevRenderers.register();
    }
}
