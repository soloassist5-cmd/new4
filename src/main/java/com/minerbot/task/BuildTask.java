package com.minerbot.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.minerbot.action.BlockBreaker;
import com.minerbot.action.BlockPlacer;
import com.minerbot.action.ToolSelector;
import com.minerbot.blueprint.Blueprint;
import com.minerbot.bot.BotInputState;
import com.minerbot.bot.Navigator;
import com.minerbot.path.PathFinder;
import com.minerbot.selection.Region;
import com.minerbot.util.Registries;

import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Block;

/**
 * Rebuilds a captured structure one or more times, offset along a direction.
 *
 * <p>Blocks that have nothing to click against yet are put back at the end of the queue instead of
 * being written off, because a neighbour placed later in the same pass usually gives them the
 * support they were missing. A pass that places nothing at all means the rest genuinely cannot be
 * built, and the task stops.
 */
public final class BuildTask implements BotTask {
	private final Blueprint blueprint;
	private final Direction direction;
	private final int copies;

	private final BlockPlacer placer = new BlockPlacer();
	private final BlockBreaker breaker = new BlockBreaker();
	private final Navigator navigator = new Navigator();

	private final List<Blueprint.Placement> queue = new ArrayList<>();
	private final Set<BlockPos> deferred = new HashSet<>();
	private final Map<BlockPos, Component> reasons = new HashMap<>();

	private int cursor;
	private int totalPlanned;
	private int placed;
	private int placedThisPass;
	private int passes;
	@Nullable
	private Component failure;

	/** How many times the whole queue may be retried before giving up. */
	private static final int MAX_PASSES = 6;

	private static final Component REASON_NO_SUPPORT = Component.translatable("minerbot.build.no_support");
	private static final Component REASON_UNREACHABLE = Component.translatable("minerbot.build.unreachable");
	private static final Component REASON_CANNOT_CLEAR = Component.translatable("minerbot.build.cannot_clear");
	private static final Component REASON_UNKNOWN = Component.translatable("minerbot.build.unknown");

	/**
	 * Groups the deferred positions by the reason they were put aside.
	 *
	 * <p>Grouped on the rendered text rather than the component itself, so two reasons that read
	 * the same to the player are counted together whatever built them.
	 */
	private Component describeReasons() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, Component> samples = new LinkedHashMap<>();

		for (BlockPos pos : deferred) {
			Component reason = reasons.getOrDefault(pos, REASON_UNKNOWN);
			String text = reason.getString();
			counts.merge(text, 1, Integer::sum);
			samples.putIfAbsent(text, reason);
		}

		MutableComponent described = Component.empty();
		boolean firstEntry = true;

		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (!firstEntry) {
				described.append("; ");
			}

