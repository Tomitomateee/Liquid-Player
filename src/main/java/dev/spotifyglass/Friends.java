package dev.spotifyglass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Friends without any login: a request goes to the other player through the song relay, and once
 * they accept, both keep each other in {@code config/liquid-player-friends.json}. Friends send a
 * small "online" heartbeat while the game runs (from the menu too), so the friend list shows who
 * is online, what they hear and their listening stats, on any server.
 */
public final class Friends {
	/** A friend; the last known stats are saved, the online state is not. */
	public static final class Friend {
		public String uuid;
		public String name = "";
		public int minutes = -1;
		public String topArtist = "";
		public long lastSeen;
		transient boolean quit;
		/** "menu", "single", "lan" or "multi". */
		public transient String where = "";
		transient String server = "";
		public transient String title = "", artist = "", image = "";

		public UUID id() { return UUID.fromString(uuid); }

		public boolean online() {
			return !quit && lastSeen > 0 && System.currentTimeMillis() - lastSeen < ONLINE_MS;
		}

		/** True if we're both on the same multiplayer server. */
		public boolean sameServer() {
			return online() && "multi".equals(where) && !server.isEmpty() && server.equals(myServer());
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Heartbeat interval, and how long a friend counts as online without one. */
	private static final long HEARTBEAT_MS = 60_000, ONLINE_MS = 150_000, MIN_GAP_MS = 5_000;

	private static final Map<UUID, Friend> FRIENDS = new LinkedHashMap<>();
	/** Requests I received (uuid → name) and sent (uuid → time; kept on disk until answered). */
	private static final Map<UUID, String> INCOMING = new LinkedHashMap<>();
	private static final Map<UUID, Long> OUTGOING = new LinkedHashMap<>();
	private static long lastBeat;
	private static String lastBeatIdentity = "";
	/** The account whose list is loaded: every Minecraft account has its own friends. */
	private static UUID loadedFor;

	/** What is saved per account. */
	private static final class Saved {
		List<Friend> friends = new ArrayList<>();
		Map<String, Long> requested = new LinkedHashMap<>();
	}

	private Friends() {}

	private static Path file(UUID account) {
		return FabricLoader.getInstance().getConfigDir().resolve("liquid-player-friends-" + account + ".json");
	}

	/** Loads the list of the account that is logged in now (accounts can be switched in-game). */
	private static void ensureLoaded() {
		UUID me = me();
		if (me == null || me.equals(loadedFor)) return;
		loadedFor = me;
		FRIENDS.clear();
		INCOMING.clear();
		OUTGOING.clear();
		lastBeatIdentity = "";
		try {
			Path f = file(me);
			if (!Files.exists(f)) return;
			Saved saved = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Saved.class);
			if (saved == null) return;
			if (saved.friends != null) for (Friend fr : saved.friends) {
				try {
					if (fr != null && fr.uuid != null) FRIENDS.put(fr.id(), fr);
				} catch (IllegalArgumentException ignored) {
					// broken entry
				}
			}
			if (saved.requested != null) saved.requested.forEach((k, v) -> {
				try {
					OUTGOING.put(UUID.fromString(k), v);
				} catch (IllegalArgumentException ignored) {
					// broken entry
				}
			});
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Freundesliste nicht lesbar", e);
		}
	}

	private static void save() {
		if (loadedFor == null) return;
		try {
			Saved s = new Saved();
			s.friends.addAll(FRIENDS.values());
			OUTGOING.forEach((k, v) -> s.requested.put(k.toString(), v));
			Path f = file(loadedFor);
			Files.createDirectories(f.getParent());
			Files.writeString(f, GSON.toJson(s), StandardCharsets.UTF_8);
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Freundesliste nicht speicherbar", e);
		}
	}

	// ---------------------------------------------------------------- queries (client thread)

	/** Online friends first, then by name. */
	public static List<Friend> list() {
		ensureLoaded();
		List<Friend> out = new ArrayList<>(FRIENDS.values());
		out.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
		return out;
	}

