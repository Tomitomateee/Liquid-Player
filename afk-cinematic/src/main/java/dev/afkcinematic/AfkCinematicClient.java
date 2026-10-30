package dev.afkcinematic;

import com.mojang.blaze3d.platform.InputConstants;
import dev.afkcinematic.cinematic.CinematicController;
import dev.afkcinematic.config.CinematicConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point of AFK Cinematic: loads the config, registers the toggle key and drives the
 * {@link CinematicController} every client tick. Everything else is hooked in via mixins.
 */
public class AfkCinematicClient implements ClientModInitializer {
	public static final String MOD_ID = "afkcinematic";
	public static final Logger LOGGER = LoggerFactory.getLogger("AFK Cinematic");

	@Override
	public void onInitializeClient() {
		CinematicConfig.get();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		KeyMapping toggle = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.afkcinematic.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

		CinematicController controller = CinematicController.get();
		ClientTickEvents.END_CLIENT_TICK.register(client -> controller.tick(client, toggle));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> controller.shutdown());
	}
}
