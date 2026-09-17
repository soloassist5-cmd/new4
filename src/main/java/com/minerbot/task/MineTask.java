package com.minerbot.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.minerbot.action.BlockBreaker;
import com.minerbot.bot.BotInputState;
import com.minerbot.bot.Navigator;
import com.minerbot.path.PathFinder;
import com.minerbot.path.WorldHelper;
import com.minerbot.selection.Region;
import com.minerbot.util.Registries;

import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Clears every matching block out of a region.
 *
 * <p>The region is scanned once up front rather than every tick: a large box holds far too many
 * positions to re-examine sixty times a second, and the set of blocks of a given type only ever
 * shrinks as the bot works.
 */
public final class MineTask implements BotTask {
	/** A scan larger than this is refused rather than silently freezing the client. */
	public static final long MAX_REGION_VOLUME = 1_000_000L;

	private final Region region;
	private final Set<Block> wanted;
	@Nullable
	private final Item pinnedTool;
	private final boolean mayDigOutsideRegion;

	private final BlockBreaker breaker = new BlockBreaker();
	private final Navigator navigator = new Navigator();
	private final List<BlockPos> queue = new ArrayList<>();
	private final Set<BlockPos> skipped = new HashSet<>();

	@Nullable
	private BlockPos current;
	private int mined;
	private int cursor;

	/**
	 * @param wanted              block types to mine; empty means every breakable block
	 * @param pinnedTool          the tool to hold, or {@code null} to pick the fastest each time
	 * @param mayDigOutsideRegion whether the bot may tunnel through blocks outside the region to
	 *                            reach the ones inside it
	 */
	public MineTask(Region region, Set<Block> wanted, @Nullable Item pinnedTool, boolean mayDigOutsideRegion) {
		this.region = region;
		this.wanted = Set.copyOf(wanted);
		this.pinnedTool = pinnedTool;
		this.mayDigOutsideRegion = mayDigOutsideRegion;
	}

	@Override
	public Component label() {
		return Component.translatable("minerbot.task.mine");
	}

	@Override
	public List<Region> highlightedRegions() {
		return List.of(region);
	}

	/**
	 * Fills the work queue from the world. Call once before the first tick.
	 *
	 * <p>The queue is ordered once, top layer first and nearest first within a layer, and then
	 * simply walked. Re-ranking millions of positions every time a block breaks would cost far more
	 * than the slightly better ordering is worth, and working downwards is what keeps the bot from
	 * burying itself anyway.
	 *
	 * @return how many blocks were queued
	 */
	public int scan(LocalPlayer player) {
		queue.clear();
		cursor = 0;

		for (BlockPos pos : region.positions()) {
			if (!player.level().hasChunkAt(pos)) {
				continue;
			}

			if (matches(player, pos)) {
				queue.add(pos.immutable());
			}
		}

		BlockPos start = player.blockPosition();
		queue.sort(Comparator
				.comparingInt((BlockPos pos) -> -pos.getY())
				.thenComparingDouble(pos -> pos.distSqr(start)));

		return queue.size();
	}

	private boolean matches(LocalPlayer player, BlockPos pos) {
		BlockState state = player.level().getBlockState(pos);

		if (WorldHelper.isUnsafeToBreak(state) || state.getDestroySpeed(player.level(), pos) < 0.0F) {
			return false;
		}

		return wanted.isEmpty() || wanted.contains(state.getBlock());
	}

	@Override
	public Result tick(LocalPlayer player) {
		if (current == null || !matches(player, current)) {
			current = nextTarget(player);

			if (current == null) {
				return Result.FINISHED;
			}

			breaker.cancel();
			navigator.reset();
		}

		PathFinder.Settings settings = PathFinder.Settings.mining(
				pos -> mayDigOutsideRegion || region.contains(pos));

		switch (navigator.advance(player, Navigator.Target.toBreak(current), settings)) {
			case ARRIVED -> {
				return mineCurrent(player);
			}
			case NEEDS_BREAK -> {
				BlockPos inTheWay = navigator.blockToBreak();
				return inTheWay == null ? Result.RUNNING : breakThrough(player, inTheWay);
			}
			case MOVING -> {
				return Result.RUNNING;
			}
			case UNREACHABLE -> {
				skipped.add(current);
				current = null;
				navigator.reset();
				return Result.RUNNING;
			}
		}

		return Result.RUNNING;
	}

	private Result mineCurrent(LocalPlayer player) {
		BlockPos target = current;

		if (target == null) {
			return Result.RUNNING;
		}

		switch (breaker.tick(player, target, pinnedTool)) {
			case DONE -> {
				mined++;
				cursor++;
				current = null;
			}
			case FAILED -> {
				skipped.add(target);
				current = null;
				navigator.reset();
			}
			case WORKING -> {
				// Keep chipping away next tick.
			}
		}

		return Result.RUNNING;
	}

	/** Mines a block that is merely in the way, rather than one of the targets. */
	private Result breakThrough(LocalPlayer player, BlockPos pos) {
		if (!Navigator.isInPosition(player, Navigator.Target.toBreak(pos))) {
			// The path put the bot next to it; if it still cannot see it, the path is wrong.
			navigator.reset();
			return Result.RUNNING;
		}

		switch (breaker.tick(player, pos, pinnedTool)) {
			case FAILED -> {
				skipped.add(pos);
				navigator.reset();
			}
			case DONE, WORKING -> {
				// Either way the navigator re-checks the step next tick.
			}
		}

		return Result.RUNNING;
	}

	/** Walks the cursor to the next block still worth mining. */
	@Nullable
	private BlockPos nextTarget(LocalPlayer player) {
		while (cursor < queue.size()) {
			BlockPos pos = queue.get(cursor);

			if (!skipped.contains(pos) && matches(player, pos)) {
				return pos;
			}

			cursor++;
		}

		return null;
	}

	@Override
	public Component status() {
		Component target = wanted.isEmpty()
				? Component.translatable("minerbot.status.everything")
				: Component.literal(wanted.stream().map(Registries::nameOf).reduce((a, b) -> a + ", " + b).orElse(""));

		return Component.translatable("minerbot.status.mining", target, mined, remaining())
				.withStyle(ChatFormatting.GRAY);
	}

	private int remaining() {
		return Math.max(0, queue.size() - cursor);
	}

	@Override
	public void onStop() {
		breaker.cancel();
		navigator.reset();
		BotInputState.release();
	}
}