			firstEntry = false;
			described.append(Component.translatable(
					"minerbot.build.reason_entry", entry.getValue(), samples.get(entry.getKey())));
		}

		return described;
	}

	public BuildTask(Blueprint blueprint, Direction direction, int copies) {
		this.blueprint = blueprint;
		this.direction = direction;
		this.copies = copies;
	}

	@Override
	public Component label() {
		return Component.translatable("minerbot.task.build");
	}

	/** Where each copy of the structure will land. */
	@Override
	public List<Region> highlightedRegions() {
		List<Region> regions = new ArrayList<>(copies);
		int step = blueprint.source().sizeAlong(direction);

		for (int copy = 1; copy <= copies; copy++) {
			BlockPos offset = new BlockPos(
					direction.getStepX() * step * copy,
					direction.getStepY() * step * copy,
					direction.getStepZ() * step * copy);
			regions.add(blueprint.source().offset(offset));
		}

		return regions;
	}

	/** Builds the work queue. Call once before the first tick. */
	public int plan(LocalPlayer player) {
		queue.clear();
		deferred.clear();
		reasons.clear();
		cursor = 0;

		int step = blueprint.source().sizeAlong(direction);

		for (int copy = 1; copy <= copies; copy++) {
			BlockPos origin = blueprint.source().min().offset(
					direction.getStepX() * step * copy,
					direction.getStepY() * step * copy,
					direction.getStepZ() * step * copy);

			queue.addAll(blueprint.placements(origin));
		}

		// Building upwards means each block has the one below it to stand on and click against.
		BlockPos start = player.blockPosition();
		queue.sort(Comparator
				.comparingInt((Blueprint.Placement placement) -> placement.pos().getY())
				.thenComparingDouble(placement -> placement.pos().distSqr(start)));

		totalPlanned = queue.size();
		return totalPlanned;
	}

	/** Materials needed for the whole job, minus what the hotbar already holds. */
	public Map<Block, Integer> missingMaterials(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		Map<Block, Integer> missing = new LinkedHashMap<>();

		blueprint.materials().forEach((block, perCopy) -> {
			int needed = perCopy * copies;
			int have = ToolSelector.countInHotbar(inventory, block.asItem());

			if (have < needed) {
				missing.put(block, needed - have);
			}
		});

		return missing;
	}

	@Override
	public Result tick(LocalPlayer player) {
		if (cursor >= queue.size()) {
			return startNextPass();
		}

		Blueprint.Placement placement = queue.get(cursor);
		BlockPos pos = placement.pos();
		Block block = placement.block();

		if (!BlockPlacer.needsPlacing(player, pos, block)) {
			advance();
			return Result.RUNNING;
		}

		if (!BlockPlacer.hasAnySupport(player, pos)) {
			// Nothing to click against yet; a neighbour placed later may fix that.
			defer(pos, REASON_NO_SUPPORT);
			return Result.RUNNING;
		}

		if (ToolSelector.findHotbarSlot(player.getInventory(), block.asItem()) < 0) {
			failure = Component.translatable("minerbot.build.out_of", Registries.nameOf(block))
					.withStyle(ChatFormatting.RED);
			return Result.FAILED;
		}

		PathFinder.Settings settings = PathFinder.Settings.walkOnly();
		// Standing in the hole means the block would land inside the player's own hitbox, and a
		// position is only good enough if a face of some neighbour can actually be clicked from it.
		Navigator.Target target = new Navigator.Target(
				pos,
				feet -> !feet.equals(pos) && !feet.above().equals(pos),
				(self, eye) -> BlockPlacer.canPlaceFrom(self, eye, pos));

		switch (navigator.advance(player, target, settings)) {
			case ARRIVED -> {
				return work(player, pos, block);
			}
			case NEEDS_BREAK -> {
				BlockPos inTheWay = navigator.blockToBreak();
				return inTheWay == null ? Result.RUNNING : clear(player, inTheWay);
			}
			case MOVING -> {
				return Result.RUNNING;
			}
			case UNREACHABLE -> {
				defer(pos, REASON_UNREACHABLE);
				return Result.RUNNING;
			}
		}

		return Result.RUNNING;
	}

	private Result work(LocalPlayer player, BlockPos pos, Block block) {
		// Something else is standing where this block belongs, so it has to come out first.
		if (BlockPlacer.isObstructed(player, pos, block)) {
			return clear(player, pos);
		}

		switch (placer.tick(player, pos, block)) {
			case DONE -> {
				placed++;
				placedThisPass++;
				advance();
			}
			case FAILED -> {
				Component reason = placer.refusal();
				defer(pos, reason == null ? REASON_UNKNOWN : reason);
			}
			case WORKING -> {
				// Aiming or waiting out the placement cooldown.
			}
		}

		return Result.RUNNING;
	}

	private Result clear(LocalPlayer player, BlockPos pos) {
		if (!Navigator.isInPosition(player, Navigator.Target.toBreak(pos))) {
			navigator.reset();
			return Result.RUNNING;
		}

		switch (breaker.tick(player, pos, null)) {
			case FAILED -> {
				defer(pos, REASON_CANNOT_CLEAR);
			}
			case DONE, WORKING -> {
				// The next tick re-checks what is at the position.
			}
		}

		return Result.RUNNING;
	}

	private void advance() {
		cursor++;
		breaker.cancel();
		placer.reset();
	}

	/** Puts a position aside for the next pass and records why, for the final report. */
	private void defer(BlockPos pos, Component reason) {
		deferred.add(pos);
		reasons.put(pos, reason);
		advance();
		navigator.reset();
	}

	/** Retries everything that was skipped, as long as the previous pass achieved something. */
	private Result startNextPass() {
		if (deferred.isEmpty()) {
			return Result.FINISHED;
		}

		if (placedThisPass == 0 || ++passes > MAX_PASSES) {
			failure = Component.translatable("minerbot.build.failed", deferred.size(), describeReasons())
					.withStyle(ChatFormatting.RED);
			return Result.FAILED;
		}

		Set<BlockPos> retry = Set.copyOf(deferred);
		deferred.clear();
		reasons.clear();
		queue.removeIf(placement -> !retry.contains(placement.pos()));
		cursor = 0;
		placedThisPass = 0;
		return Result.RUNNING;
	}

	@Override
	public Component status() {
		if (failure != null) {
			return failure;
		}

		return Component.translatable("minerbot.status.building", placed, totalPlanned, deferred.size(), passes + 1)
				.withStyle(ChatFormatting.GRAY);
	}

	@Override
	public void onStop() {
		breaker.cancel();
		navigator.reset();
		placer.reset();
		BotInputState.release();
	}
}
