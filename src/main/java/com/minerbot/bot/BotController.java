package com.minerbot.bot;

import org.jspecify.annotations.Nullable;

import com.minerbot.screen.MinerBotScreen;
import com.minerbot.task.BotTask;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Owns the running task and the conditions under which the bot gives the controls back.
 *
 * <p>The bot stops rather than fights the player: touching a movement key, dropping below the
 * health floor or losing the world all hand control straight back.
 */
public final class BotController {
	private static final BotController INSTANCE = new BotController();

	/** Below this many hearts the bot stops so the player can react. */
	private static final float HEALTH_FLOOR = 8.0F;

	@Nullable
	private BotTask task;

	private BotController() {
	}

	public static BotController get() {
		return INSTANCE;
	}

	public boolean isRunning() {
		return task != null;
	}

	@Nullable
	public BotTask task() {
		return task;
	}

	public void start(BotTask newTask) {
		stop(null);
		task = newTask;
	}

	/** Stops the current task. A {@code reason} is shown to the player; {@code null} is silent. */
	public void stop(@Nullable Component reason) {
		if (task != null) {
			task.onStop();
			task = null;
		}

		BotInputState.release();
		BotRuntime.setDrivingBreakLoop(false);

		if (reason != null) {
			tell(reason);
		}
	}

	/** Runs the active task for one tick. Called at the start of each client tick. */
	public void tick(Minecraft client) {
		if (task == null) {
			BotRuntime.setDrivingBreakLoop(false);
			return;
		}

		LocalPlayer player = client.player;

		if (player == null || client.level == null) {
			stop(null);
			return;
		}

		// Any other screen means the player is busy, so hold still rather than walk blindly. The
		// mod's own menu is the exception: it is where the job is watched from, and freezing the
		// bot the moment it is opened would make the status line useless.
		if (client.screen != null && !(client.screen instanceof MinerBotScreen)) {
			BotInputState.release();
			BotRuntime.setDrivingBreakLoop(false);
			return;
		}

		if (playerTookOver(client.options)) {
			stop(Component.translatable("minerbot.stopped.player_input").withStyle(ChatFormatting.YELLOW));
			return;
		}

		if (player.getHealth() <= HEALTH_FLOOR) {
			stop(Component.translatable("minerbot.stopped.low_health").withStyle(ChatFormatting.RED));
			return;
		}

		BotInputState.takeControl();
		BotRuntime.setDrivingBreakLoop(true);

		BotTask current = task;

		switch (current.tick(player)) {
			case FINISHED -> stop(Component.translatable("minerbot.finished", current.label())
					.withStyle(ChatFormatting.GREEN));
			case FAILED -> stop(Component.translatable("minerbot.failed", current.label(), current.status())
					.withStyle(ChatFormatting.RED));
			case RUNNING -> {
				// Keep going next tick.
			}
		}
	}

	private static boolean playerTookOver(Options options) {
		return options.keyUp.isDown()
				|| options.keyDown.isDown()
				|| options.keyLeft.isDown()
				|| options.keyRight.isDown()
				|| options.keyJump.isDown();
	}

	/** Sends a prefixed line to the player's chat, client-side only. */
	public static void tell(Component message) {
		Minecraft client = Minecraft.getInstance();

		if (client.player == null) {
			return;
		}

		client.gui.getChat().addClientSystemMessage(
				Component.literal("[MinerBot] ").withStyle(ChatFormatting.AQUA).append(message));
	}
}
