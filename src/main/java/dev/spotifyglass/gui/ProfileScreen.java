package dev.spotifyglass.gui;

import dev.spotifyglass.CoverCache;
import dev.spotifyglass.Friends;
import dev.spotifyglass.ListenAlong;
import dev.spotifyglass.ListenStats;
import dev.spotifyglass.SongTags;
import dev.spotifyglass.SpotifyClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A player's profile: listening time and top artist (counted by the mod, only for songs played
 * while it ran) and their current song. Other players' profiles offer invite / listen-along and
 * friend requests; my own profile lets me make it private and shows my friends on the right.
 * The shown data refreshes once a minute while open.
 */
public class ProfileScreen extends Screen {
	private static final int W = 260;
	/** Friends panel right of my own profile. */
	private static final int FW = 190, GAP = 10, ROW_H = 36, REQ_H = 26;
	private static final long REFRESH_MS = 60_000;

	private final Screen parent;
	private final SpotifyClient spotify;
	private final UUID player;
	private final boolean self;
	private final int H;
	private int px, py;
	private String status = "";
	private long statusAt;
	private int friendScroll;
	/** Friend whose remove button was clicked once (click again to confirm). */
	private UUID confirmRemove;

	/** Snapshot of the profile, refreshed once a minute. */
	private long refreshedAt;
	private boolean known;
	private int minutes;
	private String topArtist = "", title = "", artist = "", image = "";

	public ProfileScreen(Screen parent, SpotifyClient spotify, UUID player) {
		super(Component.literal("Profil"));
		this.parent = parent;
		this.spotify = spotify;
		this.player = player;
		this.self = net.minecraft.client.Minecraft.getInstance().player != null
				&& net.minecraft.client.Minecraft.getInstance().player.getUUID().equals(player);
		this.H = self ? 200 : 224;
	}

	@Override
	protected void init() {
		int total = self ? W + GAP + FW : W;
		px = (width - total) / 2;
		py = (height - H) / 2;
		refresh();
	}

	@Override
	public void tick() {
		super.tick();
		if (System.currentTimeMillis() - refreshedAt >= REFRESH_MS) refresh();
	}

	private void refresh() {
		refreshedAt = System.currentTimeMillis();
		if (self) {
			SpotifyClient.PlayerState s = spotify.state();
			known = true;
			minutes = ListenStats.totalMinutes();
			topArtist = ListenStats.topArtist();
			boolean playing = s.uri() != null && s.playing();
			title = playing ? s.title() : "";
			artist = playing ? s.artist() : "";
			image = playing && s.imageUrl() != null ? s.imageUrl() : "";
			return;
		}
		SongTags.Listener l = SongTags.listener(player);
		Friends.Friend f = Friends.get(player);
		if (l == null && f != null) {
			// Friends' stats are known even when they don't share their song right now.
			known = f.lastSeen > 0;
			minutes = f.minutes;
			topArtist = f.topArtist;
			title = f.online() ? f.title : "";
			artist = f.online() ? f.artist : "";
			image = f.online() ? f.image : "";
			return;
		}
		known = l != null;
		minutes = l == null ? 0 : l.minutes();
		topArtist = l == null ? "" : l.topArtist();
		title = l == null ? "" : l.title();
		artist = l == null ? "" : l.artist();
		image = l == null ? "" : l.image();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		spotify.config.save();
		minecraft.setScreen(parent);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		renderBlurredBackground(g);
		if (spotify.config.blackMode) g.fillGradient(0, 0, width, height, 0x60000000, 0x80000000);
		else g.fillGradient(0, 0, width, height, 0x40F3EEF9, 0x50D9D2E6);
	}

	private int buttonCount() { return self ? 2 : 3; }

	private int buttonY(int i) { return py + H - 6 - (buttonCount() - i) * 24; }

