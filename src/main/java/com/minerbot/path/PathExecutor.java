package com.minerbot.path;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.minerbot.bot.BotInputState;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a path produced by {@link PathFinder} by steering the player's movement input.
 *
 * <p>Blocks that a step needs dug out are not handled here — the executor reports them through
 * {@link #blockingBreak} and waits, so the owning task can mine them with its own tool choice and
 * timing.
 */
public final class PathExecutor {
	/** How close to the middle of a step counts as having arrived. */
	private static final double ARRIVAL_RADIUS = 0.45;
	/** Squared movement below which the bot is considered stuck. */
	private static final double STUCK_SPEED_SQR = 0.0015;
	/** Ticks of no progress before the path is declared dead. */
	private static final int STUCK_TICKS = 40;

	private final List<PathStep> steps;
	private int index;
	private int stuckTicks;
	private Vec3 lastPosition;

	public PathExecutor(List<PathStep> steps) {
		this.steps = steps;
	}

	public boolean isFinished() {
		return index >= steps.size();
	}

	public boolean isStuck() {
		return stuckTicks >= STUCK_TICKS;
	}

	@Nullable
	public PathStep currentStep() {
		return isFinished() ? null : steps.get(index);
	}

	/** The next block the current step needs cleared before it can be walked, if any. */
	@Nullable
	public BlockPos blockingBreak(LocalPlayer player) {
		PathStep step = currentStep();

		if (step == null) {
			return null;
		}

		for (BlockPos pos : step.toBreak()) {
			if (!WorldHelper.isPassable(player.level(), pos)) {
				return pos;
			}
		}

		return null;
	}

	/**
	 * Advances one tick.
	 *
	 * @return {@code true} when the executor asked the player to move this tick
	 */
	public boolean tick(LocalPlayer player) {
		Vec3 position = player.position();

		// Several steps can already be behind the player after a fall or a sprint.
		while (!isFinished()) {
			PathStep step = steps.get(index);

			// The task has to clear the way first.
			if (!step.toBreak().isEmpty() && !isCleared(player, step)) {
				BotInputState.stopMoving();
				return false;
			}

			Vec3 target = centerOf(step.feet());
			double dx = target.x - position.x;
			double dz = target.z - position.z;
			double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
			boolean atHeight = Math.abs(target.y - position.y) < 1.2;

			if (horizontalDistance < ARRIVAL_RADIUS && atHeight) {
				index++;
				stuckTicks = 0;
				continue;
			}

			BotInputState.moveTowards(dx, dz);
			BotInputState.setJump(step.needsJump() && player.onGround() && target.y > position.y + 0.1);
			BotInputState.setSprint(horizontalDistance > 2.5 && !step.needsJump());

			trackProgress(position);
			return true;
		}

		BotInputState.stopMoving();
		return false;
	}

	private boolean isCleared(LocalPlayer player, PathStep step) {
		for (BlockPos pos : step.toBreak()) {
			if (!WorldHelper.isPassable(player.level(), pos)) {
				return false;
			}
		}

		return true;
	}

	private void trackProgress(Vec3 position) {
		if (lastPosition != null) {
			double moved = lastPosition.distanceToSqr(position);
			stuckTicks = moved < STUCK_SPEED_SQR ? stuckTicks + 1 : 0;
		}

		lastPosition = position;
	}

	private static Vec3 centerOf(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}
}
