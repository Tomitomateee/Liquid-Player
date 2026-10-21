package dev.spotifyglass;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spotifyglass.gui.SongHud;
import dev.spotifyglass.gui.SpotifyScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotifyGlassClient implements ClientModInitializer {
	public static final String MOD_ID = "spotifyglass";
	public static final Logger LOGGER = LoggerFactory.getLogger("Liquid Player");

	private static SpotifyClient spotify;

	public static SpotifyClient spotify() {
		return spotify;
	}

	@Override
	public void onInitializeClient() {
		spotify = new SpotifyClient(SpotifyConfig.load());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "spotify"));
		KeyMapping open = register("open", GLFW.GLFW_KEY_P, category);
		KeyMapping toggle = register("toggle", InputConstants.UNKNOWN.getValue(), category);
		KeyMapping next = register("next", InputConstants.UNKNOWN.getValue(), category);
		KeyMapping previous = register("previous", InputConstants.UNKNOWN.getValue(), category);

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"),
				(graphics, delta) -> SongHud.renderHud(graphics, spotify));

		SongTags.init();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			ListenAlong.quit();
			SongTags.relay().clearSong(SongTags.myId());
			Friends.goodbye();
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ListenStats.tick(spotify.state());
			SongTags.tick(spotify);
			ListenAlong.tick(spotify);
			Friends.tick(spotify);

			while (open.consumeClick()) {
				if (client.screen == null) client.setScreen(new SpotifyScreen(spotify));
			}
			while (toggle.consumeClick()) spotify.togglePlay();
			while (next.consumeClick()) spotify.next();
			while (previous.consumeClick()) spotify.previous();
		});
	}

	private static KeyMapping register(String name, int key, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key." + MOD_ID + "." + name, InputConstants.Type.KEYSYM, key, category));
	}
}
