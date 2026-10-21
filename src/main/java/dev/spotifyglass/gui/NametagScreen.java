package dev.spotifyglass.gui;

import dev.spotifyglass.SongTags;
import dev.spotifyglass.SpotifyClient;
import dev.spotifyglass.SpotifyClient.PlayerState;
import dev.spotifyglass.SpotifyConfig;
import dev.spotifyglass.render.CardBaker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * Settings page for the song card above the player's name: shows your own skin with the card
 * over your head, and lets you change its size and the sharing options.
 */
public class NametagScreen extends Screen {
	private static final int W = 320, H = 220;
	private static final float MIN = 0.5f, MAX = 2f;
	/** Preview model size (GUI pixels per block). */
	private static final int MODEL = 46;

	private final Screen parent;
	private final SpotifyClient spotify;
	private int px, py;
	private boolean dragging;

	public NametagScreen(Screen parent, SpotifyClient spotify) {
		super(Component.literal("Song über dem Namen"));
		this.parent = parent;
		this.spotify = spotify;
	}

	@Override
	protected void init() {
		px = (width - W) / 2;
		py = (height - H) / 2;
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

	// ---------------------------------------------------------------- layout

	private int boxX() { return px + 14; }
	private int boxY() { return py + 70; }
	private int boxW() { return 140; }
	private int boxH() { return 136; }
	private int ctrlX() { return px + 170; }
	private int ctrlW() { return W - 184; }
	private int sliderY() { return py + 128; }

	// ---------------------------------------------------------------- rendering

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		SpotifyConfig cfg = spotify.config;
		Theme t = Theme.current(cfg);
		float alpha = Math.max(20, Math.min(100, cfg.opacity)) / 100f;

		// Panel.
		Glass.shadow(g, px, py, W, H, 16, 110);
		ShapeCache.roundRectFaded(g, px, py, W, H, 16, Glass.argb(0xFF, t.panelTop()), Glass.argb(0xD4, t.panelBottom()), alpha);
		g.enableScissor(px + 2, py + 2, px + W - 2, py + H - 2);
		Glass.blob(g, px + W - 30, py + H - 40, 60, Glass.argb(0x40, t.blobA()));
		Glass.blob(g, px + 20, py + H - 20, 40, Glass.argb(0x30, t.blobB()));
		g.disableScissor();
		Glass.roundRect(g, px + 3, py + 1, W - 6, 24, 15, 0x50FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, px, py, W, H, 16, 0xC0FFFFFF);
		g.drawString(font, "Song über dem Namen", px + 14, py + 12, t.text(), false);
		boolean hc = hover(mx, my, px + W - 28, py + 9, 16, 14);
		Glass.roundRect(g, px + W - 27, py + 15, 12, 2, 1, hc ? t.accentBottom() : t.textFaint());

		// Preview area: your skin with the card above your head.
		int bx = boxX(), by = boxY(), bw = boxW(), bh = boxH();
		Glass.glass(g, bx, py + 30, bw, bh + 40, 12, 0x40FFFFFF, 0x20FFFFFF);
		if (minecraft.player != null) {
			InventoryScreen.renderEntityInInventoryFollowsMouse(g, bx, by, bx + bw, by + bh, MODEL, 0.0625f, mx, my, minecraft.player);
			drawCardPreview(g, t, bx + bw / 2, by + bh / 2);
		} else {
			centered(g, "Nur in einer Welt verfügbar", bx + bw / 2, by + bh / 2, t.textSoft());
		}

		// Controls.
		int cx = ctrlX(), cw = ctrlW();
		toggle(g, t, cx, py + 40, cw, "Mein Song über Namen", cfg.nametagShare);
		toggle(g, t, cx, py + 62, cw, "Songs anderer zeigen", cfg.nametagOthers);

		g.drawString(font, "Größe", cx, py + 98, t.text(), false);
		String val = Math.round(scale() * 100) + "%";
		g.drawString(font, val, cx + cw - font.width(val), py + 98, t.textSoft(), false);
		// − [slider] +
		boolean hm = hover(mx, my, cx, sliderY() - 7, 16, 16), hp = hover(mx, my, cx + cw - 16, sliderY() - 7, 16, 16);
		Glass.circle(g, cx, sliderY() - 7, 16, hm ? t.accentTop() : t.pillTop());
		Glass.minusIcon(g, cx + 8, sliderY() + 1, 6, hm ? 0xFFFFFFFF : t.text());
		Glass.circle(g, cx + cw - 16, sliderY() - 7, 16, hp ? t.accentTop() : t.pillTop());
		Glass.plusIcon(g, cx + cw - 8, sliderY() + 1, 6, hp ? 0xFFFFFFFF : t.text());
		int sx = cx + 22, sw = cw - 44;
		Glass.roundRect(g, sx, sliderY(), sw, 4, 2, 0x50A09CB8);
		int fill = Math.round(sw * (scale() - MIN) / (MAX - MIN));
		Glass.roundRect(g, sx, sliderY(), Math.max(4, fill), 4, 2, t.accentTop(), t.accentBottom());
		Glass.shadow(g, sx + fill - 5, sliderY() - 3, 10, 10, 5, 70);
		Glass.circle(g, sx + fill - 5, sliderY() - 3, 10, 0xFFFFFFFF);
		Glass.roundOutline(g, sx + fill - 5, sliderY() - 3, 10, 10, 5, 0x40000000);

		g.drawString(font, "Tipp: Mausrad über der", cx, py + 150, t.textFaint(), false);
		g.drawString(font, "Vorschau ändert die Größe.", cx, py + 161, t.textFaint(), false);

		boolean hd = hover(mx, my, cx, py + H - 34, cw, 20);
		Glass.pill(g, cx, py + H - 34, cw, 20, t.accentTop(), t.accentBottom(), hd);
		centered(g, "Fertig", cx + cw / 2, py + H - 28, 0xFFFFFFFF);

		super.render(g, mx, my, pt);
	}

