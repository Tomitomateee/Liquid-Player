package dev.spotifyglass;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shares songs, invites, friend requests and online status between players via a free public
 * MQTT broker (no login, no daily message limit — ntfy.sh allowed only 250 messages a day per
 * internet connection). Every player keeps their song and online status as a "retained" message,
 * so others get it the moment they connect; the broker announces "offline" by itself if the game
 * stops without saying goodbye. The client only shows songs of players on the same server (by
 * UUID). Works without the mod on the server.
 */
public final class SongRelay {
	/** What a player is playing, plus their stats and playback position for listening along. */
	public record Song(String title, String artist, String image, boolean tag, boolean listen,
	                   int minutes, String topArtist, boolean playing, long positionMs, long positionAt,
	                   String spotifyId, long receivedAt, String name, String server) {
		/** Host position now, extrapolated from the last message. */
		public long livePosition() {
			return playing ? positionMs + (System.currentTimeMillis() - positionAt) : positionMs;
		}
	}

	/** An invite / accept / leave (or friend request) message from one player to another. */
	public record Control(String kind, UUID from, UUID to, String name) {}

	/** A friend's "I'm online" heartbeat ({@code where} "off" = just quit the game). */
	public record Presence(UUID from, String name, String where, String server, String title, String artist,
	                       String image, int minutes, String topArtist, long sentAt, boolean live) {}

	private static final String HOST = "broker.emqx.io";
	private static final int PORT = 1883, KEEPALIVE_S = 60;
	private static final String ROOT = "liquidplayer/" + hash("liquidplayer:global:v3") + "/";
	/** A song nobody re-sent for 5 minutes is dropped (its player is gone); playing songs are re-sent every 2. */
	private static final long EXPIRE_MS = 5 * 60_000;
	private static final long REPUBLISH_MS = 120_000;
	/** While someone listens along, the host re-sends its position this often. */
	private static final long FOLLOWED_REFRESH_MS = 10_000;
	private static final long MIN_GAP_MS = 2_000;

