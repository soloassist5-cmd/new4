package com.minerbot.bot;

import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.minerbot.action.AimHelper;
import com.minerbot.path.PathExecutor;
import com.minerbot.path.PathFinder;
import com.minerbot.path.PathStep;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Gets the player into a position the job can actually be done from, and keeps them there.
 *
 * <p>"Close enough" is decided by the same test the action itself will run, asked about candidate
 * standing positions during the search rather than only once the bot has walked there. Judging it
 * by distance alone parks the bot a hand's breadth out of range, or behind something it has just
 * built, and then there is nowhere left to go.
 *
 * <p>Paths go stale as the bot digs and builds, so one is kept only for a while before being
 * recomputed, and a path that stops making progress is thrown away immediately.
 */
public final class Navigator {
	/** Recompute the path at least this often, because the world changes underneath it. */
	private static final int REPATH_INTERVAL = 80;
	/** Consecutive failed searches before a target is written off. */
	private static final int MAX_FAILURES = 3;
	/**
	 * Only positions this much inside reach are tested properly. Ray casting every node the search
	 * touches would be far too slow, and anything further away cannot work out anyway.
	 */
	private static final double COARSE_REACH_MARGIN = 0.8;

	/**
	 * Where the bot is headed and what counts as having got there.
	 *
	 * @param block     the block being worked on
	 * @param standable rejects positions the bot must not stand in, such as the hole a block goes in
	 * @param usable    whether the job can be done by a player whose eyes are at a given point
	 */
	public record Target(BlockPos block, Predicate<BlockPos> standable, Usable usable) {
		/** Whether the job can be done from a given eye position. */
		@FunctionalInterface
		public interface Usable {
			boolean test(LocalPlayer player, Vec3 eye);
		}

		/** A target that only needs to be seen and reached, which is what breaking a block needs. */
		public static Target toBreak(BlockPos block) {
			return new Target(block, feet -> true,
					(player, eye) -> AimHelper.visibleFace(player, eye, block) != null);
		}
	}

	/** What the navigator managed this tick. */
	public enum Status {
		/** Walking; nothing else to do. */
		MOVING,
		/** In position: the job can be done from here. */
		ARRIVED,
		/** A block is in the way; see {@link #blockToBreak()}. */
		NEEDS_BREAK,
		/** No route exists, or every attempt to find one failed. */
		UNREACHABLE
	}

	@Nullable
	private PathExecutor executor;
	@Nullable
	private BlockPos goal;
	@Nullable
	private BlockPos blockToBreak;
	private int sinceRepath;
	private int failures;

	public void reset() {
		executor = null;
		goal = null;
		blockToBreak = null;
		sinceRepath = 0;
		failures = 0;
	}

	@Nullable
	public BlockPos blockToBreak() {
		return blockToBreak;
	}

	/** Moves one tick's worth toward being able to work on {@code target}. */
	public Status advance(LocalPlayer player, Target target, PathFinder.Settings settings) {
		blockToBreak = null;

		if (isInPosition(player, target)) {
			BotInputState.stopMoving();
			executor = null;
			failures = 0;
			return Status.ARRIVED;
		}

		boolean stale = executor == null
				|| !target.block().equals(goal)
				|| executor.isFinished()
				|| executor.isStuck()
				|| sinceRepath++ > REPATH_INTERVAL;

		if (stale && !repath(player, target, settings)) {
			return Status.UNREACHABLE;
		}

		if (executor == null) {
			return Status.UNREACHABLE;
		}

		BlockPos blocking = executor.blockingBreak(player);

		if (blocking != null) {
			blockToBreak = blocking;
			BotInputState.stopMoving();
			return Status.NEEDS_BREAK;
		}

		executor.tick(player);
		return Status.MOVING;
	}

	/** Whether the bot can do the job from exactly where it is standing now. */
	public static boolean isInPosition(LocalPlayer player, Target target) {
		return target.standable().test(player.blockPosition())
				&& target.usable().test(player, player.getEyePosition());
	}

	private boolean repath(LocalPlayer player, Target target, PathFinder.Settings settings) {
		goal = target.block().immutable();
		sinceRepath = 0;

		double coarseReach = player.blockInteractionRange() - COARSE_REACH_MARGIN;
		double eyeHeight = player.getEyeHeight();
		Vec3 blockCenter = Vec3.atCenterOf(target.block());

		Predicate<BlockPos> isGoal = feet -> {
			if (!target.standable().test(feet)) {
				return false;
			}

			Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + eyeHeight, feet.getZ() + 0.5);

			// Cheap distance first: the exact test casts rays, so it must not run on every node.
			return eye.distanceTo(blockCenter) <= coarseReach && target.usable().test(player, eye);
		};

		List<PathStep> steps = new PathFinder(player.level(), settings)
				.find(player.blockPosition(), isGoal, target.block());

		if (steps.isEmpty()) {
			executor = null;
			return ++failures < MAX_FAILURES;
		}

		failures = 0;
		executor = new PathExecutor(steps);
		return true;
	}
}
