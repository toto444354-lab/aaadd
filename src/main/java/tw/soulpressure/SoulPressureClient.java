package tw.soulpressure;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Only client-side code lives in this entrypoint. */
public final class SoulPressureClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBinding areaKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.soulpressure.area", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R,
            "key.category.soulpressure"
        ));
        KeyBinding singleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.soulpressure.single", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
            "key.category.soulpressure"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getNetworkHandler() == null) return;
            while (areaKey.wasPressed()) {
                ClientPlayNetworking.send(SoulPressureMod.AREA_PACKET, PacketByteBufs.empty());
            }
            while (singleKey.wasPressed()) {
                ClientPlayNetworking.send(SoulPressureMod.SINGLE_PACKET, PacketByteBufs.empty());
            }
        });
    }
}
