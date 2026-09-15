package com.minerbot.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.minerbot.selection.Region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A snapshot of the blocks inside a region, used as the template for copies.
 *
 * <p>Air is recorded as "nothing here", not as "make this air": copying a fence should leave the
 * ground and the plants between the posts alone rather than clear the whole box.
 */
public final class Blueprint {
	private final Region source;
	private final Map<BlockPos, Block> blocks;

	private Blueprint(Region source, Map<BlockPos, Block> blocks) {
		this.source = source;
		this.blocks = blocks;
	}

	/**
	 * Reads {@code region} out of the world.
	 *
	 * <p>Offsets are stored relative to the region's minimum corner so a copy is just that corner
	 * moved somewhere else.
	 */
	public static Blueprint capture(BlockGetter level, Region region) {
		Map<BlockPos, Block> blocks = new LinkedHashMap<>();

		for (BlockPos pos : region.positions()) {
			BlockState state = level.getBlockState(pos);

			// Air, and anything with no item form such as fluids or fire, means "leave this be".
			if (state.isAir() || state.getBlock().asItem() == Items.AIR) {
				continue;
			}

			blocks.put(pos.subtract(region.min()), state.getBlock());
		}

		return new Blueprint(region, Map.copyOf(blocks));
	}

	public Region source() {
		return source;
	}

	public int blockCount() {
		return blocks.size();
	}

	/** How many of each block a single copy consumes. */
	public Map<Block, Integer> materials() {
		Map<Block, Integer> totals = new LinkedHashMap<>();

		for (Block block : blocks.values()) {
			totals.merge(block, 1, Integer::sum);
		}

		return totals;
	}

	/** The blocks of one copy, with {@code origin} standing in for the source's minimum corner. */
	public List<Placement> placements(BlockPos origin) {
		List<Placement> placements = new ArrayList<>(blocks.size());

		blocks.forEach((offset, block) -> placements.add(new Placement(origin.offset(offset), block)));
		return placements;
	}

	/** One block of a copy: where it goes and what belongs there. */
	public record Placement(BlockPos pos, Block block) {
	}
}
