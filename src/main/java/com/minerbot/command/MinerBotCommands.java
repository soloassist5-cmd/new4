package com.minerbot.command;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import com.minerbot.blueprint.Blueprint;
import com.minerbot.bot.BotController;
import com.minerbot.selection.Region;
import com.minerbot.selection.SelectionManager;
import com.minerbot.task.BotTask;
import com.minerbot.task.BuildTask;
import com.minerbot.task.MineTask;
import com.minerbot.util.Registries;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** The {@code /minerbot} command tree. */
public final class MinerBotCommands {
	/** The blueprint captured by {@code /minerbot copy}, reused by every {@code build}. */
	@Nullable
	private static Blueprint clipboard;
	/** Whether mining may tunnel through blocks outside the selected region. */
	private static boolean tunnelling;

	private MinerBotCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> build(dispatcher));
	}

	private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("minerbot")
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
								.then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 64))
										.executes(ctx -> buildCopies(ctx,
												StringArgumentType.getString(ctx, "direction"),
												IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(ClientCommands.literal("tunnel")
						.then(ClientCommands.argument("enabled", StringArgumentType.word())
								.executes(MinerBotCommands::setTunnelling)))
				.then(ClientCommands.literal("stop").executes(MinerBotCommands::stop))
				.then(ClientCommands.literal("status").executes(MinerBotCommands::status)));
	}

	private static int setCorner(CommandContext<FabricClientCommandSource> ctx, boolean first) {
		LocalPlayer player = ctx.getSource().getPlayer();
		BlockPos pos = lookedAtBlock(ctx.getSource());

		if (pos == null) {
			pos = player.blockPosition();
		}

		if (first) {
			SelectionManager.get().setFirst(pos);
		} else {
			SelectionManager.get().setSecond(pos);
		}

		feedback(ctx, "corner %d set to %d %d %d".formatted(first ? 1 : 2, pos.getX(), pos.getY(), pos.getZ()));
		return 1;
	}

	/** The block under the crosshair, or {@code null} when the player is looking at nothing. */
	@Nullable
	public static BlockPos lookedAtBlock(FabricClientCommandSource source) {
		HitResult hit = source.getClient().hitResult;

		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			return blockHit.getBlockPos();
		}

		return null;
	}

	private static int clearSelection(CommandContext<FabricClientCommandSource> ctx) {
		SelectionManager.get().clear();
		feedback(ctx, "selection cleared");
		return 1;
	}

	private static int showSelection(CommandContext<FabricClientCommandSource> ctx) {
		Region region = SelectionManager.get().region();

		if (region == null) {
			error(ctx, "set both corners first: /minerbot pos1 and /minerbot pos2");
			return 0;
		}

		feedback(ctx, "selection " + region + ", " + region.volume() + " blocks");
		return 1;
	}

	private static int mine(CommandContext<FabricClientCommandSource> ctx,
			@Nullable String blockName, @Nullable String toolName) {
		Region region = SelectionManager.get().region();

		if (region == null) {
			error(ctx, "set both corners first: /minerbot pos1 and /minerbot pos2");
			return 0;
		}

		if (region.volume() > MineTask.MAX_REGION_VOLUME) {
			error(ctx, "region is too large (%d blocks, limit %d)".formatted(region.volume(), MineTask.MAX_REGION_VOLUME));
			return 0;
		}

		Set<Block> wanted = new HashSet<>();

		if (blockName != null && !blockName.equals("all")) {
			Block block = Registries.block(blockName);

			if (block == null) {
				error(ctx, "unknown block: " + blockName);
				return 0;
			}

			wanted.add(block);
		}

		Item tool = null;

		if (toolName != null && !toolName.equals("auto")) {
			tool = Registries.item(toolName);

			if (tool == null) {
				error(ctx, "unknown item: " + toolName);
				return 0;
			}
		}

		LocalPlayer player = ctx.getSource().getPlayer();
		MineTask task = new MineTask(region, wanted, tool, tunnelling);
		int found = task.scan(player);

		if (found == 0) {
			error(ctx, "nothing to mine in the selection");
			return 0;
		}

		BotController.get().start(task);
		feedback(ctx, "mining %d block(s)%s".formatted(found, tunnelling ? ", tunnelling allowed" : ""));
		return 1;
	}

	private static int copy(CommandContext<FabricClientCommandSource> ctx) {
		Region region = SelectionManager.get().region();

		if (region == null) {
			error(ctx, "set both corners first: /minerbot pos1 and /minerbot pos2");
			return 0;
		}

		clipboard = Blueprint.capture(ctx.getSource().getLevel(), region);
		feedback(ctx, "copied %d block(s) from %s".formatted(clipboard.blockCount(), region));
		return 1;
	}

	private static int buildCopies(CommandContext<FabricClientCommandSource> ctx, String directionName, int count) {
		if (clipboard == null) {
			error(ctx, "nothing copied yet — select a structure and run /minerbot copy");
			return 0;
		}

		LocalPlayer player = ctx.getSource().getPlayer();
		Direction direction = parseDirection(directionName, player);

		if (direction == null) {
			error(ctx, "unknown direction: " + directionName + " (north/south/east/west/up/down/facing)");
			return 0;
		}

		BuildTask task = new BuildTask(clipboard, direction, count);
		int total = task.plan(player);
		Map<Block, Integer> missing = task.missingMaterials(player);

		if (!missing.isEmpty()) {
			error(ctx, "not enough materials in the hotbar:");
			missing.forEach((block, amount) ->
					error(ctx, "  %s x%d".formatted(Registries.nameOf(block), amount)));
			return 0;
		}

		BotController.get().start(task);
		feedback(ctx, "building %d copy/copies %s — %d block(s)".formatted(count, direction.getName(), total));
		return 1;
	}

	/** Parses a compass name, or {@code facing} for whichever way the player is looking. */
	@Nullable
	public static Direction parseDirection(String name, LocalPlayer player) {
		if (name.equals("facing") || name.equals("forward")) {
			return player.getDirection();
		}

		return Direction.byName(name.toLowerCase(Locale.ROOT));
	}

	private static int setTunnelling(CommandContext<FabricClientCommandSource> ctx) {
		String value = StringArgumentType.getString(ctx, "enabled");
		tunnelling = value.equals("on") || value.equals("true");
		feedback(ctx, "tunnelling outside the region is " + (tunnelling ? "on" : "off"));
		return 1;
	}

	private static int stop(CommandContext<FabricClientCommandSource> ctx) {
		if (!BotController.get().isRunning()) {
			feedback(ctx, "nothing running");
			return 0;
		}

		BotController.get().stop(Component.literal("stopped by command").withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		BotTask task = BotController.get().task();

		if (task == null) {
			feedback(ctx, "idle");
		} else {
			ctx.getSource().sendFeedback(task.status());
		}

		Region region = SelectionManager.get().region();
		feedback(ctx, region == null ? "no selection" : "selection " + region);
		feedback(ctx, clipboard == null ? "clipboard empty" : "clipboard: " + clipboard.blockCount() + " block(s)");
		return 1;
	}

	private static void feedback(CommandContext<FabricClientCommandSource> ctx, String message) {
		ctx.getSource().sendFeedback(Component.literal(message).withStyle(ChatFormatting.GRAY));
	}

	private static void error(CommandContext<FabricClientCommandSource> ctx, String message) {
		ctx.getSource().sendError(Component.literal(message));
	}
}