	/**
	 * Draws the card where it sits above your name in the world, at the configured size. The
	 * model is centered in the box; the name tag sits 2.3 blocks above the feet.
	 */
	private void drawCardPreview(GuiGraphics g, Theme t, int centerX, int centerY) {
		PlayerState s = spotify.state();
		SongTags.Label label = new SongTags.Label(
				s.uri() == null ? "Dein Song" : s.title(), s.uri() == null ? "Interpret" : s.artist(),
				s.imageUrl() == null ? "" : s.imageUrl());
		CardBaker.Card card = CardBaker.get(label, t);
		float perUnit = 0.025f * MODEL * scale(); // card units → GUI pixels
		float feetY = centerY + 0.9f * MODEL;
		float nameY = feetY - 2.3f * MODEL;
		float w = card.width() * perUnit, h = card.height() * perUnit;
		g.pose().pushMatrix();
		g.pose().translate(centerX - w / 2f, nameY - h - 3 * 0.025f * MODEL);
		g.pose().scale(perUnit, perUnit);
		g.blit(RenderPipelines.GUI_TEXTURED, card.texture(), 0, 0, 0, 0, card.width(), card.height(), card.width(), card.height());
		g.pose().popMatrix();
	}

	private void toggle(GuiGraphics g, Theme t, int x, int y, int w, String label, boolean on) {
		g.drawString(font, label, x, y + 3, t.text(), false);
		int tx = x + w - 28;
		Glass.roundRect(g, tx, y, 28, 14, 7, on ? t.accentTop() : 0x60A09CB8, on ? t.accentBottom() : 0x60A09CB8);
		Glass.roundOutline(g, tx, y, 28, 14, 7, 0x90FFFFFF);
		Glass.circle(g, on ? tx + 15 : tx + 1, y + 1, 12, 0xFFFFFFFF);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != 0) return super.mouseClicked(e, doubleClick);
		int mx = (int) e.x(), my = (int) e.y();
		SpotifyConfig cfg = spotify.config;
		int cx = ctrlX(), cw = ctrlW();
		if (hover(mx, my, px + W - 28, py + 9, 16, 14) || hover(mx, my, cx, py + H - 34, cw, 20)) {
			onClose();
			return true;
		}
		if (hover(mx, my, cx, py + 37, cw, 20)) { cfg.nametagShare = !cfg.nametagShare; cfg.save(); return true; }
		if (hover(mx, my, cx, py + 59, cw, 20)) { cfg.nametagOthers = !cfg.nametagOthers; cfg.save(); return true; }
		if (hover(mx, my, cx, sliderY() - 7, 16, 16)) { setScale(scale() - 0.05f); return true; }
		if (hover(mx, my, cx + cw - 16, sliderY() - 7, 16, 16)) { setScale(scale() + 0.05f); return true; }
		if (hover(mx, my, cx + 18, sliderY() - 6, cw - 36, 16)) {
			dragging = true;
			setScale(scaleAt(mx));
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			setScale(scaleAt((int) e.x()));
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging) {
			dragging = false;
			spotify.config.save();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (hover((int) mx, (int) my, boxX(), py + 30, boxW(), boxH() + 40)) {
			setScale(scale() + (float) Math.signum(sy) * 0.05f);
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	// ---------------------------------------------------------------- helpers

	private float scale() {
		return Math.max(MIN, Math.min(MAX, spotify.config.nametagScale));
	}

	private void setScale(float v) {
		spotify.config.nametagScale = Math.round(Math.max(MIN, Math.min(MAX, v)) * 20) / 20f;
	}

	private float scaleAt(int mx) {
		int sx = ctrlX() + 22, sw = ctrlW() - 44;
		float f = Math.max(0, Math.min(1, (mx - sx) / (float) sw));
		return MIN + f * (MAX - MIN);
	}

	private static boolean hover(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	private void centered(GuiGraphics g, String text, int cx, int y, int color) {
		g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
	}
}
