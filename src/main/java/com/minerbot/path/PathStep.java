package com.minerbot.path;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * One move along a path.
 *
 * @param feet     where the player's feet end up
 * @param toBreak  blocks that must be cleared before the move can be walked, in break order
 * @param needsJump whether the move steps up and therefore has to be jumped
 */
public record PathStep(BlockPos feet, List<BlockPos> toBreak, boolean needsJump) {
	public static PathStep walk(BlockPos feet) {
		return new PathStep(feet, List.of(), false);
	}
}
