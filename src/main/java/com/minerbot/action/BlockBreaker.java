package com.minerbot.action;

import org.jspecify.annotations.Nullable;

import com.minerbot.bot.BotInputState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the vanilla break loop against one block at a time.
 *
 * <p>Vanilla cancels any progress on a tick where the attack key is not held, so
 * {@code MinecraftMixin} suppresses that handling while {@link #isBusy()} and the break is driven
 * from here instead.
 */
public final class BlockBreaker {
	@Nullable
	private BlockPos target;
	private int ticksOnTarget;

	/** Gives up on a block after this long, so one unreachable target cannot wedge the bot. */
	private static final int TIMEOUT_TICKS = 20 * 30;

	public boolean isBusy() {
		return target != null;
	}

	@Nullable
	public BlockPos target() {
		return target;
	}

	public void cancel() {
		Minecraft client = Minecraft.getInstance();

		if (target != null && client.gameMode != null) {
			client.gameMode.stopDestroyBlock();
		}

		target = null;
		ticksOnTarget = 0;
	}

	/** The outcome of one tick of breaking. */
	public enum Progress {
		/** Still aiming or still chipping away. */
		WORKING,
		/** The block is gone. */
		DONE,
		/** The block cannot be reached or broken from here. */
		FAILED
	}

	/**
	 * Works on {@code pos} for one tick.
	 *
	 * @param pinnedTool the tool the player asked for, or {@code null} to pick the fastest
	 */
	public Progress tick(LocalPlayer player, BlockPos pos, @Nullable Item pinnedTool) {
		Minecraft client = Minecraft.getInstance();
		MultiPlayerGameMode gameMode = client.gameMode;

		if (gameMode == null || client.level == null) {
			return Progress.FAILED;
		}

		if (!pos.equals(target)) {
			cancel();
			target = pos.immutable();
		}

		BlockState state = player.level().getBlockState(pos);

		if (state.isAir()) {
			cancel();
			return Progress.DONE;
		}

		if (++ticksOnTarget > TIMEOUT_TICKS) {
			cancel();
			return Progress.FAILED;
		}

		if (!player.isWithinBlockInteractionRange(pos, 0.0)) {
			return Progress.FAILED;
		}

		Direction visible = AimHelper.visibleFace(player, pos);

		if (visible == null) {
			return Progress.FAILED;
		}

		if (!ToolSelector.selectFor(player, state, pinnedTool)) {
			return Progress.FAILED;
		}

		Vec3 aimPoint = AimHelper.faceCenter(pos, visible);

		if (!AimHelper.aimAt(player, aimPoint)) {
			// Still turning; swinging before the server sees the new angle just gets rejected.
			return Progress.WORKING;
		}

		// Standing still while mining keeps the aim steady and avoids walking out of range.
		BotInputState.stopMoving();

		if (gameMode.continueDestroyBlock(pos, visible)) {
			client.level.addBreakingBlockEffect(pos, visible);
			player.swing(InteractionHand.MAIN_HAND);
		}

		if (player.level().getBlockState(pos).isAir()) {
			cancel();
			return Progress.DONE;
		}

		return Progress.WORKING;
	}
}