	public static int onlineCount() {
		return (int) FRIENDS.values().stream().filter(Friend::online).count();
	}

	public static boolean isFriend(UUID uuid) { return FRIENDS.containsKey(uuid); }
	public static Friend get(UUID uuid) { return FRIENDS.get(uuid); }
	public static boolean requested(UUID uuid) { return OUTGOING.containsKey(uuid); }
	public static boolean requestedBy(UUID uuid) { return INCOMING.containsKey(uuid); }
	public static Map<UUID, String> incoming() { return INCOMING; }

	// ---------------------------------------------------------------- actions

	/** Sends a friend request (or accepts theirs, if they already asked me). */
	public static void request(UUID player) {
		if (INCOMING.containsKey(player)) {
			accept(player);
			return;
		}
		OUTGOING.put(player, System.currentTimeMillis());
		save();
		send("friend", player);
	}

	public static void accept(UUID player) {
		String name = INCOMING.remove(player);
		OUTGOING.remove(player);
		add(player, name == null ? ListenAlong.name(player) : name);
		send("friendok", player);
		handled(player);
	}

	public static void decline(UUID player) {
		INCOMING.remove(player);
		handled(player);
	}

	public static void remove(UUID player) {
		OUTGOING.remove(player);
		FRIENDS.remove(player);
		save();
		send("unfriend", player);
		handled(player);
	}

	/** Deletes their stored message to me, so it isn't handled again at the next start. */
	private static void handled(UUID from) {
		UUID me = me();
		if (me != null) SongTags.relay().clearFriendMessage(me, from);
	}

	private static void add(UUID player, String name) {
		Friend f = FRIENDS.get(player);
		if (f == null) {
			f = new Friend();
			f.uuid = player.toString();
			FRIENDS.put(player, f);
		}
		if (name != null && !name.isEmpty()) f.name = name;
		save();
		lastBeatIdentity = ""; // tell the new friend right away that I'm online
		dev.spotifyglass.gui.LiquidToast.show("Neuer Freund", f.name + " ist jetzt dein Freund", player);
	}

	private static void send(String kind, UUID to) {
		UUID me = me();
		if (me != null) SongTags.relay().sendControl(kind, me, to);
	}

	// ---------------------------------------------------------------- tick

	/** Called every client tick, also in the menu. */
	public static void tick(SpotifyClient spotify) {
		UUID me = me();
		if (me == null) return;
		ensureLoaded();
		SongRelay relay = SongTags.relay();
		SongRelay.Control c;
		while ((c = relay.pollFriendControl()) != null) {
			if (!me.equals(c.to()) || me.equals(c.from())) continue;
			UUID from = c.from();
			String name = c.name().isEmpty() ? ListenAlong.name(from) : c.name();
			switch (c.kind()) {
				case "friend" -> {
					if (FRIENDS.containsKey(from) || OUTGOING.containsKey(from)) {
						// Already friends (they lost their list), or we both asked: just confirm.
						OUTGOING.remove(from);
						if (!FRIENDS.containsKey(from)) add(from, name);
						send("friendok", from);
						handled(from);
					} else if (!INCOMING.containsKey(from)) {
						INCOMING.put(from, name);
						dev.spotifyglass.gui.LiquidToast.show("Freundschaftsanfrage", name + " · P → Profil → Freunde", from);
					}
				}
				case "friendok" -> {
					if (OUTGOING.remove(from) != null) add(from, name);
					handled(from);
				}
				case "unfriend" -> {
					OUTGOING.remove(from);
					INCOMING.remove(from);
					if (FRIENDS.remove(from) != null) {
						save();
						dev.spotifyglass.gui.LiquidToast.show("Freunde", name + " hat dich entfernt", from);
					}
					handled(from);
				}
				default -> { }
			}
		}
		SongRelay.Presence p;
		while ((p = relay.pollPresence()) != null) onPresence(p, me);
		heartbeat(spotify, me, System.currentTimeMillis());
	}