	private void panel(GuiGraphics g, Theme t, int x, int y, int w, int h) {
		float alpha = Math.max(20, Math.min(100, spotify.config.opacity)) / 100f;
		Glass.shadow(g, x, y, w, h, 16, 110);
		ShapeCache.roundRectFaded(g, x, y, w, h, 16, Glass.argb(0xFF, t.panelTop()), Glass.argb(0xD4, t.panelBottom()), alpha);
		g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
		Glass.blob(g, x + w - 30, y + 30, 60, Glass.argb(0x40, t.blobA()));
		Glass.blob(g, x + 20, y + h - 20, 50, Glass.argb(0x30, t.blobB()));
		g.disableScissor();
		Glass.roundRect(g, x + 3, y + 1, w - 6, 24, 15, 0x50FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, x, y, w, h, 16, 0xC0FFFFFF);
		Season.decoratePanel(g, spotify.config.season, x, y, w, h, x == px);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		Theme t = Theme.current(spotify.config);
		panel(g, t, px, py, W, H);
		boolean hc = hover(mx, my, px + W - 28, py + 9, 16, 14);
		Glass.roundRect(g, px + W - 27, py + 15, 12, 2, 1, hc ? t.accentBottom() : t.textFaint());

		// Head + name.
		PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(player);
		Glass.shadow(g, px + 16, py + 16, 32, 32, 6, 90);
		if (info != null) PlayerFaceRenderer.draw(g, info.getSkin(), px + 16, py + 16, 32);
		else Glass.roundRect(g, px + 16, py + 16, 32, 32, 6, t.accentTop(), t.accentBottom());
		Friends.Friend friend = self ? null : Friends.get(player);
		String name = self && minecraft.player != null ? minecraft.player.getName().getString()
				: info == null && friend != null ? friend.name : ListenAlong.name(player);
		g.pose().pushMatrix();
		g.pose().translate(px + 58, py + 20);
		g.pose().scale(1.3f, 1.3f);
		g.drawString(font, clip(name, 120), 0, 0, t.text(), false);
		g.pose().popMatrix();
		String relay = SongTags.relay().connected() ? "Relay online" : "Relay verbindet…";
		String sub = self ? (spotify.config.profilePrivate ? "Privat · " : "") + relay
				: player.equals(ListenAlong.following()) ? "Du hörst gerade mit"
				: friend != null ? "Freund · " + Friends.status(friend) : "";
		g.drawString(font, clip(sub, W - 72), px + 58, py + 36, self && spotify.config.profilePrivate ? t.textSoft() : t.accentBottom(), false);

		// Stats (hidden for others if the profile is private).
		boolean hidden = !self && known && minutes < 0;
		Glass.glass(g, px + 14, py + 58, W - 28, 40, 10, 0x50FFFFFF, 0x30FFFFFF);
		g.drawString(font, "Hörzeit", px + 24, py + 64, t.textSoft(), false);
		g.drawString(font, hidden ? "Privat" : !known ? "–" : ListenStats.formatMinutes(minutes), px + 24, py + 76, t.text(), false);
		g.drawString(font, "Meistgehört", px + W / 2, py + 64, t.textSoft(), false);
		g.drawString(font, clip(hidden ? "Privat" : topArtist.isEmpty() ? "–" : topArtist, W / 2 - 24), px + W / 2, py + 76, t.text(), false);
		long next = Math.max(0, (REFRESH_MS - (System.currentTimeMillis() - refreshedAt)) / 1000);
		g.drawString(font, "gezählt mit Liquid Player · neu in " + next + "s", px + 24, py + 88, t.textFaint(), false);

		// Current song.
		Glass.glass(g, px + 14, py + 104, W - 28, 30, 10, 0x50FFFFFF, 0x30FFFFFF);
		if (!title.isEmpty()) {
			Identifier cover = image.isEmpty() ? null : CoverCache.get(image);
			if (cover != null) g.blit(RenderPipelines.GUI_TEXTURED, cover, px + 20, py + 108, 0, 0, 22, 22, 22, 22);
			else Glass.roundRect(g, px + 20, py + 108, 22, 22, 5, t.accentTop(), t.accentBottom());
			g.drawString(font, clip(title, W - 80), px + 48, py + 110, t.text(), false);
			g.drawString(font, clip(artist, W - 80), px + 48, py + 121, t.textSoft(), false);
		} else {
			g.drawString(font, "Hört gerade nichts", px + 24, py + 115, t.textSoft(), false);
		}

		// Buttons.
		int bw = W - 28;
		String[] labels = buttonLabels();
		for (int i = 0; i < labels.length; i++) {
			boolean h = hover(mx, my, px + 14, buttonY(i), bw, 20);
			if (i == 0) Glass.pill(g, px + 14, buttonY(i), bw, 20, t.accentTop(), t.accentBottom(), h);
			else Glass.pill(g, px + 14, buttonY(i), bw, 20, t.secondTop(), t.secondBottom(), h);
			centered(g, labels[i], px + W / 2, buttonY(i) + 6, 0xFFFFFFFF);
		}

		if (self) renderFriends(g, t, mx, my);

		if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 4000) {
			int tw = font.width(status) + 20;
			Glass.glass(g, px + (W - tw) / 2, py + H + 6, tw, 16, 8, Glass.argb(0xF0, t.pillTop()), Glass.argb(0xE8, t.pillBottom()));
			centered(g, status, px + W / 2, py + H + 10, t.text());
		}
		super.render(g, mx, my, pt);
	}

	private String[] buttonLabels() {
		if (self) {
			return new String[] {
					spotify.config.profilePrivate ? "Profil privat: An" : "Profil privat: Aus",
					spotify.config.listenShare ? "In „Reinhören“ zeigen: An" : "In „Reinhören“ zeigen: Aus" };
		}
		String main;
		if (player.equals(ListenAlong.following())) main = "Mithören beenden";
		else if (ListenAlong.isInvitedBy(player)) main = "Einladung annehmen – mithören";
		else main = "Zum Mithören einladen";
		String friend = Friends.isFriend(player) ? (player.equals(confirmRemove) ? "Wirklich entfernen?" : "Freund entfernen")
				: Friends.requestedBy(player) ? "Freundschaftsanfrage annehmen"
				: Friends.requested(player) ? "Anfrage gesendet…" : "Als Freund hinzufügen";
		return new String[] { main, "Song in Spotify öffnen", friend };
	}

	// ---------------------------------------------------------------- friends panel

	private int fx() { return px + W + GAP; }
	private int listTop() { return py + 30; }
	private int listBottom() { return py + H - 22; }

	/** Requests first, then friends; one entry per row. */
	private List<Object> friendRows() {
		List<Object> rows = new ArrayList<>(Friends.incoming().entrySet());
		rows.addAll(Friends.list());
		return rows;
	}

	private int rowHeight(Object row) {
		return row instanceof Map.Entry<?, ?> ? REQ_H : ROW_H;
	}

	private void renderFriends(GuiGraphics g, Theme t, int mx, int my) {
		int x = fx(), y = py;
		panel(g, t, x, y, FW, H);
		g.drawString(font, "Freunde", x + 14, y + 12, t.text(), false);
		String count = Friends.onlineCount() + " online";
		g.drawString(font, count, x + FW - 14 - font.width(count), y + 12, t.accentBottom(), false);

		List<Object> rows = friendRows();
		int top = listTop(), bottom = listBottom();
		friendScroll = Math.max(0, Math.min(friendScroll, Math.max(0, rows.size() - 1)));
		g.enableScissor(x + 4, top, x + FW - 4, bottom);
		if (rows.isEmpty()) {
			centered(g, "Noch keine Freunde", x + FW / 2, (top + bottom) / 2 - 14, t.textSoft());
			centered(g, "Öffne das Profil eines Spielers", x + FW / 2, (top + bottom) / 2 - 2, t.textFaint());
			centered(g, "→ „Als Freund hinzufügen“", x + FW / 2, (top + bottom) / 2 + 8, t.textFaint());
		}
		int ry = top + 2;
		for (int i = friendScroll; i < rows.size() && ry < bottom; i++) {
			Object row = rows.get(i);
			int rh = rowHeight(row), rx = x + 8, rw = FW - 16;
			if (row instanceof Map.Entry<?, ?> e) renderRequest(g, t, mx, my, (UUID) e.getKey(), (String) e.getValue(), rx, ry, rw);
			else renderFriend(g, t, mx, my, (Friends.Friend) row, rx, ry, rw);
			ry += rh + 4;
		}
		g.disableScissor();
		g.drawString(font, clip("Hörzeit zählt nur mit Liquid Player", FW - 28), x + 14, y + H - 15, t.textFaint(), false);
	}

	private void renderRequest(GuiGraphics g, Theme t, int mx, int my, UUID uuid, String name, int x, int y, int w) {
		Glass.glass(g, x, y, w, REQ_H, 9, Glass.argb(0x90, t.accentTop()), Glass.argb(0x70, t.accentBottom()));
		g.drawString(font, clip(name, w - 70), x + 8, y + 4, 0xFFFFFFFF, false);
		g.drawString(font, "möchte dein Freund sein", x + 8, y + 14, 0xE0FFFFFF, false);
		boolean ho = hover(mx, my, x + w - 48, y + 5, 20, 16), hn = hover(mx, my, x + w - 24, y + 5, 20, 16);
		Glass.pill(g, x + w - 48, y + 5, 20, 16, t.secondTop(), t.secondBottom(), ho);
		centered(g, "✔", x + w - 38, y + 9, 0xFFFFFFFF);
		Glass.pill(g, x + w - 24, y + 5, 20, 16, 0xFF9A9AA8, 0xFF7A7A88, hn);
		centered(g, "✕", x + w - 14, y + 9, 0xFFFFFFFF);
	}

	private void renderFriend(GuiGraphics g, Theme t, int mx, int my, Friends.Friend f, int x, int y, int w) {
		boolean hov = hover(mx, my, x, y, w, ROW_H);
		Glass.glass(g, x, y, w, ROW_H, 10, hov ? 0x90FFFFFF : 0x58FFFFFF, hov ? 0x70FFFFFF : 0x38FFFFFF);
		UUID id = f.id();
		PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(id);
		if (info != null) {
			PlayerFaceRenderer.draw(g, info.getSkin(), x + 6, y + 8, 20);
		} else {
			Glass.roundRect(g, x + 6, y + 8, 20, 20, 5, t.accentTop(), t.accentBottom());
			centered(g, f.name.isEmpty() ? "?" : f.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), x + 16, y + 14, 0xFFFFFFFF);
		}
		// Online dot on the head.
		int dot = f.online() ? 0xFF3DDC84 : 0xFF9A9AA8;
		Glass.roundRect(g, x + 21, y + 23, 7, 7, 3.5f, 0xFFFFFFFF, 0xFFFFFFFF);
		Glass.roundRect(g, x + 22, y + 24, 5, 5, 2.5f, dot, dot);

		int tx = x + 32, tw = w - 36;
		String mins = f.minutes < 0 ? (f.lastSeen > 0 ? "Privat" : "") : ListenStats.formatMinutes(f.minutes);
		boolean showRemove = hov || id.equals(confirmRemove);
		int right = showRemove ? 14 : 0;
		g.drawString(font, clip(f.name, tw - font.width(mins) - 6 - right), tx, y + 4, t.text(), false);
		if (!mins.isEmpty()) g.drawString(font, mins, x + w - 4 - right - font.width(mins), y + 4, t.textSoft(), false);
		g.drawString(font, clip(Friends.status(f), tw), tx, y + 14, f.online() ? t.accentBottom() : t.textFaint(), false);
		String third = f.online() && !f.title.isEmpty() ? "♪ " + f.title + " – " + f.artist
				: f.topArtist.isEmpty() ? "" : "Meistgehört: " + f.topArtist;
		g.drawString(font, clip(third, tw), tx, y + 24, t.textSoft(), false);
		if (showRemove) {
			boolean hr = hover(mx, my, x + w - 16, y + 2, 14, 12);
			centered(g, "✕", x + w - 9, y + 4, id.equals(confirmRemove) ? 0xFFE05561 : hr ? t.accentBottom() : t.textFaint());
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (self && hover((int) mx, (int) my, fx(), py, FW, H)) {
			friendScroll = Math.max(0, friendScroll - (int) Math.signum(sy));
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	private boolean clickFriends(int mx, int my) {
		int x = fx();
		if (!hover(mx, my, x, py, FW, H)) return false;
		List<Object> rows = friendRows();
		int ry = listTop() + 2, rx = x + 8, rw = FW - 16;
		for (int i = friendScroll; i < rows.size() && ry < listBottom(); i++) {
			Object row = rows.get(i);
			if (row instanceof Map.Entry<?, ?> e) {
				UUID id = (UUID) e.getKey();
				if (hover(mx, my, rx + rw - 48, ry + 5, 20, 16)) {
					Friends.accept(id);
					return true;
				}
				if (hover(mx, my, rx + rw - 24, ry + 5, 20, 16)) {
					Friends.decline(id);
					return true;
				}
			} else if (hover(mx, my, rx, ry, rw, ROW_H)) {
				Friends.Friend f = (Friends.Friend) row;
				if (hover(mx, my, rx + rw - 16, ry + 2, 14, 12)) {
					if (f.id().equals(confirmRemove)) {
						Friends.remove(f.id());
						confirmRemove = null;
						setStatus(f.name + " ist nicht mehr dein Freund");
					} else {
						confirmRemove = f.id();
						setStatus("Nochmal auf ✕ klicken zum Entfernen");
					}
				} else {
					minecraft.setScreen(new ProfileScreen(this, spotify, f.id()));
				}
				return true;
			}
			ry += rowHeight(row) + 4;
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != 0) return super.mouseClicked(e, doubleClick);
		int mx = (int) e.x(), my = (int) e.y();
		if (hover(mx, my, px + W - 28, py + 9, 16, 14)) {
			onClose();
			return true;
		}
		if (self && clickFriends(mx, my)) return true;
		int bw = W - 28;
		boolean b1 = hover(mx, my, px + 14, buttonY(0), bw, 20), b2 = hover(mx, my, px + 14, buttonY(1), bw, 20);
		boolean b3 = !self && hover(mx, my, px + 14, buttonY(2), bw, 20);
		if (!b3) confirmRemove = null;
		if (self) {
			if (b1) {
				spotify.config.profilePrivate = !spotify.config.profilePrivate;
				spotify.config.save();
				setStatus(spotify.config.profilePrivate ? "Hörzeit und Top-Artist sind jetzt privat" : "Profil ist wieder sichtbar");
				return true;
			}
			if (b2) {
				spotify.config.listenShare = !spotify.config.listenShare;
				spotify.config.save();
				return true;
			}
			return super.mouseClicked(e, doubleClick);
		}
		if (b1) {
			if (player.equals(ListenAlong.following())) {
				ListenAlong.stop();
				setStatus("Mithören beendet");
			} else if (ListenAlong.isInvitedBy(player)) {
				ListenAlong.accept(player);
				setStatus("Du hörst jetzt mit");
			} else if (ListenAlong.invite(player)) {
				setStatus("Einladung gesendet");
			} else {
				setStatus("Song-Relay verbindet noch… gleich nochmal");
			}
			return true;
		}
		if (b2) {
			SongTags.Listener l = SongTags.listener(player);
			if (l != null) spotify.play(l.track());
			else if (!title.isEmpty()) spotify.play(new SpotifyClient.Track("listen:" + player, title, artist, image));
			else setStatus("Hört gerade nichts");
			return true;
		}
		if (b3) {
			if (Friends.isFriend(player)) {
				if (player.equals(confirmRemove)) {
					Friends.remove(player);
					confirmRemove = null;
					setStatus("Freund entfernt");
				} else {
					confirmRemove = player;
				}
			} else if (Friends.requestedBy(player)) {
				Friends.accept(player);
				setStatus("Ihr seid jetzt Freunde");
			} else if (Friends.requested(player)) {
				setStatus("Warte, bis die Anfrage angenommen wird");
			} else if (!SongTags.relay().connected()) {
				setStatus("Relay verbindet noch… gleich nochmal");
			} else {
				Friends.request(player);
				setStatus("Freundschaftsanfrage gesendet");
			}
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	private void setStatus(String s) {
		status = s;
		statusAt = System.currentTimeMillis();
	}

	private static boolean hover(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	private void centered(GuiGraphics g, String text, int cx, int y, int color) {
		g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
	}

	private String clip(String text, int maxWidth) {
		if (text == null) return "";
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
	}
}
