package dev.spotifyglass.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.spotifyglass.CoverCache;
import dev.spotifyglass.SongTags;
import dev.spotifyglass.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composites the whole song card (glass, cover, text) into one texture. Drawing it as a single
 * quad avoids any depth/order fights between separately rendered layers in the world.
 */
public final class CardBaker {
	/** Card texture and its size in world "name tag pixels". */
	public record Card(Identifier texture, int width, int height) {}

	private static final int S = 4;                       // texture pixels per card unit
	private static final int H = 28, PAD = 4, COVER = 20, TEXT_MAX = 112;
	private static final int MAX_CARDS = 32;

	private static final Map<String, Card> CACHE = new LinkedHashMap<>(16, 0.75f, true);
	private static int counter;

	private CardBaker() {}

	public static Card get(SongTags.Label label, Theme theme) {
		BufferedImage cover = CoverCache.image(label.image());
		String key = label.title() + "\u0000" + label.artist() + "\u0000" + label.image() + "\u0000"
				+ theme.name() + "\u0000" + (cover != null);
		Card card = CACHE.get(key);
		if (card != null) return card;
		card = bake(label, theme, cover);
		CACHE.put(key, card);
		if (CACHE.size() > MAX_CARDS) {
			var it = CACHE.entrySet().iterator();
			Card old = it.next().getValue();
			it.remove();
			Minecraft.getInstance().getTextureManager().release(old.texture());
		}
		return card;
	}

	private static Card bake(SongTags.Label label, Theme theme, BufferedImage cover) {
		Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 9 * S);
		Font artistFont = new Font(Font.SANS_SERIF, Font.PLAIN, 8 * S);

		// Measure with a scratch graphics context.
		BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = scratch.createGraphics();
		sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		FontMetrics tm = sg.getFontMetrics(titleFont), am = sg.getFontMetrics(artistFont);
		String title = clip(label.title(), tm), artist = clip(label.artist(), am);
		int textW = (int) Math.ceil(Math.max(tm.stringWidth(title), am.stringWidth(artist)) / (float) S);
		sg.dispose();

		int w = PAD + COVER + 6 + textW + 8;
		int pw = w * S, ph = H * S;
		BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

		// Glass body.
		float inset = S * 0.6f;
		RoundRectangle2D body = new RoundRectangle2D.Float(inset, inset, pw - 2 * inset, ph - 2 * inset, 16 * S, 16 * S);
		g.setPaint(new GradientPaint(0, 0, color(theme.panelTop(), 0xF0), 0, ph, color(theme.panelBottom(), 0xE4)));
		g.fill(body);
		g.setClip(body);
		g.setPaint(new RadialGradientPaint(pw * 0.88f, ph * 1.0f, ph * 1.3f, new float[]{0f, 1f},
				new Color[]{color(theme.blobA(), 0x70), color(theme.blobA(), 0)}));
		g.fill(body);
		g.setPaint(new RadialGradientPaint(pw * 0.35f, 0, ph, new float[]{0f, 1f},
				new Color[]{color(theme.blobB(), 0x40), color(theme.blobB(), 0)}));
		g.fill(body);
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 90), 0, ph * 0.5f, new Color(255, 255, 255, 0)));
		g.fill(body);
		g.setClip(null);
		g.setStroke(new BasicStroke(S * 0.9f));
		g.setColor(new Color(255, 255, 255, 215));
		g.draw(body);

		// Cover.
		int cx = PAD * S, cy = (H - COVER) / 2 * S, cs = COVER * S;
		RoundRectangle2D coverShape = new RoundRectangle2D.Float(cx, cy, cs, cs, 7 * S, 7 * S);
		g.setColor(new Color(0, 0, 0, 40));
		g.fill(new RoundRectangle2D.Float(cx + S * 0.6f, cy + S * 1.2f, cs, cs, 7 * S, 7 * S));
		g.setClip(coverShape);
		if (cover != null) {
			g.drawImage(cover, cx, cy, cs, cs, null);
		} else {
			g.setPaint(new GradientPaint(0, cy, color(theme.accentTop(), 0xFF), 0, cy + cs, color(theme.accentBottom(), 0xFF)));
			g.fill(coverShape);
			g.setColor(Color.WHITE);
			Font noteFont = new Font(Font.SANS_SERIF, Font.BOLD, 12 * S);
			FontMetrics nm = g.getFontMetrics(noteFont);
			g.setFont(noteFont);
			g.drawString("♫", cx + (cs - nm.stringWidth("♫")) / 2f, cy + (cs + nm.getAscent() - nm.getDescent()) / 2f);
		}
		g.setClip(null);
		g.setComposite(AlphaComposite.SrcOver);
		g.setStroke(new BasicStroke(S * 0.5f));
		g.setColor(new Color(255, 255, 255, 120));
		g.draw(coverShape);

		// Text.
		float tx = (PAD + COVER + 6) * S;
		g.setFont(titleFont);
		g.setColor(color(theme.text(), 0xFF));
		g.drawString(title, tx, 12.5f * S);
		g.setFont(artistFont);
		g.setColor(color(theme.textSoft(), 0xFF));
		g.drawString(artist, tx, 22f * S);
		g.dispose();

		NativeImage nat = new NativeImage(pw, ph, false);
		for (int y = 0; y < ph; y++) {
			for (int x = 0; x < pw; x++) nat.setPixel(x, y, img.getRGB(x, y));
		}
		Identifier id = Identifier.fromNamespaceAndPath("spotifyglass", "card/" + (counter++));
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, nat));
		return new Card(id, w, H);
	}

	private static Color color(int rgb, int alpha) {
		return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha);
	}

	private static String clip(String s, FontMetrics fm) {
		if (s == null) return "";
		int max = TEXT_MAX * S;
		if (fm.stringWidth(s) <= max) return s;
		while (!s.isEmpty() && fm.stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
		return s + "…";
	}
}
