package dev.spotifyglass.mixin;

import dev.spotifyglass.SongTags;
import dev.spotifyglass.render.SongTagHolder;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements SongTagHolder {
	@Unique
	private SongTags.Label liquidplayer$song;
	@Unique
	private Vec3 liquidplayer$songAttachment;

	@Override
	public SongTags.Label liquidplayer$song() {
		return liquidplayer$song;
	}

	@Override
	public Vec3 liquidplayer$songAttachment() {
		return liquidplayer$songAttachment;
	}

	@Override
	public void liquidplayer$setSong(SongTags.Label song, Vec3 attachment) {
		liquidplayer$song = song;
		liquidplayer$songAttachment = attachment;
	}
}
