package dev.spotifyglass;

import dev.spotifyglass.SpotifyClient.PlayerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listening along like a Spotify Jam, without login and without streaming audio: everyone hears
 * the song on their own Spotify, and the mod keeps the listener's song, position and pause in
 * sync with the host. Invites and the host's position go through the song relay.
 */
public final class ListenAlong {
	private static final long INVITE_TTL_MS = 120_000;

	/** Invites I received: host → time. */
	private static final Map<UUID, Long> INVITES = new ConcurrentHashMap<>();
	/** Players listening along with me. */
	private static final Set<UUID> FOLLOWERS = ConcurrentHashMap.newKeySet();
	private static volatile UUID following;
	private static String openedSong;
	private static long lastSeek, lastToggle, lostSince;
	private static UUID account;

	private ListenAlong() {}

	public static boolean hasFollowers() { return !FOLLOWERS.isEmpty(); }
	public static int followerCount() { return FOLLOWERS.size(); }
	public static UUID following() { return following; }
	public static boolean isInvitedBy(UUID host) { return INVITES.containsKey(host); }
	public static Set<UUID> invites() { return INVITES.keySet(); }

	/** Invite another player to listen along with me. */
	/** Invite another player to listen along with me; works across servers and sub-servers. */
	public static boolean invite(UUID player) {
		UUID me = SongTags.myId();
		if (me == null || !SongTags.relay().connected()) return false;
		SongTags.relay().sendControl("invite", me, player);
		return true;
	}

	public static void accept(UUID host) {
		UUID me = SongTags.myId();
		INVITES.remove(host);
		if (me == null) return;
		if (following != null && !following.equals(host)) SongTags.relay().sendControl("leave", me, following);
		following = host;
		openedSong = null;
		lostSince = 0;
		SongTags.relay().sendControl("accept", me, host);
		toast("Mithören", "Du hörst jetzt bei " + name(host) + " mit", host);
	}

	public static void decline(UUID host) {
		INVITES.remove(host);
	}

	public static void stop() {
		UUID me = SongTags.myId();
		if (following != null && me != null) SongTags.relay().sendControl("leave", me, following);
		following = null;
		toast("Mithören", "Du hörst nicht mehr mit", null);
	}

	/** Ends my jam: everyone listening along with me is let go. */
	public static void endJam() {
		UUID me = SongTags.myId();
		if (me != null) for (UUID f : FOLLOWERS) SongTags.relay().sendControl("leave", me, f);
		FOLLOWERS.clear();
		toast("Jam beendet", "Niemand hört mehr bei dir mit", null);
	}

	/** Called while the game quits: leave / end the jam so nobody waits for me. */
	public static void quit() {
		UUID me = SongTags.myId();
		if (me == null) return;
		if (following != null) SongTags.relay().sendControl("leave", me, following);
		for (UUID f : FOLLOWERS) SongTags.relay().sendControl("leave", me, f);
		SongTags.relay().flush();
	}

	/** Called every client tick (also in the menu: listening along doesn't depend on the server). */
	public static void tick(SpotifyClient spotify) {
		UUID me = SongTags.myId();
		if (me == null) return;
		if (!me.equals(account)) {
			// Switched accounts in-game: the jam belonged to the other account.
			account = me;
			INVITES.clear();
			FOLLOWERS.clear();
			following = null;
		}
		SongRelay.Control c;
		while ((c = SongTags.relay().pollControl()) != null) {
			if (!me.equals(c.to())) continue;
			switch (c.kind()) {
				case "invite" -> {
					INVITES.put(c.from(), System.currentTimeMillis());
					toast("Einladung zum Mithören", name(c.from()) + " lädt dich ein · P → Reinhören", c.from());
				}
				case "accept" -> {
					if (FOLLOWERS.add(c.from())) toast("Mithören", name(c.from()) + " hört jetzt bei dir mit", c.from());
				}
				case "leave" -> {
					if (FOLLOWERS.remove(c.from())) toast("Mithören", name(c.from()) + " hört nicht mehr mit", c.from());
					if (c.from().equals(following)) {
						following = null;
						toast("Mithören beendet", name(c.from()) + " hat aufgehört", c.from());
					}
				}
				default -> { }
			}
		}
		long now = System.currentTimeMillis();
		INVITES.values().removeIf(t -> now - t > INVITE_TTL_MS);

		if (following != null) sync(spotify, now);
	}

	/** Keeps my Spotify on the host's song, position and play state. */
	private static void sync(SpotifyClient spotify, long now) {
		SongRelay.Song host = SongTags.relay().get(following);
		if (host == null) {
			if (lostSince == 0) lostSince = now;
			if (now - lostSince > 60_000) {
				following = null;
				toast("Mithören beendet", "Vom Gastgeber kommt nichts mehr", null);
			}
			return;
		}
		lostSince = 0;
		PlayerState me = spotify.state();
		String hostKey = key(host.title(), host.artist());
		boolean sameSong = me.uri() != null && key(me.title(), me.artist()).equals(hostKey);
		if (!sameSong) {
			if (hostKey.equals(openedSong)) return; // already started; wait for Spotify to switch
			// Play exactly this song: via its Spotify ID if known, else the bridge searches in
			// Spotify and presses play on it. Both happen in the background.
			openedSong = hostKey;
			String id = host.spotifyId().isEmpty() ? TrackIdLookup.get(host.title(), host.artist()) : host.spotifyId();
			if (!id.isEmpty()) {
				spotify.play(new SpotifyClient.Track("spotify:track:" + id, host.title(), host.artist(), host.image()));
			} else {
				spotify.searchAndPlay(host.title(), host.artist());
			}
			toast("Mithören", "Spielt: " + host.title(), following);			return;
		}
		if (host.playing() != me.playing() && now - lastToggle > 2500) {
			lastToggle = now;
			spotify.togglePlay();
			return;
		}
		long target = host.livePosition();
		if (host.playing() && Math.abs(me.liveProgress() - target) > 3000 && now - lastSeek > 6000) {
			lastSeek = now;
			spotify.seek(target + 250);
		}
	}

	/** Same song even if versions differ slightly: "Song (feat. X) - Remastered" by "A, B" → "song|a". */
	private static String key(String title, String artist) {
		String tt = title.replaceAll("\\s*[(\\[].*?[)\\]]", "").replaceAll("\\s+-\\s+.*$", "");
		String aa = artist.contains(", ") ? artist.substring(0, artist.indexOf(", ")) : artist;
		return (tt + "|" + aa).toLowerCase(Locale.ROOT).trim();
	}

	public static String name(UUID uuid) {
		return SongTags.name(uuid);
	}

	/** Notifications pop up at the top right as liquid glass toasts in the theme colors. */
	private static void toast(String title, String text, UUID player) {
		dev.spotifyglass.gui.LiquidToast.show(title, text, player);
	}
}