	private static void onPresence(SongRelay.Presence p, UUID me) {
		if (p.from().equals(me)) return;
		Friend f = FRIENDS.get(p.from());
		if (f == null) return;
		boolean wasOnline = f.online();
		if ("off".equals(p.where())) {
			f.quit = true;
			if (p.sentAt() > f.lastSeen) f.lastSeen = p.sentAt();
			return;
		}
		if (p.sentAt() < f.lastSeen) return; // older than what we know
		if (!p.name().isEmpty()) f.name = p.name();
		f.lastSeen = p.sentAt();
		f.quit = false;
		f.where = p.where();
		f.server = p.server();
		f.title = p.title();
		f.artist = p.artist();
		f.image = p.image();
		f.minutes = p.minutes();
		f.topArtist = p.topArtist();
		save();
		// Only live heartbeats, not the replayed history from before I started the game.
		if (p.live() && !wasOnline && f.online()) {
			dev.spotifyglass.gui.LiquidToast.show("Freund online", f.name + " ist jetzt online", p.from());
		}
	}

	/** Tells my friends that I'm online, what I hear and my stats; once a minute and on changes. */
	private static void heartbeat(SpotifyClient spotify, UUID me, long now) {
		if (FRIENDS.isEmpty() || !SongTags.relay().connected()) return;
		JsonObject o = presence(spotify, me);
		String identity = o.toString();
		boolean changed = !identity.equals(lastBeatIdentity);
		if ((!changed && now - lastBeat < HEARTBEAT_MS) || now - lastBeat < MIN_GAP_MS) return;
		lastBeat = now;
		lastBeatIdentity = identity;
		SongTags.relay().publishPresence(o, false);
	}

	/** Called while the game quits: friends see me go offline, and I leave everyone's lists, right away. */
	public static void goodbye() {
		UUID me = me();
		if (me == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("u", me.toString());
		o.addProperty("nm", Minecraft.getInstance().getUser().getName());
		o.addProperty("w", "off");
		SongTags.relay().publishPresence(o, true);
	}

	private static JsonObject presence(SpotifyClient spotify, UUID me) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject o = new JsonObject();
		o.addProperty("u", me.toString());
		o.addProperty("nm", mc.getUser().getName());
		boolean lan = mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished();
		String where = mc.player == null ? "menu" : lan ? "lan" : mc.isLocalServer() ? "single" : "multi";
		o.addProperty("w", where);
		o.addProperty("sv", "multi".equals(where) ? myServer() : "");
		// My song only if I share it at all (name tag or "Reinhören").
		var song = SongTags.mySong(spotify);
		if (song != null) {
			o.addProperty("t", song.title());
			o.addProperty("a", song.artist());
			o.addProperty("i", song.image());
		}
		o.addProperty("m", SongTags.sharedMinutes(spotify));
		o.addProperty("ta", SongTags.sharedTopArtist(spotify));
		return o;
	}

	private static UUID me() {
		try {
			return Minecraft.getInstance().getUser().getProfileId();
		} catch (Exception e) {
			return null;
		}
	}

	/** A short hash of the server address, so friends can tell "same server" without seeing it. */
	static String myServer() {
		return SongTags.myServer();
	}

	/** "Online · auf deinem Server", "Offline · vor 3 Std" … */
	public static String status(Friend f) {
		if (!f.online()) {
			if (f.lastSeen <= 0) return "Offline";
			long min = (System.currentTimeMillis() - f.lastSeen) / 60_000;
			String ago = min < 60 ? Math.max(1, min) + " Min" : min < 48 * 60 ? (min / 60) + " Std" : (min / 1440) + " Tagen";
			return "Offline · vor " + ago;
		}
		if (f.sameServer()) return "Online · auf deinem Server";
		return switch (f.where) {
			case "menu" -> "Online · im Menü";
			case "single" -> "Online · Einzelspieler";
			case "lan" -> "Online · LAN-Welt";
			case "multi" -> "Online · auf einem Server";
			default -> "Online";
		};
	}
}
