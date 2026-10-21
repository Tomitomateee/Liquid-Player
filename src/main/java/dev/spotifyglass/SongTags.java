package dev.spotifyglass;

import dev.spotifyglass.SpotifyClient.PlayerState;
import dev.spotifyglass.net.SongPackets;
import dev.spotifyglass.net.SongPackets.NowPlaying;
import dev.spotifyglass.net.SongPackets.SongUpdate;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of song sharing: sends my song (with my listening stats) and stores everyone
 * else's, for the card above names, the "Reinhören" list and profiles.
 */
public final class SongTags {
	/** What to show above a player's name. {@code image} may be empty. */
	public record Label(String title, String artist, String image) {}

	/** A player who is currently sharing a song, with their stats from the mod. */
	public record Listener(UUID uuid, String title, String artist, String image, int minutes, String topArtist, String spotifyId) {
		/** Track to open: plays directly via its Spotify ID if known, else opens the search. */
		public SpotifyClient.Track track() {
			return new SpotifyClient.Track(spotifyId.isEmpty() ? "listen:" + uuid : "spotify:track:" + spotifyId, title, artist, image);
		}
	}

	private static final Map<UUID, NowPlaying> OTHERS = new ConcurrentHashMap<>();
	private static final SongRelay RELAY = new SongRelay();
	private static NowPlaying lastSent;
	private static volatile boolean sharing;

	private SongTags() {}

	public static SongRelay relay() {
		return RELAY;
	}

	/** True on multiplayer servers and open LAN worlds. */
	public static boolean sharing() {
		return sharing;
	}

	/** The logged-in account (can change in-game), or null. */
	public static UUID myId() {
		try {
			return Minecraft.getInstance().getUser().getProfileId();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * A short hash of the server address ("" when not on a server): the whole server, including
	 * all its sub-servers, has the same one. Others can't read the address from it.
	 */
	public static String myServer() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getCurrentServer() == null || mc.isLocalServer()) return "";
		String ip = mc.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT).trim();
		if (ip.endsWith(":25565")) ip = ip.substring(0, ip.length() - 6);
		if (ip.endsWith(".")) ip = ip.substring(0, ip.length() - 1);
		try {
			byte[] h = java.security.MessageDigest.getInstance("SHA-256")
					.digest(("liquidplayer:" + ip).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(h, 0, 8);
		} catch (Exception e) {
			return "";
		}
	}

	/** A player's name: from the tab list, else from their shared song, else from my friends. */
	public static String name(UUID uuid) {
		Minecraft mc = Minecraft.getInstance();
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
		if (info != null) return info.getProfile().name();
		SongRelay.Song s = RELAY.get(uuid);
		if (s != null && !s.name().isEmpty()) return s.name();
		Friends.Friend f = Friends.get(uuid);
		if (f != null && !f.name.isEmpty()) return f.name;
		return "Ein Spieler";
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(SongUpdate.TYPE, (payload, context) -> {
			if (payload.title().isEmpty()) OTHERS.remove(payload.player());
			else OTHERS.put(payload.player(), new NowPlaying(payload.title(), payload.artist(), SongPackets.safeImage(payload.image()),
					payload.tag(), payload.listen(), payload.minutes(), payload.topArtist()));
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			lastSent = null;
			RELAY.resend();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			OTHERS.clear();
			lastSent = null;
		});
	}

	/** Called every client tick: shares my song via the server (if it has the mod) and the internet relay. */
	public static void tick(SpotifyClient spotify) {
		Minecraft mc = Minecraft.getInstance();
		// The internet relay is connected the whole time: my song (if I share it) goes to every
		// Liquid Player user, tagged with my server so the "Server" tab can find players on the
		// same server — also on other sub-servers of a network, which aren't in my tab list.
		boolean lan = mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished();
		sharing = mc.player != null && ((mc.getCurrentServer() != null && !mc.isLocalServer()) || lan);
		RELAY.setServer("on");

		NowPlaying mine = mySong(spotify);
		NowPlaying wanted = mine != null ? mine : new NowPlaying("", "", "", false, false, 0, "");
		PlayerState s = spotify.state();
		UUID me = myId();
		if (me != null) {
			// While someone listens along, the position must go out even if my list entry is off.
			boolean followed = ListenAlong.hasFollowers();
			boolean send = mine != null || (followed && s.uri() != null);
			RELAY.publish(me, mc.getUser().getName(), myServer(),
					send ? cut(s.title(), 60) : "", send ? cut(s.artist(), 60) : "", wanted.image(),
					wanted.tag() && sharing, wanted.listen() || followed, sharedMinutes(spotify), sharedTopArtist(spotify),
					s.playing(), s.liveProgress(), send ? TrackIdLookup.get(s.title(), s.artist()) : "", followed);
		}
		if (mc.player == null) return;

		if (!ClientPlayNetworking.canSend(NowPlaying.TYPE)) return;
		if (!wanted.equals(lastSent)) {
			ClientPlayNetworking.send(wanted);
			lastSent = wanted;
		}
	}

