package com.minerbot.gametest;

import com.minerbot.bot.BotController;
import com.minerbot.config.BotSettings;
import com.minerbot.config.BuildDirection;
import com.minerbot.screen.MinerBotScreen;
import com.minerbot.selection.Region;
import com.minerbot.selection.SelectionManager;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Drives the bot end to end in a real client against a real server, through the menu.
 *
 * <p>Both jobs are started by clicking the buttons a player clicks, so the test covers the screen
 * wiring as well as the bot: a button that is missing, mislabelled or left disabled fails the run
 * before the world is ever checked.
 *
 * <p>Run it with {@code ./gradlew runClientGametest}. The arena is built with commands on a
 * platform far from spawn so world generation cannot interfere with the result.
 */
public class MinerBotGameTest implements FabricClientGameTest {
	/** Height of the stone platform the arena sits on. */
	private static final int FLOOR_Y = 99;
	private static final int WORK_Y = FLOOR_Y + 1;

	/** Long enough for the bot to walk a few blocks and chew through stone with a pickaxe. */
	private static final int TASK_TIMEOUT_TICKS = 20 * 90;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(settings -> {
					settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					settings.setName("minerbot-gametest");
				})
				.create()) {
			TestServerContext server = singleplayer.getServer();

			buildArena(server);
			singleplayer.getClientLevel().waitForChunksRender();
			context.waitTicks(20);

			testCornerButton(context);
			testMining(context, server);
			testBuilding(context, server);
		}
	}

	private void buildArena(TestServerContext server) {
		server.runCommand("gamerule doDaylightCycle false");
		server.runCommand("gamerule doWeatherCycle false");
		server.runCommand("gamerule mobGriefing false");
		server.runCommand("gamerule doMobSpawning false");
		server.runCommand("time set day");
		server.runCommand("difficulty peaceful");
		server.runCommand("kill @e[type=!player]");

		// A stone slab to work on, with nothing else in reach.
		server.runCommand("fill 0 %d 0 24 %d 24 minecraft:stone".formatted(FLOOR_Y, FLOOR_Y));
		server.runCommand("fill 0 %d 0 24 %d 24 minecraft:air".formatted(WORK_Y, WORK_Y + 4));

		server.runCommand("tp @a 2.5 %d 2.5".formatted(WORK_Y));
		server.runCommand("gamemode survival @a");
		server.runCommand("clear @a");
		server.runCommand("give @a minecraft:diamond_pickaxe");
		server.runCommand("give @a minecraft:oak_fence 64");
	}

	/** The corner button marks something, so the menu is wired to the selection. */
	private void testCornerButton(ClientGameTestContext context) {
		context.runOnClient(client -> SelectionManager.get().clear());
		openMenu(context);
		context.clickScreenButton("minerbot.screen.corner1");

		boolean marked = context.computeOnClient(client -> SelectionManager.get().first() != null);
		assertThat(marked, "the corner button did not mark anything");

		// Saved for eyeballing the layout; nothing asserts on it.
		context.takeScreenshot("minerbot-menu");

		context.clickScreenButton("minerbot.screen.clear");
		boolean cleared = context.computeOnClient(client -> SelectionManager.get().first() == null);
		assertThat(cleared, "the clear button did not clear the selection");

		closeMenu(context);
	}

	/** Mines a 3x1x3 patch of iron ore, started from the menu, and checks every block is gone. */
	private void testMining(ClientGameTestContext context, TestServerContext server) {
		BlockPos oreMin = new BlockPos(8, WORK_Y, 8);
		BlockPos oreMax = new BlockPos(10, WORK_Y, 10);

		server.runCommand("fill %d %d %d %d %d %d minecraft:iron_ore".formatted(
				oreMin.getX(), oreMin.getY(), oreMin.getZ(), oreMax.getX(), oreMax.getY(), oreMax.getZ()));
		context.waitTicks(10);

		Region region = Region.of(oreMin, oreMax);
		context.runOnClient(client -> {
			SelectionManager.get().setFirst(oreMin);
			SelectionManager.get().setSecond(oreMax);
			BotSettings.setBlockName("iron_ore");
			BotSettings.setToolName("");
			BotSettings.setTunnelling(false);
		});

		openMenu(context);
		context.clickScreenButton("minerbot.screen.mine.start");

		assertThat(context.computeOnClient(client -> BotController.get().isRunning()),
				"the mine button did not start a task");

		waitForBotToStop(context, "mining");

		int left = context.computeOnClient(client -> {
			int remaining = 0;

			for (BlockPos pos : region.positions()) {
				if (client.level.getBlockState(pos).is(Blocks.IRON_ORE)) {
					remaining++;
				}
			}

			return remaining;
		});

		assertThat(left == 0, "bot left " + left + " ore block(s) unmined");
	}

	/** Copies a three-post fence one block south, both steps started from the menu. */
	private void testBuilding(ClientGameTestContext context, TestServerContext server) {
		BlockPos fenceMin = new BlockPos(14, WORK_Y, 14);
		BlockPos fenceMax = new BlockPos(16, WORK_Y, 14);

		server.runCommand("fill %d %d %d %d %d %d minecraft:oak_fence".formatted(
				fenceMin.getX(), fenceMin.getY(), fenceMin.getZ(), fenceMax.getX(), fenceMax.getY(), fenceMax.getZ()));
		context.waitTicks(10);

		context.runOnClient(client -> {
			SelectionManager.get().setFirst(fenceMin);
			SelectionManager.get().setSecond(fenceMax);
			BotSettings.setBuildDirection(BuildDirection.SOUTH);
			BotSettings.setCopies(1);
		});

		openMenu(context);
		context.clickScreenButton("minerbot.screen.build.copy");

		int copied = context.computeOnClient(client -> {
			var clipboard = BotSettings.clipboard();
			return clipboard == null ? 0 : clipboard.blockCount();
		});
		assertThat(copied == 3, "expected 3 fence posts in the clipboard, got " + copied);

		context.clickScreenButton("minerbot.screen.build.start");
		assertThat(context.computeOnClient(client -> BotController.get().isRunning()),
				"the build button did not start a task");

		waitForBotToStop(context, "building");

		int built = context.computeOnClient(client -> {
			int found = 0;

			for (int x = fenceMin.getX(); x <= fenceMax.getX(); x++) {
				if (client.level.getBlockState(new BlockPos(x, WORK_Y, fenceMin.getZ() + 1)).is(Blocks.OAK_FENCE)) {
					found++;
				}
			}

			return found;
		});

		assertThat(built == 3, "bot placed " + built + " of 3 fence posts in the copy");
	}

	private void openMenu(ClientGameTestContext context) {
		context.setScreen(() -> MinerBotScreen.open(net.minecraft.client.Minecraft.getInstance()));
		context.waitTicks(2);
	}

	private void closeMenu(ClientGameTestContext context) {
		context.runOnClient(client -> client.setScreen(null));
		context.waitTicks(2);
	}

	private void waitForBotToStop(ClientGameTestContext context, String what) {
		int ticks = context.waitFor(client -> !BotController.get().isRunning(), TASK_TIMEOUT_TICKS);
		System.out.println("[gametest] " + what + " stopped after " + ticks + " tick(s)");
	}

	private static void assertThat(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
