package dev.spotifyglass;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads album covers (Spotify serves JPEGs, which NativeImage cannot decode) and turns them
 * into GUI textures. Covers are rounded off here so the GUI can blit them directly.
 */
public final class CoverCache {
	private static final int SIZE = 128;
	private static final int MAX_ENTRIES = 40;
	private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();
	private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "spotifyglass-covers");
		t.setDaemon(true);
		return t;
	});
	private static final Map<String, BufferedImage> IMAGES = new ConcurrentHashMap<>();
	private static int counter;

	private CoverCache() {}

	/** The decoded (square, unrounded) cover for compositing, or null while it is still loading. */
	public static BufferedImage image(String url) {
		if (url == null || url.isEmpty()) return null;
		BufferedImage img = IMAGES.get(url);
		if (img == null && !PENDING.containsKey(url)) get(url);
		return img;
	}

	/** Returns the texture for this URL, or null while it is still loading. */
	public static Identifier get(String url) {
		if (url == null || url.isEmpty()) return null;
		Identifier id = TEXTURES.get(url);
		if (id != null || PENDING.putIfAbsent(url, true) != null) return id;
		LOADER.execute(() -> load(url));
		return null;
	}

	private static void load(String url) {
		try (InputStream in = URI.create(url).toURL().openStream()) {
			BufferedImage src = ImageIO.read(in);
			if (src == null) return;
			BufferedImage scaled = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.drawImage(src, 0, 0, SIZE, SIZE, null);
			g.dispose();
			if (IMAGES.size() >= MAX_ENTRIES) IMAGES.clear();
			IMAGES.put(url, scaled);

			NativeImage image = new NativeImage(SIZE, SIZE, false);
			float r = SIZE * 0.18f;
			for (int y = 0; y < SIZE; y++) {
				for (int x = 0; x < SIZE; x++) {
					int argb = scaled.getRGB(x, y) | 0xFF000000;
					float alpha = cornerAlpha(x + 0.5f, y + 0.5f, r);
					int a = Math.round(alpha * 255);
					image.setPixel(x, y, (a << 24) | (argb & 0xFFFFFF));
				}
			}
			Minecraft.getInstance().execute(() -> {
				if (TEXTURES.size() >= MAX_ENTRIES) clear();
				Identifier id = Identifier.fromNamespaceAndPath("spotifyglass", "cover/" + (counter++));
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
				TEXTURES.put(url, id);
			});
		} catch (Exception e) {
			SpotifyGlassClient.LOGGER.debug("Cover konnte nicht geladen werden: {}", url, e);
		}
	}

	/** Anti-aliased rounded-corner mask. */
	private static float cornerAlpha(float x, float y, float r) {
		float cx = Math.max(r, Math.min(SIZE - r, x));
		float cy = Math.max(r, Math.min(SIZE - r, y));
		float d = (float) Math.hypot(x - cx, y - cy);
		return Math.max(0, Math.min(1, r - d + 0.5f));
	}

	private static void clear() {
		for (Identifier id : TEXTURES.values()) Minecraft.getInstance().getTextureManager().release(id);
		TEXTURES.clear();
		PENDING.clear();
	}
}
