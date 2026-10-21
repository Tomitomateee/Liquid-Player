package dev.spotifyglass.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.spotifyglass.SongTags;
import dev.spotifyglass.SpotifyGlassClient;
import dev.spotifyglass.gui.Theme;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** Draws the liquid glass song card (one pre-composited texture) above a player's name tag. */
public final class SongCardRenderer {
	private SongCardRenderer() {}

	/**
	 * @param raise how far (in world units) to lift the card, e.g. above name and score lines
	 * @param earsOffset vanilla's extra offset for deadmau5 ears
	 */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
	                          SongTags.Label label, Vec3 attachment, float raise, int earsOffset) {
		Theme theme = Theme.current(SpotifyGlassClient.spotify().config);
		CardBaker.Card card = CardBaker.get(label, theme);
		float scale = Math.max(0.5f, Math.min(2f, SpotifyGlassClient.spotify().config.nametagScale));
		float w = card.width() * scale, h = card.height() * scale;
		float x = -w / 2f, y = earsOffset - h - 3;

		poseStack.pushPose();
		poseStack.translate(attachment.x, attachment.y + 0.5 + raise, attachment.z);
		poseStack.mulPose(camera.orientation);
		poseStack.scale(0.025F, -0.025F, 0.025F);
		int light = LightTexture.FULL_BRIGHT;
		// Unshaded (like name tags) so the card is never darkened by world lighting.
		collector.submitCustomGeometry(poseStack, RenderTypes.text(card.texture()), (pose, vc) -> {
			vc.addVertex(pose, x, y, 0).setColor(-1).setUv(0, 0).setLight(light);
			vc.addVertex(pose, x, y + h, 0).setColor(-1).setUv(0, 1).setLight(light);
			vc.addVertex(pose, x + w, y + h, 0).setColor(-1).setUv(1, 1).setLight(light);
			vc.addVertex(pose, x + w, y, 0).setColor(-1).setUv(1, 0).setLight(light);
		});
		poseStack.popPose();
	}
}
