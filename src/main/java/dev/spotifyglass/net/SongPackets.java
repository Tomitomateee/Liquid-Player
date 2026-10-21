package dev.spotifyglass.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Packets for sharing "now playing" between players who have the mod. */
public final class SongPackets {
	private SongPackets() {}

	/** Only Spotify's and Apple's cover CDNs are accepted, since the URL comes from other players. */
	public static String safeImage(String url) {
		if (url == null || url.length() >= 200) return "";
		return url.startsWith("https://i.scdn.co/image/") || url.matches("https://is\\d-ssl\\.mzstatic\\.com/image/thumb/[^\\s]+") ? url : "";
	}

	/**
	 * Client → server: my current song. An empty title means "nothing / hidden". {@code tag}: show it
	 * above my name; {@code listen}: list it under "Reinhören". Both are chosen separately.
	 */
	public record NowPlaying(String title, String artist, String image, boolean tag, boolean listen,
	                         int minutes, String topArtist) implements CustomPacketPayload {
		public static final Type<NowPlaying> TYPE = new Type<>(Identifier.fromNamespaceAndPath("spotifyglass", "now_playing_v4"));
		public static final StreamCodec<ByteBuf, NowPlaying> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(128), NowPlaying::title,
				ByteBufCodecs.stringUtf8(128), NowPlaying::artist,
				ByteBufCodecs.stringUtf8(256), NowPlaying::image,
				ByteBufCodecs.BOOL, NowPlaying::tag,
				ByteBufCodecs.BOOL, NowPlaying::listen,
				ByteBufCodecs.VAR_INT, NowPlaying::minutes,
				ByteBufCodecs.stringUtf8(128), NowPlaying::topArtist,
				NowPlaying::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server → client: the song of one player. An empty title removes it. */
	public record SongUpdate(UUID player, String title, String artist, String image, boolean tag, boolean listen,
	                         int minutes, String topArtist) implements CustomPacketPayload {
		public static final Type<SongUpdate> TYPE = new Type<>(Identifier.fromNamespaceAndPath("spotifyglass", "song_update_v4"));
		public static final StreamCodec<ByteBuf, SongUpdate> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, SongUpdate::player,
				ByteBufCodecs.stringUtf8(128), SongUpdate::title,
				ByteBufCodecs.stringUtf8(128), SongUpdate::artist,
				ByteBufCodecs.stringUtf8(256), SongUpdate::image,
				ByteBufCodecs.BOOL, SongUpdate::tag,
				ByteBufCodecs.BOOL, SongUpdate::listen,
				ByteBufCodecs.VAR_INT, SongUpdate::minutes,
				ByteBufCodecs.stringUtf8(128), SongUpdate::topArtist,
				SongUpdate::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
