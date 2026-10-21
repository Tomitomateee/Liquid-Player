package dev.spotifyglass.gui;

import dev.spotifyglass.SpotifyClient;
import dev.spotifyglass.SpotifyConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * "Own colors" page: pick two colors on rainbow sliders (they are blended into every gradient)
 * and an intensity from pastel to vivid, with a live preview of card, vinyl and wave.
 */
public class ColorScreen extends Screen {
	private static final int W = 330, H = 226;
	private static final int SLIDER_W = 140;

	private final Screen parent;
	private final SpotifyClient spotify;
	private int px, py;
	/** Which slider is being dragged: 0 none, 1 color 1, 2 color 2, 3 intensity. */
	private int dragging;

	public ColorScreen(Screen parent, SpotifyClient spotify) {
		super(Component.literal("Eigene Farben"));
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

	private int ctrlX() { return px + W - SLIDER_W - 16; }
	private int sliderY(int i) { return py + 52 + (i - 1) * 40; }

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		SpotifyConfig cfg = spotify.config;
		Theme t = Theme.current(cfg);
		float alpha = Math.max(20, Math.min(100, cfg.opacity)) / 100f;

		// Panel in the colors being edited.
		Glass.shadow(g, px, py, W, H, 16, 110);
		ShapeCache.roundRectFaded(g, px, py, W, H, 16, Glass.argb(0xFF, t.panelTop()), Glass.argb(0xD4, t.panelBottom()), alpha);
		g.enableScissor(px + 2, py + 2, px + W - 2, py + H - 2);
		Glass.blob(g, px + W - 30, py + H - 40, 60, Glass.argb(0x40, t.blobA()));
		Glass.blob(g, px + 20, py + H - 20, 50, Glass.argb(0x38, t.blobB()));
		g.disableScissor();
		Glass.roundRect(g, px + 3, py + 1, W - 6, 24, 15, 0x50FFFFFF, 0x00FFFFFF);
		Glass.roundOutline(g, px, py, W, H, 16, 0xC0FFFFFF);
		g.drawString(font, "Eigene Farben", px + 14, py + 12, t.text(), false);
		boolean hc = hover(mx, my, px + W - 28, py + 9, 16, 14);
		Glass.roundRect(g, px + W - 27, py + 15, 12, 2, 1, hc ? t.accentBottom() : t.textFaint());

		// Live preview of the three song display styles.
		int lx = px + 14;
		Glass.glass(g, lx, py + 30, 156, H - 44, 12, 0x40FFFFFF, 0x20FFFFFF);
		SongHud.render(g, spotify, lx + 14, py + 38, SongHud.STYLE_CARD, 0.8f);
		SongHud.render(g, spotify, lx + 42, py + 78, SongHud.STYLE_VINYL, 0.55f);
		if (dragging == 0) {
			SongHud.render(g, spotify, lx + 20, py + 136, SongHud.STYLE_WAVE, 0.8f);
		} else {
			// The wave is rendered per color; wait until the slider is released.
			centered(g, "~ ~ ~", lx + 78, py + 150, t.textSoft());
		}

		// Sliders.
		int cx = ctrlX();
		float h1 = cfg.customHue1, h2 = cfg.customHue2, in = cfg.customIntensity;
		label(g, t, cx, sliderY(1) - 13, "Farbe 1", 0xFF000000 | Theme.hsv(h1, 0.85f, 1f));
		rainbow(g, cx, sliderY(1));
		knob(g, cx + Math.round(SLIDER_W * h1 / 360f), sliderY(1), 0xFF000000 | Theme.hsv(h1, 0.85f, 1f));

		label(g, t, cx, sliderY(2) - 13, "Farbe 2", 0xFF000000 | Theme.hsv(h2, 0.85f, 1f));
		rainbow(g, cx, sliderY(2));
		knob(g, cx + Math.round(SLIDER_W * h2 / 360f), sliderY(2), 0xFF000000 | Theme.hsv(h2, 0.85f, 1f));

		label(g, t, cx, sliderY(3) - 13, "Pastell  ↔  Kräftig", 0);
		for (int i = 0; i < SLIDER_W; i++) {
			float f = i / (float) (SLIDER_W - 1);
			int c = Glass.lerp(0xFF000000 | Theme.hsv(h1, 0.2f + 0.7f * f, 1f), 0xFF000000 | Theme.hsv(h2, 0.2f + 0.7f * f, 1f), 0.5f);
			g.fill(cx + i, sliderY(3), cx + i + 1, sliderY(3) + 8, c);
		}
		Glass.roundOutline(g, cx - 1, sliderY(3) - 1, SLIDER_W + 2, 10, 4, 0xC0FFFFFF);
		knob(g, cx + Math.round(SLIDER_W * in), sliderY(3), 0xFF000000 | Theme.hsv(h1, 0.2f + 0.7f * in, 1f));

		// The resulting gradient.
		Glass.pill(g, cx, py + 160, SLIDER_W, 16, t.accentTop(), t.accentBottom(), false);
		centered(g, "Deine Farben", cx + SLIDER_W / 2, py + 164, 0xFFFFFFFF);

		boolean hd = hover(mx, my, cx, py + H - 32, SLIDER_W, 20);
		Glass.pill(g, cx, py + H - 32, SLIDER_W, 20, t.accentTop(), t.accentBottom(), hd);
		centered(g, "Fertig", cx + SLIDER_W / 2, py + H - 26, 0xFFFFFFFF);

		super.render(g, mx, my, pt);
	}

