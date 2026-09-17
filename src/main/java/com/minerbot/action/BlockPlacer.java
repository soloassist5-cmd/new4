package com.minerbot.action;

import org.jspecify.annotations.Nullable;

import com.minerbot.bot.BotInputState;
import com.minerbot.path.WorldHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Places one block by clicking the face of a neighbour, the same way a player would. */
public final class BlockPlacer {
	/** Ticks between placements, so the server does not see an implausible burst. */
	private static final int PLACE_COOLDOWN = 3;
	/** Ticks spent stepping out of a block's way before the position is written off. */
	private static final int MAX_BACKOFF_TICKS = 30;

	private int cooldown;
	private int backoffTicks;
	@Nullable
	private Component refusal;

	public void reset() {
		cooldown = 0;
		backoffTicks = 0;
		refusal = null;
	}

	/** Why the last {@link Progress#FAILED} happened, for the task to report. */
	@Nullable
	public Component refusal() {
		return refusal;
	}

	private Progress fail(String key, Object... args) {
		refusal = Component.translatable(key, args);
		return Progress.FAILED;
	}

	/** The outcome of one tick of placing. */
	public enum Progress {
		/** Aiming, sneaking down, or waiting out the cooldown. */
		WORKING,
		/** The wanted block is now at the target position. */
		DONE,
		/** Nothing to click against, out of reach, or the item is not in the hotbar. */
		FAILED
	}

	/**
	 * Tries to get {@code wanted} placed at {@code pos} this tick.
	 *
	 * <p>The bot sneaks while placing: {@code performUseItemOn} skips a block's own right-click
	 * behaviour when the player is sneaking, which stops the bot from opening chests or flipping
	 * levers it happens to build against.
	 */
	public Progress tick(LocalPlayer player, BlockPos pos, Block wanted) {
		Minecraft client = Minecraft.getInstance();
		MultiPlayerGameMode gameMode = client.gameMode;

		if (gameMode == null) {
			return fail("minerbot.place.no_game_mode");
		}

		BlockState existing = player.level().getBlockState(pos);

		if (existing.is(wanted)) {
			return Progress.DONE;
		}

		if (!existing.canBeReplaced()) {
			return fail("minerbot.place.occupied");
		}

		// The server refuses a block whose shape would land inside an entity. Rather than give up,
		// walk out of the way the same way a player would and try again next tick.
		if (!player.level().isUnobstructed(wanted.defaultBlockState(), pos, CollisionContext.of(player))) {
			if (++backoffTicks > MAX_BACKOFF_TICKS) {
				return fail("minerbot.place.in_the_way");
			}

			Vec3 away = player.position().subtract(Vec3.atCenterOf(pos));
			BotInputState.moveTowards(away.x, away.z);
			BotInputState.setSneak(true);
			return Progress.WORKING;
		}

		backoffTicks = 0;

		Item item = wanted.asItem();

		if (!ToolSelector.selectItem(player, item)) {
			return fail("minerbot.place.no_item", BuiltInRegistries.ITEM.getKey(item).toString());
		}

		Support support = findSupport(player, player.getEyePosition(), pos);

		if (support == null) {
			return fail("minerbot.place.no_face");
		}

		if (!AimHelper.aimAt(player, support.hitPoint)) {
			BotInputState.setSneak(true);
			BotInputState.stopMoving();
			return Progress.WORKING;
		}

		BotInputState.setSneak(true);
		BotInputState.stopMoving();

		// isSecondaryUseActive reads the input applied on the previous tick, so the bot waits one
		// tick for the crouch to register before clicking.
		if (!player.isShiftKeyDown()) {
			return Progress.WORKING;
		}

		if (cooldown > 0) {
			cooldown--;
			return Progress.WORKING;
		}

		BlockHitResult hit = new BlockHitResult(support.hitPoint, support.faceTowardsTarget, support.pos, false);
		InteractionResult result = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);

		if (result.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND);
			cooldown = PLACE_COOLDOWN;
		}

		return player.level().getBlockState(pos).is(wanted) ? Progress.DONE : Progress.WORKING;
	}

	/**
	 * Finds a neighbouring block whose face can be clicked to put a block at {@code target}.
	 *
	 * @return the click target, or {@code null} when the position is floating or blocked from view
	 */
	@Nullable
	public static Support findSupport(LocalPlayer player, Vec3 eye, BlockPos target) {
		double reach = player.blockInteractionRange();
		Support best = null;
		double bestDistance = Double.MAX_VALUE;

		for (Direction towardsTarget : Direction.values()) {
			BlockPos neighbour = target.relative(towardsTarget.getOpposite());
			BlockState state = player.level().getBlockState(neighbour);

			// Clicking air, or a block that would itself be replaced, places nothing.
			if (state.isAir() || state.canBeReplaced() || state.getCollisionShape(player.level(), neighbour).isEmpty()) {
				continue;
			}

			Vec3 hitPoint = AimHelper.faceCenter(neighbour, towardsTarget);
			double distance = eye.distanceTo(hitPoint);

			if (distance > reach || distance >= bestDistance) {
				continue;
			}

			if (!AimHelper.hasLineOfSight(player, eye, hitPoint, neighbour)) {
				continue;
			}

			best = new Support(neighbour, towardsTarget, hitPoint);
			bestDistance = distance;
		}

		return best;
	}

	/**
	 * Whether a block could be placed at {@code target} by someone whose eyes are at {@code eye}.
	 *
	 * <p>Asked of candidate standing positions before the bot walks to one, so it does not settle
	 * somewhere every face is out of range or hidden behind what it has already built.
	 */
	public static boolean canPlaceFrom(LocalPlayer player, Vec3 eye, BlockPos target) {
		return findSupport(player, eye, target) != null;
	}

	/** Whether anything at all could hold a block at {@code pos} — used to defer floating blocks. */
	public static boolean hasAnySupport(LocalPlayer player, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			BlockState state = player.level().getBlockState(neighbour);

			if (!state.isAir() && !state.canBeReplaced()
					&& !state.getCollisionShape(player.level(), neighbour).isEmpty()) {
				return true;
			}
		}

		return false;
	}

	/** Whether {@code pos} still needs work to hold {@code wanted}. */
	public static boolean needsPlacing(LocalPlayer player, BlockPos pos, Block wanted) {
		return !player.level().getBlockState(pos).is(wanted);
	}

	/** Whether something other than the wanted block is occupying {@code pos}. */
	public static boolean isObstructed(LocalPlayer player, BlockPos pos, Block wanted) {
		BlockState state = player.level().getBlockState(pos);
		return !state.is(wanted) && !state.canBeReplaced() && !WorldHelper.isUnsafeToBreak(state);
	}

	/** A block face to click, and the exact point on it to look at. */
	public record Support(BlockPos pos, Direction faceTowardsTarget, Vec3 hitPoint) {
	}
}
