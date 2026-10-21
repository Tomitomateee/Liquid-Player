package dev.spotifyglass.gui;

import dev.spotifyglass.CoverCache;
import dev.spotifyglass.ListenAlong;
import dev.spotifyglass.SongTags;
import dev.spotifyglass.SpotifyClient;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import dev.spotifyglass.SpotifyClient.PlayerState;
import dev.spotifyglass.SpotifyClient.Track;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The liquid glass Spotify UI: a centered player panel (big now-playing, controls below) and a
 * separate search panel on its right.
 */
public class SpotifyScreen extends Screen {
	// Player panel, search panel.
	private static final int PW = 220, SW = 170, GAP = 10, H = 250;
	private static final int ROW_Y = 58, ROW_H = 24, ROW_STEP = 27, VISIBLE_ROWS = 6;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int DRAWER_W = 196, DRAWER_H = 232;

	// Theme colors; set from the selected Theme at the start of every frame.
	private static int TEXT, TEXT_SOFT, TEXT_FAINT, ICON;
	private static int PURPLE, PURPLE_TOP, PURPLE_BOTTOM;
	private static int TEAL_TOP, TEAL_BOTTOM;
	private static int PILL_WHITE_TOP, PILL_WHITE_BOTTOM;
	private static int PANEL_TOP, PANEL_BOTTOM, BLOB_A, BLOB_B;
	private static float PANEL_ALPHA;

	private static void applyTheme(Theme t, int opacity) {
		TEXT = t.text();
		TEXT_SOFT = t.textSoft();
		TEXT_FAINT = t.textFaint();
		ICON = t.textSoft();
		PURPLE = t.accentBottom();
		PURPLE_TOP = t.accentTop();
		PURPLE_BOTTOM = t.accentBottom();
		TEAL_TOP = t.secondTop();
		TEAL_BOTTOM = t.secondBottom();
		PILL_WHITE_TOP = t.pillTop();
		PILL_WHITE_BOTTOM = t.pillBottom();
		PANEL_ALPHA = Math.max(20, Math.min(100, opacity)) / 100f;
		PANEL_TOP = Glass.argb(0xFF, t.panelTop());
		PANEL_BOTTOM = Glass.argb(0xD4, t.panelBottom());
		BLOB_A = Glass.argb(0x40, t.blobA());
		BLOB_B = Glass.argb(0x30, t.blobB());
	}

	private final SpotifyClient spotify;
	private EditBox searchBox;
	/** Player panel x, search panel x, shared y. */
	private int px, sx, py;
	private int scroll;
	private String pressedUri;
	private long pressedAt;
	private boolean drawerOpen;
	private float drawerAnim;
	private long lastFrame;
	private boolean draggingOpacity;
	/** When the color was last changed; a small preview of all display styles shows for a moment. */
	private long previewAt;
	private boolean moveMode;
	private boolean draggingHud;
	private int hudGrabX, hudGrabY;

	// Karaoke panel (left of the player).
	private static final int LW_MAX = 170;
	private int listenScroll;
	private float lyricsAnim;
	/** Collapse animation of the search panel on the player's right. */
	private float searchAnim;
	private int lx, lw = LW_MAX;
	private boolean draggingVolume;
	private float volumeLocal = -1, volumeBeforeMute = 0.5f;
	private long volumeSetAt, volumeSentAt;
	private boolean lyricsInline = true;
	private float frameDt;

	public SpotifyScreen(SpotifyClient spotify) {
		super(Component.literal("Liquid Player"));
		this.spotify = spotify;
		applyTheme(Theme.current(spotify.config), spotify.config.opacity);
		lyricsAnim = spotify.config.lyricsOpen ? 1 : 0;
		searchAnim = spotify.config.searchOpen ? 1 : 0;
	}

	/** Positions the panels; the group re-centers smoothly while the side panels open or close. */
	private void layout() {
		float e = ease(lyricsAnim);
		int rightExtra = Math.round(ease(searchAnim) * (SW + GAP));
		int avail = width - 12 - (PW + SW + 2 * GAP);
		lyricsInline = avail >= 120;
		lw = lyricsInline ? Math.min(LW_MAX, avail) : LW_MAX;
		int extra = lyricsInline ? Math.round(e * (lw + GAP)) : 0;
		int total = PW + rightExtra + extra;
		px = (width - PW) / 2;
		if (px - extra < 6 || px + PW + rightExtra > width - 6) px = (width - total) / 2 + extra;
		sx = px + PW + GAP;
		py = (height - H) / 2;
		lx = lyricsInline ? px - GAP - lw : Math.max(6, px - lw / 2);
		if (searchBox != null) {
			searchBox.setX(sx + 20);
			searchBox.setY(py + 36);
		}
	}

	private static float ease(float t) {
		float u = 1 - t;
		return 1 - u * u * u;
	}

	// ================================================================ lifecycle

	@Override
	protected void init() {
		layout();
		String old = searchBox != null ? searchBox.getValue() : "";
		searchBox = new EditBox(font, sx + 20, py + 36, 112, 10, Component.literal("Suche"));
		searchBox.setBordered(false);
		searchBox.setTextShadow(false);
		searchBox.setMaxLength(100);
		searchBox.setTextColor(TEXT);
		searchBox.setHint(Component.literal("Song suchen…").withColor(0xFF8E8AA0));
		searchBox.setValue(old);
		addRenderableWidget(searchBox);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// While positioning the song display, keep the game visible.
		if (moveMode) {
			g.fill(0, 0, width, height, 0x30000000);
			return;
		}
		// Blur the world behind the panels (vanilla menu blur) and wash it with a soft pastel tint.
		renderBlurredBackground(g);
		if (spotify.config.blackMode) g.fillGradient(0, 0, width, height, 0x60000000, 0x80000000);
		else g.fillGradient(0, 0, width, height, 0x40F3EEF9, 0x50D9D2E6);
	}

	// ================================================================ rendering

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		applyTheme(Theme.current(spotify.config), spotify.config.opacity);
		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		drawerAnim += ((drawerOpen ? 1 : 0) - drawerAnim) * Math.min(1, dt * 12);
		if (Math.abs(drawerAnim - (drawerOpen ? 1 : 0)) < 0.002f) drawerAnim = drawerOpen ? 1 : 0;
		boolean lo = spotify.config.lyricsOpen;
		lyricsAnim += ((lo ? 1 : 0) - lyricsAnim) * Math.min(1, dt * 9);
		if (Math.abs(lyricsAnim - (lo ? 1 : 0)) < 0.002f) lyricsAnim = lo ? 1 : 0;
		boolean so = spotify.config.searchOpen;
		searchAnim += ((so ? 1 : 0) - searchAnim) * Math.min(1, dt * 9);
		if (Math.abs(searchAnim - (so ? 1 : 0)) < 0.002f) searchAnim = so ? 1 : 0;
		layout();
		frameDt = dt;

		if (moveMode) {
			renderMoveMode(g, mx, my);
			return;
		}

