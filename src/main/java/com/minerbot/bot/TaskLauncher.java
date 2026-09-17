package com.minerbot.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.minerbot.blueprint.Blueprint;
import com.minerbot.config.BotSettings;
import com.minerbot.selection.Region;
import com.minerbot.selection.SelectionManager;
import com.minerbot.task.BuildTask;
import com.minerbot.task.MineTask;
import com.minerbot.util.Registries;

import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Turns the player's choices into a running task.
 *
 * <p>The menu and the commands both come through here, so the checks, the wording and the order of
 * operations are the same whichever one the player used.
 */
public final class TaskLauncher {
	private TaskLauncher() {
	}

	/**
	 * The result of trying to start something.
	 *
	 * @param ok      whether the job started
	 * @param message one line summarising what happened, ready to show
	 * @param details extra lines, such as the materials that are missing
	 */
	public record Outcome(boolean ok, Component message, List<Component> details) {
		static Outcome started(Component message) {
			return new Outcome(true, message.copy().withStyle(ChatFormatting.GRAY), List.of());
		}

		static Outcome refused(Component message) {
			return new Outcome(false, message.copy().withStyle(ChatFormatting.RED), List.of());
		}

		static Outcome refused(Component message, List<Component> details) {
			return new Outcome(false, message.copy().withStyle(ChatFormatting.RED), List.copyOf(details));
		}
	}

	/** Starts mining the current selection with the current block and tool choice. */
	public static Outcome startMining(LocalPlayer player) {
		Region region = SelectionManager.get().region();

		if (region == null) {
			return Outcome.refused(Component.translatable("minerbot.error.no_selection"));
		}

		if (region.volume() > MineTask.MAX_REGION_VOLUME) {
			return Outcome.refused(Component.translatable(
					"minerbot.error.region_too_large", region.volume(), MineTask.MAX_REGION_VOLUME));
		}

		Set<Block> wanted = new HashSet<>();
		String blockName = BotSettings.blockName();

		if (!blockName.isEmpty() && !blockName.equals("all")) {
			Block block = Registries.block(blockName);

			if (block == null) {
				return Outcome.refused(Component.translatable("minerbot.error.unknown_block", blockName));
			}

			wanted.add(block);
		}

		Item tool = null;
		String toolName = BotSettings.toolName();

		if (!toolName.isEmpty() && !toolName.equals("auto")) {
			tool = Registries.item(toolName);

			if (tool == null) {
				return Outcome.refused(Component.translatable("minerbot.error.unknown_item", toolName));
			}
		}

		MineTask task = new MineTask(region, wanted, tool, BotSettings.tunnelling());
		int found = task.scan(player);

		if (found == 0) {
			return Outcome.refused(Component.translatable("minerbot.error.nothing_to_mine"));
		}

		BotController.get().start(task);
		return Outcome.started(Component.translatable("minerbot.started.mining", found));
	}

	/** Takes a snapshot of the current selection into the clipboard. */
	public static Outcome copySelection(LocalPlayer player) {
		Region region = SelectionManager.get().region();

		if (region == null) {
			return Outcome.refused(Component.translatable("minerbot.error.no_selection"));
		}

		Blueprint blueprint = Blueprint.capture(player.level(), region);

		if (blueprint.blockCount() == 0) {
			return Outcome.refused(Component.translatable("minerbot.error.nothing_to_copy"));
		}

		BotSettings.setClipboard(blueprint);
		return Outcome.started(Component.translatable("minerbot.started.copied", blueprint.blockCount()));
	}

	/** Starts rebuilding the clipboard in the chosen direction. */
	public static Outcome startBuilding(LocalPlayer player) {
		Blueprint blueprint = BotSettings.clipboard();

		if (blueprint == null) {
			return Outcome.refused(Component.translatable("minerbot.error.clipboard_empty"));
		}

		Direction direction = BotSettings.buildDirection().resolve(player);
		int copies = BotSettings.copies();
		BuildTask task = new BuildTask(blueprint, direction, copies);
		int total = task.plan(player);
		Map<Block, Integer> missing = task.missingMaterials(player);

		if (!missing.isEmpty()) {
			List<Component> details = new ArrayList<>(missing.size());

			missing.forEach((block, amount) -> details.add(Component.translatable(
					"minerbot.error.missing_entry", Registries.nameOf(block), amount)
					.withStyle(ChatFormatting.RED)));

			return Outcome.refused(Component.translatable("minerbot.error.missing_materials"), details);
		}

		BotController.get().start(task);
		return Outcome.started(Component.translatable(
				"minerbot.started.building", copies, Component.translatable("minerbot.direction." + direction.getName()), total));
	}

	/** Stops whatever is running; refuses when nothing is. */
	public static Outcome stop() {
		if (!BotController.get().isRunning()) {
			return Outcome.refused(Component.translatable("minerbot.error.nothing_running"));
		}

		BotController.get().stop(Component.translatable("minerbot.stopped").withStyle(ChatFormatting.YELLOW));
		return Outcome.started(Component.translatable("minerbot.stopped"));
	}
}
