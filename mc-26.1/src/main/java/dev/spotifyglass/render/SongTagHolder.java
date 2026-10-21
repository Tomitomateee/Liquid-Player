package dev.spotifyglass.render;

import dev.spotifyglass.SongTags;
import net.minecraft.world.phys.Vec3;

/** Extra fields added to AvatarRenderState for the song card above a player's name. */
public interface SongTagHolder {
	SongTags.Label liquidplayer$song();

	Vec3 liquidplayer$songAttachment();

	void liquidplayer$setSong(SongTags.Label song, Vec3 attachment);
}