	private final Map<UUID, Song> songs = new ConcurrentHashMap<>();
	private final Queue<Control> controls = new ConcurrentLinkedQueue<>();
	private final Queue<Control> friendControls = new ConcurrentLinkedQueue<>();
	private final Queue<Presence> presences = new ConcurrentLinkedQueue<>();
	/** Sends happen here, never on the game thread. */
	private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "liquidplayer-relay-send");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean on;
	private volatile boolean connected;
	private volatile Socket socket;
	private volatile OutputStream out;
	private volatile UUID me;
	private String lastIdentity;
	private long lastPublish;
	/** Last song message, re-sent after reconnecting. */
	private volatile JsonObject lastSong;

	/** Connects to the relay ({@code address} non-null) or disconnects. */
	public synchronized void setServer(String address) {
		boolean want = address != null;
		// After switching accounts in-game, reconnect with the new UUID (my inbox, last will).
		UUID current = Minecraft.getInstance().getUser().getProfileId();
		if (connected && me != null && !me.equals(current)) {
			lastSong = null;
			closeSocket();
		}
		if (want == on) return;
		on = want;
		if (want) startReader();
		else closeSocket();
	}

	public Song get(UUID uuid) {
		Song s = songs.get(uuid);
		if (s != null && System.currentTimeMillis() - s.receivedAt() > EXPIRE_MS) {
			songs.remove(uuid);
			return null;
		}
		return s;
	}

	public boolean active() {
		return on;
	}

	/** True while the live connection to the relay is open. */
	public boolean connected() {
		return connected;
	}

	/** Everyone whose song is known right now (all servers, all Liquid Player users). */
	public Map<UUID, Song> all() {
		long now = System.currentTimeMillis();
		songs.values().removeIf(s -> now - s.receivedAt() > EXPIRE_MS);
		return songs;
	}

	/** How many players were seen on the relay recently. */
	public int knownPlayers() {
		long now = System.currentTimeMillis();
		return (int) songs.values().stream().filter(s -> now - s.receivedAt() <= EXPIRE_MS).count();
	}

	/** Next received invite/accept/leave message, or null. */
	public Control pollControl() {
		return controls.poll();
	}

	/** Next received friend request / acceptance / removal, or null. */
	public Control pollFriendControl() {
		return friendControls.poll();
	}

	/** Next received online heartbeat, or null. */
	public Presence pollPresence() {
		return presences.poll();
	}

	/** Waits (briefly) until everything queued has been sent; used while the game quits. */
	public void flush() {
		try {
			sender.submit(() -> { }).get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (Exception ignored) {
			// quitting anyway
		}
	}

	/** Sends my online heartbeat (or "bye"); {@code now} blocks briefly, for use while the game quits. */
	public void publishPresence(JsonObject body, boolean now) {
		if (!on) return;
		body.addProperty("k", "here");
		body.addProperty("ts", System.currentTimeMillis());
		String topic = ROOT + "p/" + body.get("u").getAsString();
		if (!now) {
			sender.execute(() -> send(topic, body, true));
			return;
		}
		flush();
		send(topic, body, true);
		try {
			OutputStream o = out;
			if (o != null) synchronized (this) { o.write(new byte[] { (byte) 0xE0, 0 }); o.flush(); } // clean DISCONNECT
		} catch (IOException ignored) {
			// quitting anyway
		}
	}

	/**
	 * Publishes my song (empty title = nothing playing). Sent when the song or play state changes,
	 * and refreshed periodically — often while someone listens along ({@code followed}).
	 */
	public void publish(UUID me, String name, String server, String title, String artist, String image, boolean tag, boolean listen,
	                    int minutes, String topArtist, boolean playing, long positionMs, String spotifyId, boolean followed) {
		if (!on) return;
		String identity = title + "|" + artist + "|" + image + "|" + tag + "|" + listen + "|" + playing + "|" + topArtist + "|" + spotifyId
				+ "|" + name + "|" + server;
		long now = System.currentTimeMillis();
		boolean changed = !identity.equals(lastIdentity);
		long refreshEvery = followed ? FOLLOWED_REFRESH_MS : REPUBLISH_MS;
		boolean refresh = !title.isEmpty() && now - lastPublish > refreshEvery;
		if ((!changed && !refresh) || now - lastPublish < MIN_GAP_MS) return;
		lastIdentity = identity;
		lastPublish = now;
		JsonObject o = new JsonObject();
		o.addProperty("u", me.toString());
		o.addProperty("t", title);
		o.addProperty("a", artist);
		o.addProperty("i", image);
		o.addProperty("n", tag);
		o.addProperty("l", listen);
		o.addProperty("m", minutes);
		o.addProperty("ta", topArtist);
		o.addProperty("pl", playing);
		o.addProperty("p", positionMs);
		o.addProperty("sid", spotifyId);
		o.addProperty("nm", name);
		o.addProperty("sv", server);
		o.addProperty("ts", now);
		lastSong = o;
		sender.execute(() -> {
			if (!send(ROOT + "s/" + me, o, true)) lastIdentity = null;
		});
	}

	/** Deletes my stored song (while quitting), so players who connect later don't see it. */
	public void clearSong(UUID me) {
		if (!on || me == null) return;
		lastSong = null;
		sender.execute(() -> {
			OutputStream o = out;
			if (o == null) return;
			try {
				byte[] packet = publishPacket(ROOT + "s/" + me, new byte[0], true);
				synchronized (this) {
					o.write(packet);
					o.flush();
				}
			} catch (IOException ignored) {
				// quitting anyway
			}
		});
		flush();
	}

	/** Forgets what was sent, so my song goes out again right away (e.g. after joining a server). */
	public void resend() {
		lastIdentity = null;
		lastPublish = 0;
	}

	/** Sends an invite ("invite"), acceptance ("accept"), "leave" or friend message to one player. */
	public void sendControl(String kind, UUID from, UUID to) {
		if (!on) return;
		JsonObject o = new JsonObject();
		o.addProperty("k", kind);
		o.addProperty("u", from.toString());
		o.addProperty("to", to.toString());
		o.addProperty("nm", Minecraft.getInstance().getUser().getName());
		o.addProperty("ts", System.currentTimeMillis());
		// Friend messages are kept by the broker (one per pair), so they also reach players who
		// are offline right now; invites only count live.
		if (kind.startsWith("friend") || kind.equals("unfriend")) sender.execute(() -> send(ROOT + "f/" + to + "/" + from, o, true));
		else sender.execute(() -> send(ROOT + "c/" + to, o, false));
	}

	/** Removes a handled friend request from {@code from} to me, so it doesn't come back. */
	public void clearFriendMessage(UUID me, UUID from) {
		if (!on) return;
		sender.execute(() -> {
			OutputStream o = out;
			if (o == null) return;
			try {
				byte[] packet = publishPacket(ROOT + "f/" + me + "/" + from, new byte[0], true);
				synchronized (this) {
					o.write(packet);
					o.flush();
				}
			} catch (IOException e) {
				closeSocket();
			}
		});
	}

	// ---------------------------------------------------------------- MQTT

	private boolean send(String topic, JsonObject body, boolean retain) {
		OutputStream o = out;
		if (o == null) return false;
		try {
			byte[] packet = publishPacket(topic, body.toString().getBytes(StandardCharsets.UTF_8), retain);
			synchronized (this) {
				o.write(packet);
				o.flush();
			}
			return true;
		} catch (IOException e) {
			SpotifyGlassClient.LOGGER.debug("Song-Relay: Senden fehlgeschlagen", e);
			closeSocket();
			return false;
		}
	}

	private void startReader() {
		Thread th = new Thread(() -> {
			long backoff = 2000;
			while (on) {
				try (Socket s = new Socket()) {
					UUID id = Minecraft.getInstance().getUser().getProfileId();
					me = id;
					s.connect(new InetSocketAddress(HOST, PORT), 10_000);
					s.setSoTimeout(KEEPALIVE_S * 1000 / 2);
					s.setTcpNoDelay(true);
					DataInputStream in = new DataInputStream(s.getInputStream());
					OutputStream o = s.getOutputStream();
					o.write(connectPacket(id));
					o.flush();
					int[] header = readHeader(in);
					byte[] ack = in.readNBytes(header[1]);
					if ((header[0] >> 4) != 2 || ack.length < 2 || ack[1] != 0) throw new IOException("MQTT connect refused");
					socket = s;
					o.write(subscribePacket(ROOT + "s/+", ROOT + "p/+", ROOT + "c/" + id, ROOT + "f/" + id + "/+"));
					o.flush();
					out = o;
					connected = true;
					backoff = 2000;
					JsonObject song = lastSong;
					if (song != null) sender.execute(() -> send(ROOT + "s/" + id, song, true));
					int idle = 0;
					while (on) {
						int[] h;
						try {
							h = readHeader(in);
						} catch (SocketTimeoutException timeout) {
							if (++idle >= 3) throw new IOException("MQTT timeout");
							synchronized (this) {
								o.write(new byte[] { (byte) 0xC0, 0 }); // PINGREQ
								o.flush();
							}
							continue;
						}
						idle = 0;
						byte[] body = in.readNBytes(h[1]);
						if (body.length < h[1]) throw new EOFException();
						if ((h[0] >> 4) == 3) onPublish(h[0], body);
					}
				} catch (Exception e) {
					if (on) SpotifyGlassClient.LOGGER.debug("Song-Relay getrennt, verbinde neu", e);
				} finally {
					connected = false;
					socket = null;
					out = null;
				}
				if (!on) break;
				try {
					Thread.sleep(backoff);
				} catch (InterruptedException e) {
					break;
				}
				backoff = Math.min(60_000, backoff * 2);
			}
		}, "liquidplayer-relay");
		th.setDaemon(true);
		th.start();
	}

	private void onPublish(int flags, byte[] body) {
		int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
		int pos = 2 + topicLen + (((flags >> 1) & 3) > 0 ? 2 : 0);
		if (pos > body.length) return;
		String topic = new String(body, 2, topicLen, StandardCharsets.UTF_8);
		String payload = new String(body, pos, body.length - pos, StandardCharsets.UTF_8);
		String rel = topic.substring(Math.min(topic.length(), ROOT.length()));
		if (payload.isEmpty()) {
			// A deleted song (player quit): off the lists.
			if (rel.startsWith("s/")) {
				try {
					songs.remove(UUID.fromString(rel.substring(2)));
				} catch (IllegalArgumentException ignored) {
					// not a player topic
				}
			}
			return;
		}
		handle(topic.substring(Math.min(topic.length(), ROOT.length())), payload, (flags & 1) != 0);
	}

	/** {@code stored}: a retained message from before I connected, otherwise it was just sent. */
	private void handle(String topic, String payload, boolean stored) {
		try {
			JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
			long now = System.currentTimeMillis();
			// Live messages count as "now" (the sender's clock may be off); stored ones by their time.
			long sentAt = !stored ? now : o.has("ts") ? Math.min(now, o.get("ts").getAsLong()) : 0;
			UUID uuid = UUID.fromString(o.get("u").getAsString());
			// Every player may only write their own song and status.
			if ((topic.startsWith("s/") || topic.startsWith("p/")) && !topic.substring(2).equals(uuid.toString())) return;
			if (topic.startsWith("p/")) {
				// Someone just went offline (quit, or the broker noticed the connection died):
				// take them off the lists right away. A stored "offline" may be older than their song.
				if ("off".equals(str(o, "w")) && !stored) songs.remove(uuid);
				presences.add(new Presence(uuid, clean(str(o, "nm")), clean(str(o, "w")), clean(str(o, "sv")),
						clean(str(o, "t")), clean(str(o, "a")), dev.spotifyglass.net.SongPackets.safeImage(str(o, "i")),
						o.has("m") ? Math.max(-1, o.get("m").getAsInt()) : -1, clean(str(o, "ta")),
						sentAt, !stored));
				return;
			}
			if (topic.startsWith("f/")) {
				// f/<to>/<from>: only the sender itself may write its request.
				if (!topic.endsWith("/" + uuid)) return;
				friendControls.add(new Control(str(o, "k"), uuid, UUID.fromString(str(o, "to")), clean(str(o, "nm"))));
				return;
			}
			if (topic.startsWith("c/")) {
				controls.add(new Control(str(o, "k"), uuid, UUID.fromString(str(o, "to")), clean(str(o, "nm"))));
				return;
			}
			if (!topic.startsWith("s/")) return;
			if (now - sentAt > EXPIRE_MS) return; // an old retained song
			// Live messages: their position is valid "now"; older ones are as old as their timestamp.
			long at = now - sentAt < 5000 ? now : sentAt;
			String title = clean(str(o, "t"));
			if (title.isEmpty()) {
				songs.remove(uuid);
				return;
			}
			songs.put(uuid, new Song(title, clean(str(o, "a")), dev.spotifyglass.net.SongPackets.safeImage(str(o, "i")),
					!o.has("n") || o.get("n").getAsBoolean(), !o.has("l") || o.get("l").getAsBoolean(),
					o.has("m") ? Math.max(-1, o.get("m").getAsInt()) : 0, clean(str(o, "ta")),
					o.has("pl") && o.get("pl").getAsBoolean(), o.has("p") ? Math.max(0, o.get("p").getAsLong()) : 0, at,
					str(o, "sid").matches("[A-Za-z0-9]{22}") ? str(o, "sid") : "", sentAt,
					clean(str(o, "nm")), clean(str(o, "sv"))));
		} catch (Exception ignored) {
			// Not one of ours or malformed: anyone can post to a public broker.
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/** Reads a fixed header: {type+flags, remaining length}. */
	private static int[] readHeader(DataInputStream in) throws IOException {
		int first = in.read();
		if (first < 0) throw new EOFException();
		int len = 0, mul = 1, b;
		do {
			b = in.read();
			if (b < 0) throw new EOFException();
			len += (b & 0x7F) * mul;
			mul *= 128;
		} while ((b & 0x80) != 0 && mul <= 128 * 128 * 128);
		return new int[] { first, len };
	}

	private byte[] connectPacket(UUID id) throws IOException {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		writeString(v, "MQTT");
		v.write(4); // MQTT 3.1.1
		v.write(0x02 | 0x04 | 0x20); // clean session, will, will retained
		v.write(KEEPALIVE_S >> 8);
		v.write(KEEPALIVE_S & 0xFF);
		writeString(v, "lp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
		// Last will: the broker marks me offline if the connection drops.
		writeString(v, ROOT + "p/" + id);
		JsonObject bye = new JsonObject();
		bye.addProperty("u", id.toString());
		bye.addProperty("k", "here");
		bye.addProperty("w", "off");
		bye.addProperty("nm", Minecraft.getInstance().getUser().getName());
		byte[] willBody = bye.toString().getBytes(StandardCharsets.UTF_8);
		v.write(willBody.length >> 8);
		v.write(willBody.length & 0xFF);
		v.write(willBody);
		return packet(0x10, v.toByteArray());
	}

	private static byte[] subscribePacket(String... topics) throws IOException {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		v.write(0);
		v.write(1); // packet id
		for (String t : topics) {
			writeString(v, t);
			v.write(0); // QoS 0
		}
		return packet(0x82, v.toByteArray());
	}

	private static byte[] publishPacket(String topic, byte[] payload, boolean retain) throws IOException {
		ByteArrayOutputStream v = new ByteArrayOutputStream();
		writeString(v, topic);
		v.write(payload);
		return packet(0x30 | (retain ? 1 : 0), v.toByteArray());
	}

	private static byte[] packet(int type, byte[] body) {
		ByteArrayOutputStream p = new ByteArrayOutputStream();
		p.write(type);
		int len = body.length;
		do {
			int b = len % 128;
			len /= 128;
			p.write(len > 0 ? b | 0x80 : b);
		} while (len > 0);
		p.writeBytes(body);
		return p.toByteArray();
	}

	private static void writeString(ByteArrayOutputStream v, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		v.write(b.length >> 8);
		v.write(b.length & 0xFF);
		v.write(b);
	}

	/** Untrusted text: strip formatting codes and control characters, limit length. */
	private static String clean(String s) {
		String out = s.replaceAll("§.?", "").replaceAll("\\p{Cntrl}", "").trim();
		return out.length() > 60 ? out.substring(0, 60) : out;
	}

	private void closeSocket() {
		Socket s = socket;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// already closed
			}
		}
	}

	/** One shared channel name for everyone. */
	private static String hash(String s) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(h, 0, 12);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
