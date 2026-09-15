package com.minerbot.action;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chooses which hotbar slot to hold.
 *
 * <p>Only the hotbar is considered: swapping an item out of the main inventory needs a container
 * interaction the client cannot fake convincingly, so the bot works with what the player put within
 * reach and says so when something is missing.
 */
public final class ToolSelector {
	private ToolSelector() {
	}

	/**
	 * Holds the fastest hotbar tool for {@code state}.
	 *
	 * @param pinned when set, that item is used instead of picking automatically
	 * @return {@code true} if a slot was selected or already held
	 */
	public static boolean selectFor(Player player, BlockState state, @Nullable Item pinned) {
		Inventory inventory = player.getInventory();

		if (pinned != null) {
			int slot = findHotbarSlot(inventory, pinned);

			if (slot < 0) {
				return false;
			}

			select(inventory, slot);
			return true;
		}

		int bestSlot = -1;
		float bestSpeed = -1.0F;

		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			float speed = stack.getDestroySpeed(state);

			// A tool that yields drops beats a marginally faster one that does not.
			if (stack.isCorrectToolForDrops(state)) {
				speed += 100.0F;
			}

			if (speed > bestSpeed) {
				bestSpeed = speed;
				bestSlot = slot;
			}
		}

		if (bestSlot < 0) {
			return false;
		}

		select(inventory, bestSlot);
		return true;
	}

	/** Holds the hotbar slot containing {@code item}. */
	public static boolean selectItem(Player player, Item item) {
		Inventory inventory = player.getInventory();
		int slot = findHotbarSlot(inventory, item);

		if (slot < 0) {
			return false;
		}

		select(inventory, slot);
		return true;
	}

	public static int findHotbarSlot(Inventory inventory, Item item) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);

			if (!stack.isEmpty() && stack.is(item)) {
				return slot;
			}
		}

		return -1;
	}

	/** How many of {@code item} the hotbar holds in total. */
	public static int countInHotbar(Inventory inventory, Item item) {
		int total = 0;

		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);

			if (!stack.isEmpty() && stack.is(item)) {
				total += stack.getCount();
			}
		}

		return total;
	}

	private static void select(Inventory inventory, int slot) {
		if (inventory.getSelectedSlot() != slot) {
			inventory.setSelectedSlot(slot);
		}
	}
}
