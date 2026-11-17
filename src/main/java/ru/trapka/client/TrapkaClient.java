
package ru.trapka.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

public class TrapkaClient implements ClientModInitializer {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(
                            "trapkaclient",
                            "main"
                    )
            );

    private static final KeyMapping TRAP_KEY =
            KeyBindingHelper.registerKeyBinding(
                    new KeyMapping(
                            "key.trapkaclient.activate",
                            InputConstants.Type.KEYSYM,
                            InputConstants.KEY_V,
                            CATEGORY
                    )
            );

    @Override
    public void onInitializeClient() {

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (TRAP_KEY.consumeClick()) {

                if (client.screen == null) {
                    TrapkaAutomation.tryStart(client);
                }
            }

            TrapkaAutomation.tick(client);
        });
    }
}
