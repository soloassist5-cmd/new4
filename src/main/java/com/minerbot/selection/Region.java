package com.minerbot.selection;

import java.util.Iterator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** An axis-aligned box of block positions, inclusive on both corners. */
public record Region(BlockPos min, BlockPos max) {
	public static Region of(BlockPos a, BlockPos b) {
		return new Region(
				new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
				new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
	}

	public int sizeX() {
		return max.getX() - min.getX() + 1;
	}

	public int sizeY() {
		return max.getY() - min.getY() + 1;
	}

	public int sizeZ() {
		return max.getZ() - min.getZ() + 1;
	}

	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	public boolean contains(BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	public Region offset(BlockPos delta) {
		return new Region(min.offset(delta), max.offset(delta));
	}

	/** The size of this region measured along {@code axis}, used to lay copies end to end. */
	public int sizeAlong(Direction direction) {
		return switch (direction.getAxis()) {
			case X -> sizeX();
			case Y -> sizeY();
			case Z -> sizeZ();
		};
	}

	/** All positions inside the box, iterated bottom layer first. */
	public Iterable<BlockPos> positions() {
		return () -> new Iterator<>() {
			private int x = min.getX();
			private int y = min.getY();
			private int z = min.getZ();
			private boolean exhausted = false;

			@Override
			public boolean hasNext() {
				return !exhausted;
			}

			@Override
			public BlockPos next() {
				BlockPos result = new BlockPos(x, y, z);

				if (++x > max.getX()) {
					x = min.getX();

					if (++z > max.getZ()) {
						z = min.getZ();

						if (++y > max.getY()) {
							exhausted = true;
						}
					}
				}

				return result;
			}
		};
	}

	@Override
	public String toString() {
		return "[%d,%d,%d] .. [%d,%d,%d] (%dx%dx%d)".formatted(
				min.getX(), min.getY(), min.getZ(),
				max.getX(), max.getY(), max.getZ(),
				sizeX(), sizeY(), sizeZ());
	}
}
