package com.minerbot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block-level questions the pathfinder and the tasks keep asking about the world. */
public final class WorldHelper {
	/** How many blocks tall the player's collision box is, rounded up. */
	public static final int PLAYER_HEIGHT_BLOCKS = 2;

	private WorldHelper() {
	}

	/**
	 * Whether the player's body can occupy this block without colliding.
	 *
	 * <p>Fluids count as blocked. Swimming and floating are not modelled, so a route that dips
	 * through water would have the bot fighting the current instead of walking.
	 */
	public static boolean isPassable(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (isDangerous(state) || !state.getFluidState().isEmpty()) {
			return false;
		}

		return state.getCollisionShape(level, pos).isEmpty();
	}

	/** Whether the player can stand on top of the block at {@code pos}. */
	public static boolean isStandable(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (state.isAir() || isDangerous(state)) {
			return false;
		}

		VoxelShape shape = state.getCollisionShape(level, pos);

		if (shape.isEmpty()) {
			return false;
		}

		// The top face has to reach at least slab height, otherwise stepping up is not reliable.
		return shape.max(Direction.Axis.Y) >= 0.5;
	}

	/** Whether the player fits with their feet at {@code feet}, ignoring what is underneath. */
	public static boolean hasRoomFor(BlockGetter level, BlockPos feet) {
		for (int dy = 0; dy < PLAYER_HEIGHT_BLOCKS; dy++) {
			if (!isPassable(level, feet.above(dy))) {
				return false;
			}
		}

		return true;
	}

	/** Whether the player can stand with their feet at {@code feet} — room above, support below. */
	public static boolean canStandAt(BlockGetter level, BlockPos feet) {
		return hasRoomFor(level, feet) && isStandable(level, feet.below());
	}

	/** Blocks that hurt on contact, which the bot never walks into or stands on. */
	public static boolean isDangerous(BlockState state) {
		return state.is(Blocks.LAVA)
				|| state.is(Blocks.FIRE)
				|| state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.CAMPFIRE)
				|| state.is(Blocks.SOUL_CAMPFIRE)
				|| state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.CACTUS)
				|| state.is(Blocks.SWEET_BERRY_BUSH)
				|| state.is(Blocks.POWDER_SNOW)
				|| state.is(Blocks.WITHER_ROSE)
				|| state.is(Blocks.POINTED_DRIPSTONE);
	}

	/**
	 * Blocks the bot refuses to break, because doing so floods the area, drops the bot, or wastes
	 * the trip.
	 */
	public static boolean isUnsafeToBreak(BlockState state) {
		return state.isAir()
				|| !state.getFluidState().isEmpty()
				|| state.is(Blocks.BEDROCK)
				|| state.is(Blocks.BARRIER)
				|| state.is(Blocks.END_PORTAL_FRAME)
				|| state.is(Blocks.END_PORTAL)
				|| state.is(Blocks.NETHER_PORTAL);
	}

	/** Gravity-affected blocks keep falling into a cleared shaft, so mining under them is avoided. */
	public static boolean isFallingBlock(BlockState state) {
		return state.is(Blocks.SAND)
				|| state.is(Blocks.RED_SAND)
				|| state.is(Blocks.GRAVEL)
				|| state.is(Blocks.SUSPICIOUS_SAND)
				|| state.is(Blocks.SUSPICIOUS_GRAVEL);
	}
}
