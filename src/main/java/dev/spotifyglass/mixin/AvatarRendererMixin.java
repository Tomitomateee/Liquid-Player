package dev.spotifyglass.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.spotifyglass.SongTags;
import dev.spotifyglass.render.SongCardRenderer;
import dev.spotifyglass.render.SongTagHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the liquid glass song card above a player's name tag. */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void liquidplayer$extractSong(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		SongTagHolder holder = (SongTagHolder) state;
		SongTags.Label label = null;
		Vec3 attachment = null;
		if (avatar instanceof Player player && state.distanceToCameraSq < 32 * 32 && !state.isInvisible) {
			label = SongTags.labelFor(player.getUUID());
			if (label != null) {
				attachment = state.nameTagAttachment != null ? state.nameTagAttachment
						: avatar.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, avatar.getYRot(partialTick));
			}
		}
		holder.liquidplayer$setSong(label, attachment);
	}

	@Inject(method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
			at = @At("TAIL"))
	private void liquidplayer$submitSong(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
	                                     CameraRenderState camera, CallbackInfo ci) {
		SongTagHolder holder = (SongTagHolder) state;
		SongTags.Label label = holder.liquidplayer$song();
		if (label == null || holder.liquidplayer$songAttachment() == null) return;
		// Never draw it in first person over our own view.
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.getCameraType().isFirstPerson() && mc.player != null && state.id == mc.player.getId()) return;

		int lines = (state.nameTag != null ? 1 : 0) + (state.scoreText != null ? 1 : 0);
		float raise = 9.0F * 1.15F * 0.025F * Math.max(0, lines - 1);
		SongCardRenderer.submit(poseStack, collector, camera, label, holder.liquidplayer$songAttachment(), raise,
				state.showExtraEars ? -10 : 0);
	}
}
