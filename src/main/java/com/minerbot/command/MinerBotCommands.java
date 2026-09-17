package com.minerbot.command;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import com.minerbot.blueprint.Blueprint;
import com.minerbot.bot.BotController;
import com.minerbot.bot.TaskLauncher;
import com.minerbot.config.BotSettings;
import com.minerbot.config.BuildDirection;
import com.minerbot.screen.MinerBotScreen;
import com.minerbot.selection.Region;
import com.minerbot.selection.SelectionManager;
import com.minerbot.task.BotTask;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The {@code /minerbot} command tree.
 *
 * <p>The menu is the way the mod is meant to be driven; these are kept because a command is what a
 * macro or a keybound script can call. Both go through {@link TaskLauncher} and share
 * {@link BotSettings}, so whichever one is used the other shows the same state.
 */
public final class MinerBotCommands {
	private MinerBotCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> build(dispatcher));
	}

	private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("minerbot")
				.executes(MinerBotCommands::openMenu)
				.then(ClientCommands.literal("menu").executes(MinerBotCommands::openMenu))
				.then(ClientCommands.literal("pos1").executes(ctx -> setCorner(ctx, true)))
				.then(ClientCommands.literal("pos2").executes(ctx -> setCorner(ctx, false)))
				.then(ClientCommands.literal("sel")
						.then(ClientCommands.literal("clear").executes(MinerBotCommands::clearSelection))
						.executes(MinerBotCommands::showSelection))
				.then(ClientCommands.literal("mine")
						.executes(ctx -> mine(ctx, null, null))
						.then(ClientCommands.argument("block", StringArgumentType.word())
								.executes(ctx -> mine(ctx, StringArgumentType.getString(ctx, "block"), null))
								.then(ClientCommands.argument("tool", StringArgumentType.word())
										.executes(ctx -> mine(ctx,
												StringArgumentType.getString(ctx, "block"),
												StringArgumentType.getString(ctx, "tool"))))))
				.then(ClientCommands.literal("copy").executes(MinerBotCommands::copy))
				.then(ClientCommands.literal("build")
						.then(ClientCommands.argument("direction", StringArgumentType.word())
								.executes(ctx -> buildCopies(ctx, StringArgumentType.getString(ctx, "direction"), 1))
								.then(ClientCommands.argument("count",
												IntegerArgumentType.integer(1, BotSettings.MAX_COPIES))
										.executes(ctx -> buildCopies(ctx,
												StringArgumentType.getString(ctx, "direction"),
												IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(ClientCommands.literal("tunnel")
						.then(ClientCommands.argument("enabled", StringArgumentType.word())
								.executes(MinerBotCommands::setTunnelling)))
				.then(ClientCommands.literal("stop").executes(MinerBotCommands::stop))
				.then(ClientCommands.literal("status").executes(MinerBotCommands::status)));
	}

	private static int openMenu(CommandContext<FabricClientCommandSource> ctx) {
		// The chat screen is still up at this point; the menu has to wait for it to close.
		ctx.getSource().getClient().execute(() -> {
			var client = ctx.getSource().getClient();

			if (client.screen == null) {
				client.setScreen(MinerBotScreen.open(client));
			}
		});
		return 1;
	}

	private static int setCorner(CommandContext<FabricClientCommandSource> ctx, boolean first) {
		BlockPos pos = lookedAtBlock(ctx.getSource());

		if (pos == null) {
			pos = ctx.getSource().getPlayer().blockPosition();
		}

		if (first) {
			SelectionManager.get().setFirst(pos);
		} else {
			SelectionManager.get().setSecond(pos);
		}

		ctx.getSource().sendFeedback(Component.translatable("minerbot.screen.corner_set",
				first ? 1 : 2, pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GRAY));
		return 1;
	}

	/** The block under the crosshair, or {@code null} when the player is looking at nothing. */
	@Nullable
	private static BlockPos lookedAtBlock(FabricClientCommandSource source) {
		HitResult hit = source.getClient().hitResult;

		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			return blockHit.getBlockPos();
		}

		return null;
	}

	private static int clearSelection(CommandContext<FabricClientCommandSource> ctx) {
		SelectionManager.get().clear();
		ctx.getSource().sendFeedback(
				Component.translatable("minerbot.screen.cleared").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int showSelection(CommandContext<FabricClientCommandSource> ctx) {
		Region region = SelectionManager.get().region();

		if (region == null) {
			ctx.getSource().sendError(Component.translatable("minerbot.error.no_selection"));
			return 0;
		}

		ctx.getSource().sendFeedback(Component.translatable(
				"minerbot.screen.selection", region.toString(), region.volume()).withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int mine(CommandContext<FabricClientCommandSource> ctx,
			@Nullable String blockName, @Nullable String toolName) {
		if (blockName != null) {
			BotSettings.setBlockName(blockName.equals("all") ? "" : blockName);
		}

		if (toolName != null) {
			BotSettings.setToolName(toolName.equals("auto") ? "" : toolName);
		}

		return report(ctx, TaskLauncher.startMining(ctx.getSource().getPlayer()));
	}

	private static int copy(CommandContext<FabricClientCommandSource> ctx) {
		return report(ctx, TaskLauncher.copySelection(ctx.getSource().getPlayer()));
	}

	private static int buildCopies(CommandContext<FabricClientCommandSource> ctx, String directionName, int count) {
		BuildDirection direction = BuildDirection.byName(directionName);

		if (direction == null) {
			ctx.getSource().sendError(Component.translatable("minerbot.error.unknown_direction", directionName));
			return 0;
		}

		BotSettings.setBuildDirection(direction);
		BotSettings.setCopies(count);
		return report(ctx, TaskLauncher.startBuilding(ctx.getSource().getPlayer()));
	}

	private static int setTunnelling(CommandContext<FabricClientCommandSource> ctx) {
		String value = StringArgumentType.getString(ctx, "enabled");
		boolean enabled = value.equals("on") || value.equals("true");
		BotSettings.setTunnelling(enabled);
		ctx.getSource().sendFeedback(Component.translatable(
				enabled ? "minerbot.tunnel.on" : "minerbot.tunnel.off").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int stop(CommandContext<FabricClientCommandSource> ctx) {
		return report(ctx, TaskLauncher.stop());
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		BotTask task = BotController.get().task();

		if (task == null) {
			ctx.getSource().sendFeedback(
					Component.translatable("minerbot.status.idle").withStyle(ChatFormatting.GRAY));
		} else {
			ctx.getSource().sendFeedback(task.status());
		}

		Region region = SelectionManager.get().region();
		ctx.getSource().sendFeedback(region == null
				? Component.translatable("minerbot.screen.selection.none").withStyle(ChatFormatting.GRAY)
				: Component.translatable("minerbot.screen.selection", region.toString(), region.volume())
						.withStyle(ChatFormatting.GRAY));

		Blueprint clipboard = BotSettings.clipboard();
		ctx.getSource().sendFeedback(clipboard == null
				? Component.translatable("minerbot.screen.build.clipboard.empty").withStyle(ChatFormatting.GRAY)
				: Component.translatable("minerbot.screen.build.clipboard", clipboard.blockCount())
						.withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int report(CommandContext<FabricClientCommandSource> ctx, TaskLauncher.Outcome outcome) {
		if (outcome.ok()) {
			ctx.getSource().sendFeedback(outcome.message());
		} else {
			ctx.getSource().sendError(outcome.message());
		}

		List<Component> details = outcome.details();

		for (Component detail : details) {
			ctx.getSource().sendError(detail);
		}

		return outcome.ok() ? 1 : 0;
	}
}