		searchBox.setTextColor(TEXT);
		searchBox.visible = searchVisible();
		if (lyricsInline) renderLyrics(g, mx, my);
		panel(g, px, py, PW, H);
		renderPlayer(g, mx, my);
		if (searchAnim > 0.001f) {
			// Reveal from the player's edge outward, like the karaoke panel.
			g.enableScissor(px + PW, py - 8, sx + Math.round(ease(searchAnim) * SW) + 8, py + H + 12);
			panel(g, sx, py, SW, H);
			renderSearch(g, mx, my);
			g.disableScissor();
		}
		if (!lyricsInline) {
			g.nextStratum();
			renderLyrics(g, mx, my);
		}
		renderLyricsTab(g, mx, my);
		renderSearchTab(g, mx, my);
		if (spotify.config.season != Season.OFF) {
			g.nextStratum();
			Season.overlay(g, spotify.config.season, width, height);
		}
		renderDrawer(g, mx, my);
		renderThemePreview(g);
		renderToast(g, px + PW / 2, py + 8, PW - 104, spotify.message());
		super.render(g, mx, my, pt);
	}

	private void panel(GuiGraphics g, int x, int y, int w, int h) {
		Glass.shadow(g, x, y, w, h, 16, 110);
		ShapeCache.roundRectFaded(g, x, y, w, h, 16, PANEL_TOP, PANEL_BOTTOM, PANEL_ALPHA);
		g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
		Glass.blob(g, x + w - 30, y + h - 40, 60, BLOB_A);
		Glass.blob(g, x + 30, y + 40, 50, 0x30FFFFFF);
		Glass.blob(g, x + 20, y + h - 20, 40, BLOB_B);
		g.disableScissor();
		Glass.roundRect(g, x + 3, y + 1, w - 6, 24, 15, 0x50FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, x, y, w, h, 16, 0xC0FFFFFF);
		Season.decoratePanel(g, spotify.config.season, x, y, w, h, x == px);
	}

	/** Settings drawer that slides down out of the header when the gear is clicked. */
	private void renderDrawer(GuiGraphics g, int mx, int my) {
		if (drawerAnim <= 0) return;
		float e = 1 - (1 - drawerAnim) * (1 - drawerAnim) * (1 - drawerAnim); // ease-out cubic
		int dx = px + 12, top = py + 26;
		int dy = top - Math.round((1 - e) * (DRAWER_H + 4));
		// New stratum so the player's text underneath does not draw over the drawer.
		g.nextStratum();
		g.enableScissor(px, top, px + PW, top + DRAWER_H + 12);

		Glass.shadow(g, dx, dy, DRAWER_W, DRAWER_H, 12, 120);
		Glass.roundRect(g, dx, dy, DRAWER_W, DRAWER_H, 12, Glass.argb(0xF4, PILL_WHITE_TOP), Glass.argb(0xF0, PILL_WHITE_BOTTOM));
		Glass.roundOutline(g, dx, dy, DRAWER_W, DRAWER_H, 12, 0xC0FFFFFF);

		g.drawString(font, "Farbe", dx + 12, dy + 9, TEXT, false);
		String themeName = spotify.config.season != Season.OFF ? Season.NAMES[spotify.config.season]
				: spotify.config.theme == Theme.CUSTOM ? "Eigene Farben" : Theme.get(spotify.config.theme).name();
		g.drawString(font, themeName, dx + DRAWER_W - 12 - font.width(themeName), dy + 9, TEXT_SOFT, false);
		for (int i = 0; i < Theme.ALL.length; i++) {
			Theme t = Theme.ALL[i];
			int sx0 = swatchX(dx, i), sy0 = swatchY(dy, i);
			boolean sel = i == spotify.config.theme;
			boolean hov = hover(mx, my, sx0, sy0, 20, 20);
			Glass.shadow(g, sx0, sy0, 20, 20, 10, hov ? 110 : 60);
			Glass.roundRect(g, sx0, sy0, 20, 20, 10, t.accentTop(), t.accentBottom());
			Glass.roundRect(g, sx0 + 10, sy0 + 10, 8, 8, 4, t.secondTop(), t.secondBottom());
			Glass.roundRect(g, sx0 + 4, sy0 + 2, 12, 6, 4, 0x60FFFFFF, 0x00FFFFFF);
			Glass.roundOutline(g, sx0, sy0, 20, 20, 10, 0xB0FFFFFF);
			if (sel) {
				Glass.roundOutline(g, sx0 - 3, sy0 - 3, 26, 26, 13, TEXT);
				Glass.checkIcon(g, sx0 + 8, sy0 + 9, WHITE);
			}
		}

		// Own colors: shows the current custom gradient with a +, opens the color page.
		{
			int sx0 = swatchX(dx, Theme.CUSTOM), sy0 = swatchY(dy, Theme.CUSTOM);
			boolean hov = hover(mx, my, sx0, sy0, 20, 20);
			int c1 = 0xFF000000 | Theme.hsv(spotify.config.customHue1, 0.8f, 1f), c2 = 0xFF000000 | Theme.hsv(spotify.config.customHue2, 0.8f, 1f);
			Glass.shadow(g, sx0, sy0, 20, 20, 10, hov ? 110 : 60);
			Glass.roundRect(g, sx0, sy0, 20, 20, 10, c1, c2);
			Glass.roundOutline(g, sx0, sy0, 20, 20, 10, 0xB0FFFFFF);
			Glass.plusIcon(g, sx0 + 10, sy0 + 10, 8, WHITE);
			if (spotify.config.theme == Theme.CUSTOM) Glass.roundOutline(g, sx0 - 3, sy0 - 3, 26, 26, 13, TEXT);
		}

		toggleRow(g, dx, dy + 78, "Black Mode", spotify.config.blackMode);

		g.drawString(font, "Deckkraft", dx + 12, dy + 100, TEXT, false);
		int op = spotify.config.opacity;
		String ot = op + "%";
		g.drawString(font, ot, dx + DRAWER_W - 12 - font.width(ot), dy + 100, TEXT_SOFT, false);
		int ox = dx + 14, ow = DRAWER_W - 28, oy = dy + 116;
		Glass.roundRect(g, ox, oy, ow, 4, 2, 0x50A09CB8);
		int of = Math.round(ow * (op - 20) / 80f);
		Glass.roundRect(g, ox, oy, Math.max(4, of), 4, 2, PURPLE_TOP, PURPLE_BOTTOM);
		Glass.shadow(g, ox + of - 5, oy - 3, 10, 10, 5, 70);
		Glass.circle(g, ox + of - 5, oy - 3, 10, WHITE);
		Glass.roundOutline(g, ox + of - 5, oy - 3, 10, 10, 5, 0x40000000);

		// Song display: off or one of the three styles.
		int active = spotify.config.hudEnabled ? spotify.config.hudStyle + 1 : 0;
		Glass.glass(g, dx + 12, dy + 126, DRAWER_W - 24, 18, 9, 0x50FFFFFF, 0x30FFFFFF);
		for (int i = 0; i < HUD_OPTIONS.length; i++) {
			int segX = segmentX(dx, i), segW = segmentW();
			boolean hov = hover(mx, my, segX, dy + 127, segW, 16);
			if (i == active) Glass.roundRect(g, segX, dy + 127, segW, 16, 8, PURPLE_TOP, PURPLE_BOTTOM);
			else if (hov) Glass.roundRect(g, segX, dy + 127, segW, 16, 8, 0x40FFFFFF, 0x20FFFFFF);
			centered(g, HUD_OPTIONS[i], segX + segW / 2, dy + 131, i == active ? WHITE : TEXT_SOFT);
		}

		boolean hm = hover(mx, my, dx + 12, dy + 148, DRAWER_W - 24, 20);
		Glass.pill(g, dx + 12, dy + 148, DRAWER_W - 24, 20, TEAL_TOP, TEAL_BOTTOM, hm);
		centered(g, "Anzeige verschieben", dx + DRAWER_W / 2, dy + 154, WHITE);

		g.fill(dx + 12, dy + 174, dx + DRAWER_W - 12, dy + 175, Glass.argb(0x30, TEXT_SOFT));
		// Opens its own page with a skin preview (see NametagScreen).
		boolean hn = hover(mx, my, dx + 12, dy + 181, DRAWER_W - 24, 20);
		Glass.pill(g, dx + 12, dy + 181, DRAWER_W - 24, 20, PURPLE_TOP, PURPLE_BOTTOM, hn);
		centered(g, "Song über dem Namen", dx + DRAWER_W / 2 - 4, dy + 187, WHITE);
		Glass.chevronIcon(g, dx + DRAWER_W - 24, dy + 191, WHITE);

		// Seasonal theme: off, Halloween, Christmas, winter, Easter.
		g.drawString(font, "Saison", dx + 12, dy + 213, TEXT, false);
		for (int i = 0; i < Season.NAMES.length; i++) {
			int sx0 = seasonX(dx, i), sy0 = dy + 208;
			boolean sel = i == spotify.config.season, hov = hover(mx, my, sx0, sy0, 18, 18);
			int[] c = Season.iconColors(i);
			Glass.shadow(g, sx0, sy0, 18, 18, 9, hov ? 110 : 50);
			Glass.roundRect(g, sx0, sy0, 18, 18, 9, c[0], c[1]);
			Season.icon(g, i, sx0, sy0);
			Glass.roundOutline(g, sx0, sy0, 18, 18, 9, 0xB0FFFFFF);
			if (sel) Glass.roundOutline(g, sx0 - 2, sy0 - 2, 22, 22, 11, TEXT);
		}
		g.disableScissor();
		g.nextStratum();
	}

	private static final String[] HUD_OPTIONS = {"Aus", "Karte", "Vinyl", "Welle"};

	private static int segmentW() { return (DRAWER_W - 26) / HUD_OPTIONS.length; }

	private static int segmentX(int dx, int i) { return dx + 13 + i * segmentW(); }

	private static int seasonX(int dx, int i) { return dx + DRAWER_W - 12 - (Season.NAMES.length - i) * 24 + 6; }

	private static int swatchX(int dx, int i) { return dx + 12 + (i % 7) * 26; }

	private static int swatchY(int dy, int i) { return dy + 24 + (i / 7) * 26; }

	private void toggleRow(GuiGraphics g, int dx, int y, String label, boolean on) {
		g.drawString(font, label, dx + 12, y + 3, TEXT, false);
		int tgx = dx + DRAWER_W - 40;
		Glass.roundRect(g, tgx, y, 28, 14, 7, on ? PURPLE_TOP : 0x60A09CB8, on ? PURPLE_BOTTOM : 0x60A09CB8);
		Glass.roundOutline(g, tgx, y, 28, 14, 7, 0x90FFFFFF);
		Glass.circle(g, on ? tgx + 15 : tgx + 1, y + 1, 12, WHITE);
	}

	/**
	 * After picking a color, a glass strip slides in at the top of the screen for a few seconds,
	 * showing the card, vinyl and wave styles in the new colors.
	 */
	private void renderThemePreview(GuiGraphics g) {
		long age = System.currentTimeMillis() - previewAt;
		if (previewAt == 0 || age > 4000) return;
		float in = Math.min(1f, age / 250f), out = Math.min(1f, (4000 - age) / 300f);
		float show = ease(Math.min(in, out));
		float sc = 0.55f;
		int[] styles = {SongHud.STYLE_CARD, SongHud.STYLE_VINYL, SongHud.STYLE_WAVE};
		int gap = 12, pad = 10, stripH = 58;
		int total = -gap;
		for (int st : styles) total += Math.round(SongHud.baseWidth(st) * sc) + gap;
		int stripW = total + pad * 2;
		int x = (width - stripW) / 2;
		int y = Math.round(-stripH + (stripH + 6) * show);

		g.nextStratum();
		Glass.glass(g, x, y, stripW, stripH, 14, Glass.argb(0xF2, PILL_WHITE_TOP), Glass.argb(0xEC, PILL_WHITE_BOTTOM));
		int cx = x + pad;
		for (int st : styles) {
			int w = Math.round(SongHud.baseWidth(st) * sc);
			int h = Math.round(SongHud.baseHeight(st) * sc);
			SongHud.render(g, spotify, cx, y + (stripH - h) / 2, st, sc);
			cx += w + gap;
		}
		g.nextStratum();
	}

	/** Move mode: only the song display is shown and can be dragged around. */
	private void renderMoveMode(GuiGraphics g, int mx, int my) {
		int hx = SongHud.x(spotify.config, width), hy = SongHud.y(spotify.config, height);
		SongHud.render(g, spotify, hx, hy);
		boolean hov = draggingHud || hover(mx, my, hx, hy, SongHud.width(spotify.config), SongHud.height(spotify.config));
		Glass.roundOutline(g, hx - 2, hy - 2, SongHud.width(spotify.config) + 4, SongHud.height(spotify.config) + 4, 14, hov ? PURPLE : Glass.argb(0xA0, PURPLE));

		g.nextStratum();
		renderToast(g, width / 2, 12, 260, "Ziehen zum Verschieben, Mausrad für die Größe");
		int bx = width / 2 - 40, by = height - 36;
		Glass.pill(g, bx, by, 80, 22, PURPLE_TOP, PURPLE_BOTTOM, hover(mx, my, bx, by, 80, 22));
		centered(g, "Fertig", width / 2, by + 7, WHITE);
	}

	private void exitMoveMode() {
		moveMode = false;
		draggingHud = false;
		spotify.config.save();
	}

	private int opacityAt(int mx) {
		int ox = px + 26, ow = DRAWER_W - 28;
		return Math.max(20, Math.min(100, 20 + Math.round((mx - ox) * 80f / ow)));
	}

	// ================================================================ karaoke

	private int lyricsTop() { return py + 50; }

	/** Tab switch below the title: players on my server / everyone with the mod. */
	private void renderListenTabs(GuiGraphics g, int mx, int my, int serverCount, int globalCount) {
		int x = lx + 12, w = lw - 24, y = py + 28, segW = (w - 2) / 2;
		Glass.glass(g, x, y, w, 18, 9, 0x50FFFFFF, 0x30FFFFFF);
		String[] labels = { "Server · " + serverCount, "Global · " + globalCount };
		for (int i = 0; i < 2; i++) {
			int sx0 = x + 1 + i * segW;
			boolean active = (i == 1) == spotify.config.listenGlobal;
			boolean hov = hover(mx, my, sx0, y + 1, segW, 16);
			if (active) Glass.roundRect(g, sx0, y + 1, segW, 16, 8, PURPLE_TOP, PURPLE_BOTTOM);
			else if (hov) Glass.roundRect(g, sx0, y + 1, segW, 16, 8, 0x40FFFFFF, 0x20FFFFFF);
			centered(g, labels[i], sx0 + segW / 2, y + 5, active ? WHITE : TEXT_SOFT);
		}
	}

	private boolean clickListenTabs(int mx, int my) {
		int x = lx + 12, w = lw - 24, y = py + 28, segW = (w - 2) / 2;
		for (int i = 0; i < 2; i++) {
			if (hover(mx, my, x + 1 + i * segW, y + 1, segW, 16)) {
				spotify.config.listenGlobal = i == 1;
				spotify.config.save();
				listenScroll = 0;
				return true;
			}
		}
		return false;
	}
	/** The listener list ends above the share toggle at the bottom of the panel. */
	private int lyricsBottom() { return py + H - 34; }

	private int shareToggleY() { return py + H - 26; }

	/** Small glass handle on the player's left edge that expands/collapses the karaoke panel. */
	private void renderLyricsTab(GuiGraphics g, int mx, int my) {
		int tx = px - 9, ty = py + H / 2 - 24;
		boolean hov = hover(mx, my, tx - 2, ty, 12, 48);
		Glass.shadow(g, tx, ty, 8, 48, 4, 60);
		Glass.roundRect(g, tx, ty, 8, 48, 4, hov ? PURPLE_TOP : PILL_WHITE_TOP, hov ? PURPLE_BOTTOM : PILL_WHITE_BOTTOM);
		Glass.roundOutline(g, tx, ty, 8, 48, 4, 0xA0FFFFFF);
		int c = hov ? WHITE : ICON;
		// Chevron points to where the panel will move.
		boolean open = spotify.config.lyricsOpen;
		for (int i = -3; i <= 3; i++) {
			int off = 3 - Math.abs(i);
			int x = open ? tx + 2 + off : tx + 5 - off;
			g.fill(x, ty + 24 + i, x + 1, ty + 25 + i, c);
		}
		// Tiny "mic" dots above and below.
		Glass.circle(g, tx + 2, ty + 8, 4, c);
		Glass.circle(g, tx + 2, ty + 36, 4, c);
	}

	private boolean searchVisible() {
		return spotify.config.searchOpen && searchAnim > 0.98f;
	}

	/** Glass handle on the player's right edge that expands/collapses the search panel. */
	private void renderSearchTab(GuiGraphics g, int mx, int my) {
		int tx = px + PW + 1, ty = py + H / 2 - 24;
		boolean hov = hover(mx, my, tx - 2, ty, 12, 48);
		Glass.shadow(g, tx, ty, 8, 48, 4, 60);
		Glass.roundRect(g, tx, ty, 8, 48, 4, hov ? PURPLE_TOP : PILL_WHITE_TOP, hov ? PURPLE_BOTTOM : PILL_WHITE_BOTTOM);
		Glass.roundOutline(g, tx, ty, 8, 48, 4, 0xA0FFFFFF);
		int c = hov ? WHITE : ICON;
		// Chevron points to where the panel will move.
		boolean open = spotify.config.searchOpen;
		for (int i = -3; i <= 3; i++) {
			int off = 3 - Math.abs(i);
			int x = open ? tx + 5 - off : tx + 2 + off;
			g.fill(x, ty + 24 + i, x + 1, ty + 25 + i, c);
		}
		// Tiny magnifier dots above and below.
		Glass.circle(g, tx + 2, ty + 8, 4, c);
		Glass.circle(g, tx + 2, ty + 36, 4, c);
	}

	private static final int LISTEN_ROW_H = 36, LISTEN_ROW_STEP = 40;

	/**
	 * "Reinhören": every player who is sharing a song, each in its own glass box with their head,
	 * name and song. Clicking a box opens that song in Spotify so you can listen along.
	 */
	private void renderLyrics(GuiGraphics g, int mx, int my) {
		if (lyricsAnim <= 0.001f) return;
		float e = ease(lyricsAnim);
		// Reveal from the player's edge outward.
		int revealLeft = lx + Math.round((1 - e) * lw);
		g.enableScissor(revealLeft - 8, py - 8, lx + lw + 8, py + H + 12);
		panel(g, lx, py, lw, H);
		g.drawString(font, "Reinhören", lx + 14, py + 12, TEXT, false);
		String jam = jamButtonLabel();
		if (jam != null) {
			int bw = font.width(jam) + 16, bx = lx + lw - 10 - bw;
			boolean hj = hover(mx, my, bx, py + 7, bw, 16);
			Glass.pill(g, bx, py + 7, bw, 16, PURPLE_TOP, PURPLE_BOTTOM, hj);
			centered(g, jam, bx + bw / 2, py + 11, WHITE);
		}
		List<SongTags.Listener> serverList = SongTags.listeners(false), globalList = SongTags.listeners(true);
		renderListenTabs(g, mx, my, serverList.size(), globalList.size());
		g.disableScissor();

		List<SongTags.Listener> list = spotify.config.listenGlobal ? globalList : serverList;
		g.enableScissor(Math.max(revealLeft, lx + 4), lyricsTop(), lx + lw - 4, lyricsBottom());
		int top = renderListenHeader(g, mx, my, lyricsTop()), bottom = lyricsBottom();
		g.disableScissor();
		int visible = Math.max(1, (bottom - top) / LISTEN_ROW_STEP);
		listenScroll = Math.max(0, Math.min(listenScroll, Math.max(0, list.size() - visible)));
		g.enableScissor(Math.max(revealLeft, lx + 4), top, lx + lw - 4, bottom);
		if (list.isEmpty()) {
			// Say why the list is empty, so problems can be told apart.
			String l1, l2;
			if (!SongTags.relay().connected()) { l1 = "Verbinde mit dem"; l2 = "Song-Relay…"; }
			else if (spotify.config.listenGlobal) { l1 = "Gerade hört niemand"; l2 = "sonst etwas mit der Mod"; }
			else if (!SongTags.sharing()) { l1 = "Du bist auf keinem Server"; l2 = "→ schau bei „Global“"; }
			else { l1 = "Auf deinem Server hört"; l2 = "gerade niemand etwas"; }
			centered(g, l1, lx + lw / 2, (top + bottom) / 2 - 10, TEXT_SOFT);
			centered(g, l2, lx + lw / 2, (top + bottom) / 2, TEXT_SOFT);
		}
		boolean pressedRecently = System.currentTimeMillis() - pressedAt < 700;
		for (int i = 0; i < visible + 1 && listenScroll + i < list.size(); i++) {
			SongTags.Listener l = list.get(listenScroll + i);
			int x = lx + 8, w = lw - 16, y = top + 2 + i * LISTEN_ROW_STEP;
			boolean hov = hover(mx, my, x, y, w, LISTEN_ROW_H);
			boolean pressed = pressedRecently && ("listen:" + l.uuid()).equals(pressedUri);
			Glass.glass(g, x, y, w, LISTEN_ROW_H, 10,
					pressed ? Glass.argb(0xC0, PURPLE_TOP) : hov ? 0x90FFFFFF : 0x58FFFFFF,
					pressed ? Glass.argb(0xA0, PURPLE_BOTTOM) : hov ? 0x70FFFFFF : 0x38FFFFFF);

			// Line 1: player head + name.
			PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(l.uuid());
			String name = SongTags.name(l.uuid());
			if (info != null) {
				PlayerFaceRenderer.draw(g, info.getSkin(), x + 6, y + 5, 12);
			} else {
				// Not in my tab list (other sub-server or server): first letter instead of the head.
				Glass.roundRect(g, x + 6, y + 5, 12, 12, 3, PURPLE_TOP, PURPLE_BOTTOM);
				centered(g, name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), x + 12, y + 7, WHITE);
			}
			g.drawString(font, clip(name, w - 40), x + 22, y + 7, TEXT, false);

			// Line 2: small cover + song.
			Identifier cover = l.image().isEmpty() ? null : CoverCache.get(l.image());
			if (cover != null) g.blit(RenderPipelines.GUI_TEXTURED, cover, x + 6, y + 20, 0, 0, 12, 12, 12, 12);
			else Glass.roundRect(g, x + 6, y + 20, 12, 12, 3, TEAL_TOP, TEAL_BOTTOM);
			String song = l.artist().isEmpty() ? l.title() : l.title() + " · " + l.artist();
			g.drawString(font, clip(song, w - 40), x + 22, y + 22, TEXT_SOFT, false);

			// Listen-along hint on hover.
			Glass.roundOutline(g, x + w - 18, y + 11, 14, 14, 7, hov ? PURPLE : PURPLE_TOP);
			Glass.playIcon(g, x + w - 10, y + 18, 6, hov ? PURPLE : ICON);
		}
		g.disableScissor();

		// Bottom: whether others can see (and listen along to) my song.
		g.enableScissor(Math.max(revealLeft, lx + 4), py, lx + lw - 4, py + H);
		g.fill(lx + 12, shareToggleY() - 5, lx + lw - 12, shareToggleY() - 4, Glass.argb(0x30, TEXT_SOFT));
		boolean on = spotify.config.listenShare;
		g.drawString(font, "Meinen Song zeigen", lx + 14, shareToggleY() + 3, TEXT, false);
		int tgx = lx + lw - 42;
		Glass.roundRect(g, tgx, shareToggleY(), 28, 14, 7, on ? PURPLE_TOP : 0x60A09CB8, on ? PURPLE_BOTTOM : 0x60A09CB8);
		Glass.roundOutline(g, tgx, shareToggleY(), 28, 14, 7, 0x90FFFFFF);
		Glass.circle(g, on ? tgx + 15 : tgx + 1, shareToggleY() + 1, 12, WHITE);
		g.disableScissor();
	}

	// ---------------------------------------------------------------- listen-along header

	private static final int HEADER_BOX = 22, HEADER_GAP = 4;

	/** Height of the status boxes above the list: listening along, invites, my listeners. */
	private int listenHeaderHeight() {
		int h = 0;
		if (ListenAlong.following() != null) h += HEADER_BOX + HEADER_GAP;
		h += ListenAlong.invites().size() * (HEADER_BOX + HEADER_GAP);
		if (ListenAlong.followerCount() > 0) h += 14;
		return h;
	}

	/** Draws the status boxes and returns where the list starts. */
	private int renderListenHeader(GuiGraphics g, int mx, int my, int y) {
		int x = lx + 8, w = lw - 16;
		java.util.UUID host = ListenAlong.following();
		if (host != null) {
			Glass.glass(g, x, y + 2, w, HEADER_BOX, 9, Glass.argb(0xC0, PURPLE_TOP), Glass.argb(0xA0, PURPLE_BOTTOM));
			g.drawString(font, clip("Du hörst bei " + ListenAlong.name(host) + " mit", w - 56), x + 8, y + 9, WHITE, false);
			boolean hov = hover(mx, my, x + w - 46, y + 5, 42, 16);
			Glass.roundRect(g, x + w - 46, y + 5, 42, 16, 8, hov ? WHITE : 0x60FFFFFF, hov ? WHITE : 0x40FFFFFF);
			centered(g, "Ende", x + w - 25, y + 9, hov ? PURPLE : WHITE);
			y += HEADER_BOX + HEADER_GAP;
		}
		for (java.util.UUID from : ListenAlong.invites()) {
			Glass.glass(g, x, y + 2, w, HEADER_BOX, 9, Glass.argb(0xC0, TEAL_TOP), Glass.argb(0xA0, TEAL_BOTTOM));
			g.drawString(font, clip(ListenAlong.name(from) + " lädt ein", w - 74), x + 8, y + 9, WHITE, false);
			boolean ha = hover(mx, my, x + w - 66, y + 5, 46, 16), hx = hover(mx, my, x + w - 18, y + 5, 16, 16);
			Glass.roundRect(g, x + w - 66, y + 5, 46, 16, 8, ha ? WHITE : 0x60FFFFFF, ha ? WHITE : 0x40FFFFFF);
			centered(g, "Mithören", x + w - 43, y + 9, ha ? TEXT : WHITE);
			centered(g, "✕", x + w - 10, y + 9, hx ? TEXT : WHITE);
			y += HEADER_BOX + HEADER_GAP;
		}
		int n = ListenAlong.followerCount();
		if (n > 0) {
			centered(g, n == 1 ? "1 Spieler hört bei dir mit" : n + " Spieler hören bei dir mit", lx + lw / 2, y + 3, PURPLE);
			y += 14;
		}
		return y;
	}

	/** Button next to the "Reinhören" title while I'm in a jam: leave it, or end it for everyone. */
	private String jamButtonLabel() {
		if (ListenAlong.following() != null) return "Jam verlassen";
		if (ListenAlong.followerCount() > 0) return "Jam beenden";
		return null;
	}

	private boolean clickJamButton(int mx, int my) {
		String jam = jamButtonLabel();
		if (jam == null) return false;
		int bw = font.width(jam) + 16, bx = lx + lw - 10 - bw;
		if (!hover(mx, my, bx, py + 7, bw, 16)) return false;
		if (ListenAlong.following() != null) ListenAlong.stop();
		else ListenAlong.endJam();
		return true;
	}

	private boolean clickListenHeader(int mx, int my, int y) {
		int x = lx + 8, w = lw - 16;
		java.util.UUID host = ListenAlong.following();
		if (host != null) {
			if (hover(mx, my, x + w - 46, y + 5, 42, 16)) { ListenAlong.stop(); return true; }
			y += HEADER_BOX + HEADER_GAP;
		}
		for (java.util.UUID from : List.copyOf(ListenAlong.invites())) {
			if (hover(mx, my, x + w - 66, y + 5, 46, 16)) { ListenAlong.accept(from); return true; }
			if (hover(mx, my, x + w - 18, y + 5, 16, 16)) { ListenAlong.decline(from); return true; }
			y += HEADER_BOX + HEADER_GAP;
		}
		return false;
	}

	/** Clicking a listener opens their song in Spotify; clicking the name opens their profile. */
	private boolean clickLyrics(int mx, int my) {
		if (lyricsAnim < 0.99f || !hover(mx, my, lx, py, lw, H)) return false;
		if (hover(mx, my, lx + 8, shareToggleY() - 3, lw - 16, 20)) {
			spotify.config.listenShare = !spotify.config.listenShare;
			spotify.config.save();
			return true;
		}
		if (clickJamButton(mx, my)) return true;
		if (clickListenTabs(mx, my)) return true;
		if (clickListenHeader(mx, my, lyricsTop())) return true;
		List<SongTags.Listener> list = SongTags.listeners(spotify.config.listenGlobal);
		int top = lyricsTop() + listenHeaderHeight();
		for (int i = 0; listenScroll + i < list.size(); i++) {
			int y = top + 2 + i * LISTEN_ROW_STEP;
			if (y > lyricsBottom()) break;
			if (hover(mx, my, lx + 8, y, lw - 16, 18)) {
				// Head / name: open the player's profile.
				minecraft.setScreen(new ProfileScreen(this, spotify, list.get(listenScroll + i).uuid()));
				return true;
			}
			if (hover(mx, my, lx + 8, y, lw - 16, LISTEN_ROW_H)) {
				SongTags.Listener l = list.get(listenScroll + i);
				pressedUri = "listen:" + l.uuid();
				pressedAt = System.currentTimeMillis();
				spotify.play(l.track());
				return true;
			}
		}
		return true; // swallow clicks on the panel
	}
	private void renderToast(GuiGraphics g, int cx, int y, int maxW, String msg) {
		if (msg == null || msg.isEmpty()) return;
		msg = clip(msg, maxW - 20);
		int tw = font.width(msg) + 20;
		int tx = cx - tw / 2;
		Glass.glass(g, tx, y, tw, 16, 8, Glass.argb(0xF0, PILL_WHITE_TOP), Glass.argb(0xE8, PILL_WHITE_BOTTOM));
		g.drawString(font, msg, tx + 10, y + 4, TEXT, false);
	}

	private void renderPlayer(GuiGraphics g, int mx, int my) {
		PlayerState s = spotify.state();
		int cx = px + PW / 2;

		// Header: settings gear, small title, close.
		boolean hg = hover(mx, my, px + 10, py + 7, 16, 16);
		int gearStep = Math.round(drawerAnim * 16) + (hg && drawerAnim == 0 ? 2 : 0);
		ShapeCache.gear(g, px + 11, py + 8, 14, gearStep, hg || drawerOpen ? PURPLE : TEXT_FAINT);
		g.drawString(font, "Liquid Player", px + 30, py + 12, TEXT_FAINT, false);
		boolean hc = hover(mx, my, px + PW - 28, py + 9, 16, 14);
		Glass.roundRect(g, px + PW - 27, py + 15, 12, 2, 1, hc ? PURPLE : TEXT_FAINT);
		// My profile: my own head, top right next to close.
		boolean hpr = hover(mx, my, px + PW - 50, py + 7, 16, 16);
		Glass.shadow(g, px + PW - 49, py + 8, 14, 14, 4, hpr ? 120 : 60);
		PlayerInfo me = minecraft.player == null || minecraft.getConnection() == null ? null
				: minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
		if (me != null) PlayerFaceRenderer.draw(g, me.getSkin(), px + PW - 49, py + 8, 14);
		else Glass.roundRect(g, px + PW - 49, py + 8, 14, 14, 4, PURPLE_TOP, PURPLE_BOTTOM);
		Glass.roundOutline(g, px + PW - 50, py + 7, 16, 16, 4, hpr ? PURPLE : 0xB0FFFFFF);

		// Big cover.
		int cs = 92, cxp = cx - cs / 2, cyp = py + 28;
		Glass.shadow(g, cxp, cyp, cs, cs, 12, 120);
		Glass.roundRect(g, cxp + 6, cyp + 6, cs - 12, cs, 12, Glass.argb(0x40, PURPLE));
		Identifier cover = CoverCache.get(s.imageUrl());
		if (cover != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, cover, cxp, cyp, 0, 0, cs, cs, cs, cs);
		} else {
			Glass.roundRect(g, cxp, cyp, cs, cs, 12, 0xFFFFFFFF, 0xFFEDE8FA);
			g.pose().pushMatrix();
			g.pose().translate(cx, cyp + cs / 2f);
			g.pose().scale(2.5f, 2.5f);
			Glass.noteIcon(g, 0, 0, PURPLE);
			g.pose().popMatrix();
		}
		Glass.roundOutline(g, cxp, cyp, cs, cs, 12, 0x80FFFFFF);

		// Big title + artist.
		float ts = 1.35f;
		String title = clip(s.title(), (int) ((PW - 24) / ts));
		g.pose().pushMatrix();
		g.pose().translate(cx - font.width(title) * ts / 2f, py + 128);
		g.pose().scale(ts, ts);
		g.drawString(font, title, 0, 0, TEXT, false);
		g.pose().popMatrix();
		centered(g, clip(s.artist(), PW - 24), cx, py + 144, TEXT_SOFT);

		// Progress.
		int barX = cx - 90, barW = 180, barY = py + 160;
		long prog = s.liveProgress();
		float frac = s.durationMs() > 0 ? Math.min(1, prog / (float) s.durationMs()) : 0;
		Glass.roundRect(g, barX, barY, barW, 4, 2, 0x60FFFFFF);
		Glass.roundOutline(g, barX, barY, barW, 4, 2, 0x50A09CB8);
		int fill = Math.round(barW * frac);
		if (fill > 0) Glass.roundRect(g, barX, barY, Math.max(4, fill), 4, 2, PURPLE_TOP, PURPLE_BOTTOM);
		if (hover(mx, my, barX, barY - 4, barW, 12) || s.durationMs() > 0) {
			Glass.circle(g, barX + fill - 4, barY - 2, 8, WHITE);
			Glass.roundOutline(g, barX + fill - 4, barY - 2, 8, 8, 4, 0x40000000);
		}
		g.drawString(font, time(prog), barX, barY + 9, TEXT_SOFT, false);
		String dur = time(s.durationMs());
		g.drawString(font, dur, barX + barW - font.width(dur), barY + 9, TEXT_SOFT, false);

		// Controls: shuffle · previous · play/pause · next · repeat.
		bubble(g, mx, my, cx - 96, py + 190, 20, s.shuffle(), "⇄");
		circleButton(g, mx, my, cx - 60, py + 186, 28);
		Glass.skipIcon(g, cx - 46, py + 200, 9, TEXT, false);

		boolean hp = hover(mx, my, cx - 18, py + 182, 36, 36);
		Glass.pill(g, cx - 18, py + 182, 36, 36, PURPLE_TOP, PURPLE_BOTTOM, hp);
		if (s.playing()) Glass.pauseIcon(g, cx, py + 200, 12, WHITE);
		else Glass.playIcon(g, cx + 1, py + 200, 12, WHITE);

		circleButton(g, mx, my, cx + 32, py + 186, 28);
		Glass.skipIcon(g, cx + 46, py + 200, 9, TEXT, true);
		bubble(g, mx, my, cx + 76, py + 190, 20, !"off".equals(s.repeat()), "track".equals(s.repeat()) ? "1" : "↻");

		// Source status (the song is read from the Spotify app on this PC): a dot next to the title.
		boolean found = s.uri() != null;
		Glass.circle(g, px + 30 + font.width("Liquid Player") + 4, py + 13, 4, found ? 0xFF3DDC84 : 0xFFE0A030);

		// Volume: speaker bubble (click = mute) and a slider like the progress bar.
		float vol = volumeShown();
		boolean known = vol >= 0;
		int vy = py + H - 28;
		volumeBubble(g, mx, my, cx - 96, vy, 20, known ? vol : 1f);
		int vx = cx - 68, vw = 136, vby = vy + 8;
		Glass.roundRect(g, vx, vby, vw, 4, 2, 0x60FFFFFF);
		Glass.roundOutline(g, vx, vby, vw, 4, 2, 0x50A09CB8);
		if (known) {
			int vf = Math.round(vw * vol);
			if (vf > 0) Glass.roundRect(g, vx, vby, Math.max(4, vf), 4, 2, PURPLE_TOP, PURPLE_BOTTOM);
			Glass.circle(g, vx + vf - 4, vby - 2, 8, WHITE);
			Glass.roundOutline(g, vx + vf - 4, vby - 2, 8, 8, 4, 0x40000000);
		}
		String pct = known ? Math.round(vol * 100) + "%" : "–";
		g.drawString(font, pct, cx + 96 - font.width(pct), vy + 6, TEXT_SOFT, false);
	}

	private void volumeBubble(GuiGraphics g, int mx, int my, int x, int y, int d, float level) {
		boolean hov = hover(mx, my, x, y, d, d);
		boolean muted = level <= 0.001f;
		float r = d / 2f;
		Glass.shadow(g, x, y, d, d, r, 40);
		if (muted) Glass.roundRect(g, x, y, d, d, r, Glass.argb(0xC0, PURPLE_TOP), Glass.argb(0xC0, PURPLE_BOTTOM));
		else Glass.roundRect(g, x, y, d, d, r, hov ? 0x80FFFFFF : 0x40FFFFFF, hov ? 0x60FFFFFF : 0x20FFFFFF);
		Glass.roundRect(g, x + 4, y + 2, d - 8, d / 3, r - 3, 0x70FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, x, y, d, d, r, 0xB0FFFFFF);
		Glass.speakerIcon(g, x + d / 2 - 1, y + d / 2, level, muted ? WHITE : ICON);
	}

	// ---------------------------------------------------------------- volume

	/** Volume while dragging (my value), shortly after (until Spotify reports it), else Spotify's. */
	private float volumeShown() {
		if (draggingVolume || System.currentTimeMillis() - volumeSetAt < 1500) return volumeLocal;
		return spotify.volume();
	}

	private void setVolume(float v, boolean force) {
		volumeLocal = Math.max(0, Math.min(1, v));
		volumeSetAt = System.currentTimeMillis();
		if (force || volumeSetAt - volumeSentAt > 60) {
			volumeSentAt = volumeSetAt;
			spotify.setVolume(volumeLocal);
		}
	}

	private float volumeAt(int mx) {
		return (mx - (px + PW / 2 - 68)) / 136f;
	}

	private void renderSearch(GuiGraphics g, int mx, int my) {
		g.drawString(font, "Songsuche", sx + 12, py + 12, TEXT, false);

		// Search field.
		Glass.shadow(g, sx + 10, py + 30, 150, 20, 10, 60);
		Glass.roundRect(g, sx + 10, py + 30, 150, 20, 10, PILL_WHITE_TOP, PILL_WHITE_BOTTOM);
		Glass.roundOutline(g, sx + 10, py + 30, 150, 20, 10, searchBox.isFocused() ? PURPLE_TOP : 0xC0FFFFFF);
		Glass.searchIcon(g, sx + 147, py + 39, spotify.busy() ? PURPLE : ICON);

		// Results.
		PlayerState s = spotify.state();
		List<Track> list = spotify.results();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - VISIBLE_ROWS)));
		int listY = py + ROW_Y;
		if (list.isEmpty()) {
			centered(g, spotify.busy() ? "Suche läuft…" : "Tippe oben einen Song ein", sx + SW / 2, listY + 10, TEXT_SOFT);
		}
		centered(g, "Klick öffnet den Song in Spotify", sx + SW / 2, py + H - 16, TEXT_FAINT);
		boolean pressedRecently = System.currentTimeMillis() - pressedAt < 700;
		for (int i = 0; i < VISIBLE_ROWS && scroll + i < list.size(); i++) {
			Track t = list.get(scroll + i);
			int y = listY + i * ROW_STEP;
			boolean hov = hover(mx, my, sx + 8, y, 150, ROW_H);
			boolean current = t.title().equalsIgnoreCase(s.title());
			boolean pressed = pressedRecently && t.uri().equals(pressedUri);
			int top = pressed ? 0xC0E9DFFF : hov ? 0x90FFFFFF : 0x58FFFFFF;
			int bottom = pressed ? 0xA0D9CCFF : hov ? 0x70FFFFFF : 0x38FFFFFF;
			Glass.glass(g, sx + 8, y, 150, ROW_H, 8, top, bottom);

			Identifier cover = CoverCache.get(t.imageUrl());
			if (cover != null) {
				g.blit(RenderPipelines.GUI_TEXTURED, cover, sx + 12, y + 3, 0, 0, 18, 18, 18, 18);
			} else {
				Glass.roundRect(g, sx + 12, y + 3, 18, 18, 4, 0xFFFFFFFF, 0xFFEDE8FA);
				Glass.noteIcon(g, sx + 21, y + 12, PURPLE);
			}
			g.drawString(font, clip(t.title(), 100), sx + 35, y + 3, TEXT, false);
			g.drawString(font, clip(t.subtitle(), 100), sx + 35, y + 13, TEXT_SOFT, false);

			if (current) {
				Glass.roundRect(g, sx + 140, y + 6, 12, 12, 6, PURPLE_TOP, PURPLE_BOTTOM);
				Glass.checkIcon(g, sx + 146, y + 11, WHITE);
			} else {
				Glass.roundOutline(g, sx + 140, y + 6, 12, 12, 6, hov ? PURPLE : PURPLE_TOP);
				Glass.chevronIcon(g, sx + 146, y + 12, hov ? PURPLE : ICON);
			}
		}
		if (list.size() > VISIBLE_ROWS) {
			int trackH = VISIBLE_ROWS * ROW_STEP - 3;
			int thumbH = Math.max(10, trackH * VISIBLE_ROWS / list.size());
			int thumbY = listY + (trackH - thumbH) * scroll / Math.max(1, list.size() - VISIBLE_ROWS);
			Glass.roundRect(g, sx + 161, thumbY, 3, thumbH, 1.5f, 0x807C5CF2);
		}
	}

	private void circleButton(GuiGraphics g, int mx, int my, int x, int y, int d) {
		boolean hov = hover(mx, my, x, y, d, d);
		Glass.shadow(g, x, y, d, d, d / 2f, 80);
		Glass.roundRect(g, x, y, d, d, d / 2f, hov ? WHITE : PILL_WHITE_TOP, PILL_WHITE_BOTTOM);
		Glass.roundOutline(g, x, y, d, d, d / 2f, 0xA0FFFFFF);
	}

	private void bubble(GuiGraphics g, int mx, int my, int x, int y, int d, boolean active, String glyph) {
		boolean hov = hover(mx, my, x, y, d, d);
		float r = d / 2f;
		Glass.shadow(g, x, y, d, d, r, 40);
		if (active) Glass.roundRect(g, x, y, d, d, r, Glass.argb(0xC0, PURPLE_TOP), Glass.argb(0xC0, PURPLE_BOTTOM));
		else Glass.roundRect(g, x, y, d, d, r, hov ? 0x80FFFFFF : 0x40FFFFFF, hov ? 0x60FFFFFF : 0x20FFFFFF);
		Glass.roundRect(g, x + 4, y + 2, d - 8, d / 3, r - 3, 0x70FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, x, y, d, d, r, 0xB0FFFFFF);
		centered(g, glyph, x + d / 2, y + (d - 8) / 2, active ? WHITE : ICON);
	}

	// ================================================================ input

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (moveMode) {
			int mx = (int) e.x(), my = (int) e.y();
			if (hover(mx, my, width / 2 - 40, height - 36, 80, 22)) {
				exitMoveMode();
				return true;
			}
			int hx = SongHud.x(spotify.config, width), hy = SongHud.y(spotify.config, height);
			if (hover(mx, my, hx, hy, SongHud.width(spotify.config), SongHud.height(spotify.config))) {
				draggingHud = true;
				hudGrabX = mx - hx;
				hudGrabY = my - hy;
			}
			return true;
		}
		if (super.mouseClicked(e, doubleClick)) return true;
		if (e.button() != 0) return false;
		int mx = (int) e.x(), my = (int) e.y();

		// Settings gear and drawer.
		if (hover(mx, my, px + 10, py + 7, 16, 16)) {
			drawerOpen = !drawerOpen;
			return true;
		}
		if (drawerOpen) {
			int dx = px + 12, dy = py + 26;
			if (hover(mx, my, dx, dy, DRAWER_W, DRAWER_H)) {
				for (int i = 0; i < Theme.ALL.length; i++) {
					if (hover(mx, my, swatchX(dx, i), swatchY(dy, i), 20, 20)) {
						spotify.config.theme = i;
						spotify.config.save();
						ShapeCache.clear();
						previewAt = System.currentTimeMillis();
					}
				}
				if (hover(mx, my, swatchX(dx, Theme.CUSTOM), swatchY(dy, Theme.CUSTOM), 20, 20)) {
					spotify.config.theme = Theme.CUSTOM;
					spotify.config.save();
					drawerOpen = false;
					drawerAnim = 0;
					minecraft.setScreen(new ColorScreen(this, spotify));
					return true;
				}
				if (hover(mx, my, dx + 8, dy + 75, DRAWER_W - 16, 20)) {
					spotify.config.blackMode = !spotify.config.blackMode;
					spotify.config.save();
					ShapeCache.clear();
					previewAt = System.currentTimeMillis();
				}
				if (hover(mx, my, dx + 8, dy + 108, DRAWER_W - 16, 18)) {
					draggingOpacity = true;
					spotify.config.opacity = opacityAt(mx);
				}
				for (int i = 0; i < HUD_OPTIONS.length; i++) {
					if (hover(mx, my, segmentX(dx, i), dy + 127, segmentW(), 16)) {
						spotify.config.hudEnabled = i > 0;
						if (i > 0) spotify.config.hudStyle = i - 1;
						spotify.config.save();
					}
				}
				for (int i = 0; i < Season.NAMES.length; i++) {
					if (hover(mx, my, seasonX(dx, i), dy + 208, 18, 18)) {
						spotify.config.season = i;
						spotify.config.save();
						ShapeCache.clear();
						previewAt = System.currentTimeMillis();
						return true;
					}
				}
				if (hover(mx, my, dx + 12, dy + 181, DRAWER_W - 24, 20)) {
					drawerOpen = false;
					drawerAnim = 0;
					minecraft.setScreen(new NametagScreen(this, spotify));
					return true;
				}
				if (hover(mx, my, dx + 12, dy + 148, DRAWER_W - 24, 20)) {
					spotify.config.hudEnabled = true;
					drawerOpen = false;
					drawerAnim = 0;
					moveMode = true;
					setFocused(null);
				}
				return true;
			}
			// Click outside closes the drawer.
			drawerOpen = false;
			return true;
		}

		// Karaoke handle and panel.
		if (hover(mx, my, px - 11, py + H / 2 - 24, 12, 48)) {
			spotify.config.lyricsOpen = !spotify.config.lyricsOpen;
			spotify.config.save();
			return true;
		}
		if (spotify.config.lyricsOpen && clickLyrics(mx, my)) return true;

		PlayerState s = spotify.state();
		int cx = px + PW / 2;

		// Player panel.
		if (hover(mx, my, px + PW - 28, py + 9, 16, 14)) { onClose(); return true; }
		if (hover(mx, my, px + PW - 50, py + 7, 16, 16) && minecraft.player != null) {
			minecraft.setScreen(new ProfileScreen(this, spotify, minecraft.player.getUUID()));
			return true;
		}
		if (hover(mx, my, cx - 18, py + 182, 36, 36)) { spotify.togglePlay(); return true; }
		if (hover(mx, my, cx - 60, py + 186, 28, 28)) { spotify.previous(); return true; }
		if (hover(mx, my, cx + 32, py + 186, 28, 28)) { spotify.next(); return true; }
		if (hover(mx, my, cx - 96, py + 190, 20, 20)) { spotify.toggleShuffle(); return true; }
		if (hover(mx, my, cx + 76, py + 190, 20, 20)) { spotify.cycleRepeat(); return true; }
		if (hover(mx, my, cx - 96, py + H - 28, 20, 20) && spotify.volume() >= 0) {
			// Mute / unmute (back to the volume before muting).
			float v = volumeShown();
			if (v > 0.001f) {
				volumeBeforeMute = v;
				setVolume(0, true);
			} else {
				setVolume(volumeBeforeMute > 0.02f ? volumeBeforeMute : 0.5f, true);
			}
			return true;
		}
		if (hover(mx, my, cx - 72, py + H - 26, 144, 16) && spotify.volume() >= 0) {
			draggingVolume = true;
			setVolume(volumeAt(mx), true);
			return true;
		}
		if (hover(mx, my, cx - 90, py + 156, 180, 12) && s.durationMs() > 0) {
			float f = (mx - (cx - 90)) / 180f;
			spotify.seek(Math.round(Math.max(0, Math.min(1, f)) * s.durationMs()));
			return true;
		}

		// Search handle and panel.
		if (hover(mx, my, px + PW - 1, py + H / 2 - 24, 12, 48)) {
			spotify.config.searchOpen = !spotify.config.searchOpen;
			spotify.config.save();
			if (!spotify.config.searchOpen) setFocused(null);
			return true;
		}
		if (!searchVisible()) return false;
		if (hover(mx, my, sx + 136, py + 30, 24, 20)) { doSearch(); return true; }
		if (hover(mx, my, sx + 10, py + 30, 150, 20)) { setFocused(searchBox); return true; }
		List<Track> list = spotify.results();
		for (int i = 0; i < VISIBLE_ROWS && scroll + i < list.size(); i++) {
			if (hover(mx, my, sx + 8, py + ROW_Y + i * ROW_STEP, 150, ROW_H)) {
				Track t = list.get(scroll + i);
				pressedUri = t.uri();
				pressedAt = System.currentTimeMillis();
				setFocused(null);
				spotify.play(t);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (draggingHud) {
			int freeW = Math.max(1, width - SongHud.width(spotify.config)), freeH = Math.max(1, height - SongHud.height(spotify.config));
			spotify.config.hudX = Math.max(0, Math.min(1, ((float) e.x() - hudGrabX) / freeW));
			spotify.config.hudY = Math.max(0, Math.min(1, ((float) e.y() - hudGrabY) / freeH));
			return true;
		}
		if (draggingOpacity) {
			spotify.config.opacity = opacityAt((int) e.x());
			return true;
		}
		if (draggingVolume) {
			setVolume(volumeAt((int) e.x()), false);
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (draggingHud) {
			draggingHud = false;
			return true;
		}
		if (draggingOpacity) {
			draggingOpacity = false;
			spotify.config.save();
			return true;
		}
		if (draggingVolume) {
			draggingVolume = false;
			setVolume(volumeAt((int) e.x()), true);
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sxAmount, double syAmount) {
		if (moveMode) {
			// Mouse wheel resizes the display while positioning it.
			float next = spotify.config.hudScale + (float) Math.signum(syAmount) * 0.05f;
			spotify.config.hudScale = Math.round(Math.max(SongHud.MIN_SCALE, Math.min(SongHud.MAX_SCALE, next)) * 20) / 20f;
			return true;
		}
		if (spotify.config.lyricsOpen && hover((int) mx, (int) my, lx, py, lw, H)) {
			listenScroll -= (int) Math.signum(syAmount);
			return true;
		}
		if (searchVisible() && hover((int) mx, (int) my, sx, py + ROW_Y, SW, VISIBLE_ROWS * ROW_STEP)) {
			scroll -= (int) Math.signum(syAmount);
			return true;
		}
		return super.mouseScrolled(mx, my, sxAmount, syAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (moveMode) {
			if (e.isEscape() || e.isConfirmation()) exitMoveMode();
			return true;
		}
		if (searchBox != null && searchBox.isFocused() && e.isConfirmation()) {
			doSearch();
			return true;
		}
		// Space toggles playback when no text field is focused.
		if (e.key() == 32 && (searchBox == null || !searchBox.isFocused())) {
			spotify.togglePlay();
			return true;
		}
		return super.keyPressed(e);
	}

	private void doSearch() {
		if (searchBox == null || searchBox.getValue().isBlank()) return;
		scroll = 0;
		spotify.search(searchBox.getValue());
	}

	// ================================================================ helpers

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

	private static String time(long ms) {
		long s = ms / 1000;
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}
}