	private void label(GuiGraphics g, Theme t, int x, int y, String text, int dot) {
		g.drawString(font, text, x, y, t.text(), false);
		if (dot != 0) {
			Glass.circle(g, x + SLIDER_W - 9, y - 1, 9, dot);
			Glass.roundOutline(g, x + SLIDER_W - 9, y - 1, 9, 9, 4.5f, 0xC0FFFFFF);
		}
	}

	/** Hue bar from red through all colors back to red. */
	private void rainbow(GuiGraphics g, int x, int y) {
		for (int i = 0; i < SLIDER_W; i++) {
			g.fill(x + i, y, x + i + 1, y + 8, 0xFF000000 | Theme.hsv(i * 360f / SLIDER_W, 0.8f, 1f));
		}
		Glass.roundOutline(g, x - 1, y - 1, SLIDER_W + 2, 10, 4, 0xC0FFFFFF);
	}

	private void knob(GuiGraphics g, int x, int y, int color) {
		Glass.shadow(g, x - 6, y - 2, 12, 12, 6, 90);
		Glass.circle(g, x - 6, y - 2, 12, 0xFFFFFFFF);
		Glass.circle(g, x - 4, y, 8, color);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != 0) return super.mouseClicked(e, doubleClick);
		int mx = (int) e.x(), my = (int) e.y();
		if (hover(mx, my, px + W - 28, py + 9, 16, 14) || hover(mx, my, ctrlX(), py + H - 32, SLIDER_W, 20)) {
			onClose();
			return true;
		}
		for (int i = 1; i <= 3; i++) {
			if (hover(mx, my, ctrlX() - 6, sliderY(i) - 5, SLIDER_W + 12, 18)) {
				dragging = i;
				apply(mx);
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging != 0) {
			apply((int) e.x());
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging != 0) {
			dragging = 0;
			spotify.config.save();
			return true;
		}
		return super.mouseReleased(e);
	}

	private void apply(int mx) {
		float f = Math.max(0, Math.min(1, (mx - ctrlX()) / (float) SLIDER_W));
		SpotifyConfig cfg = spotify.config;
		cfg.theme = Theme.CUSTOM;
		switch (dragging) {
			case 1 -> cfg.customHue1 = f * 360f;
			case 2 -> cfg.customHue2 = f * 360f;
			case 3 -> cfg.customIntensity = f;
			default -> { }
		}
	}

	private static boolean hover(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	private void centered(GuiGraphics g, String text, int cx, int y, int color) {
		g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
	}
}
