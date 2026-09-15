package com.minerbot.selection;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * The two corners the player has marked. A region only exists once both are set; either corner may
 * be replaced at any time.
 */
public final class SelectionManager {
	private static final SelectionManager INSTANCE = new SelectionManager();

	@Nullable
	private BlockPos first;
	@Nullable
	private BlockPos second;

	private SelectionManager() {
	}

	public static SelectionManager get() {
		return INSTANCE;
	}

	public void setFirst(BlockPos pos) {
		first = pos.immutable();
	}

	public void setSecond(BlockPos pos) {
		second = pos.immutable();
	}

	@Nullable
	public BlockPos first() {
		return first;
	}

	@Nullable
	public BlockPos second() {
		return second;
	}

	public void clear() {
		first = null;
		second = null;
	}

	public boolean isComplete() {
		return first != null && second != null;
	}

	@Nullable
	public Region region() {
		return isComplete() ? Region.of(first, second) : null;
	}
}