	static NowPlaying mySong(SpotifyClient spotify) {
		SpotifyConfig cfg = spotify.config;
		PlayerState s = spotify.state();
		// Shared if it should appear above my name and/or in other players' "Reinhören" list.
		if ((!cfg.nametagShare && !cfg.listenShare) || !s.playing() || s.uri() == null) return null;
		String image = SongPackets.safeImage(s.imageUrl());
		// The cover from Windows is a local file: share a public one instead.
		if (image.isEmpty()) image = SongPackets.safeImage(ArtworkLookup.get(s.title(), s.artist()));
		return new NowPlaying(cut(s.title(), 60), cut(s.artist(), 60), image, cfg.nametagShare, cfg.listenShare,
				sharedMinutes(spotify), sharedTopArtist(spotify));
	}

	/** My stats as others see them: -1 / empty when my profile is private. */
	static int sharedMinutes(SpotifyClient spotify) {
		return spotify.config.profilePrivate ? -1 : ListenStats.totalMinutes();
	}

	static String sharedTopArtist(SpotifyClient spotify) {
		return spotify.config.profilePrivate ? "" : cut(ListenStats.topArtist(), 60);
	}

	/** The label to show above this player's name, or null for none. */
	public static Label labelFor(UUID uuid) {
		SpotifyClient spotify = SpotifyGlassClient.spotify();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getUUID().equals(uuid)) {
			NowPlaying mine = mySong(spotify);
			if (!spotify.config.nametagShare || mine == null) return null;
			return new Label(mine.title(), mine.artist(), mine.image());
		}
		if (!spotify.config.nametagOthers) return null;
		NowPlaying song = OTHERS.get(uuid);
		if (song != null) return song.tag() && !song.title().isEmpty() ? new Label(song.title(), song.artist(), song.image()) : null;
		SongRelay.Song relayed = RELAY.get(uuid);
		if (relayed == null || !relayed.tag()) return null;
		return new Label(relayed.title(), relayed.artist(), relayed.image());
	}

	/** Players on my server (all its sub-servers) who share their song for "Reinhören". */
	public static List<Listener> listeners() {
		return listeners(false);
	}

	/**
	 * Everyone else who shares their song for "Reinhören": {@code global} = every Liquid Player
	 * user anywhere, otherwise only players on my server — in my tab list, or on another
	 * sub-server of the same network (same server address).
	 */
	public static List<Listener> listeners(boolean global) {
		Minecraft mc = Minecraft.getInstance();
		Map<UUID, Listener> all = new LinkedHashMap<>();
		OTHERS.forEach((uuid, song) -> {
			if (!song.title().isEmpty() && song.listen()) {
				all.put(uuid, new Listener(uuid, song.title(), song.artist(), song.image(), song.minutes(), song.topArtist(), ""));
			}
		});
		java.util.Set<UUID> present = new java.util.HashSet<>();
		if (mc.getConnection() != null) for (var info : mc.getConnection().getOnlinePlayers()) present.add(info.getProfile().id());
		if (mc.level != null) for (var p : mc.level.players()) present.add(p.getUUID());
		String server = myServer();
		RELAY.all().forEach((uuid, s) -> {
			if (all.containsKey(uuid) || !s.listen()) return;
			boolean here = present.contains(uuid) || (!server.isEmpty() && server.equals(s.server()));
			if (global || here) all.put(uuid, new Listener(uuid, s.title(), s.artist(), s.image(), s.minutes(), s.topArtist(), s.spotifyId()));
		});
		UUID me = myId();
		if (me != null) all.remove(me);
		if (mc.player != null) all.remove(mc.player.getUUID());
		return new ArrayList<>(all.values());
	}

	/** One player's entry (on any server), or null if they don't share. */
	public static Listener listener(UUID uuid) {
		for (Listener l : listeners(true)) if (l.uuid().equals(uuid)) return l;
		return null;
	}

	private static String cut(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
