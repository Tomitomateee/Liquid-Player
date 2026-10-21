package dev.spotifyglass.net;

import dev.spotifyglass.net.SongPackets.NowPlaying;
import dev.spotifyglass.net.SongPackets.SongUpdate;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side (also runs in singleplayer/LAN): relays each player's song to everyone else who
 * has the mod. Without the mod on the server, songs can't be shared between players.
 */
public class LiquidPlayerCommon implements ModInitializer {
	private static final Map<UUID, NowPlaying> SONGS = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playC2S().register(NowPlaying.TYPE, NowPlaying.CODEC);
		PayloadTypeRegistry.playS2C().register(SongUpdate.TYPE, SongUpdate.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(NowPlaying.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();
			if (payload.title().isEmpty()) SONGS.remove(sender.getUUID());
			else SONGS.put(sender.getUUID(), payload);
			broadcast(context.server(), new SongUpdate(sender.getUUID(), payload.title(), payload.artist(), SongPackets.safeImage(payload.image()), payload.tag(), payload.listen(), payload.minutes(), payload.topArtist()));
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			// Tell the new player what everybody is listening to.
			server.execute(() -> {
				ServerPlayer player = handler.getPlayer();
				if (!ServerPlayNetworking.canSend(player, SongUpdate.TYPE)) return;
				SONGS.forEach((uuid, song) -> ServerPlayNetworking.send(player, new SongUpdate(uuid, song.title(), song.artist(), SongPackets.safeImage(song.image()), song.tag(), song.listen(), song.minutes(), song.topArtist())));
			});
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID uuid = handler.getPlayer().getUUID();
			if (SONGS.remove(uuid) != null) broadcast(server, new SongUpdate(uuid, "", "", "", false, false, 0, ""));
		});
	}

	private static void broadcast(MinecraftServer server, SongUpdate update) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, SongUpdate.TYPE)) ServerPlayNetworking.send(p, update);
		}
	}
}
