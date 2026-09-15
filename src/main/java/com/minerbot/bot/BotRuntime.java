package com.minerbot.bot;

/**
 * The little piece of bot state the mixins need.
 *
 * <p>Kept separate from {@link BotController} so injected code touches one tiny class instead of
 * pulling the whole controller into the mixin's load order.
 */
public final class BotRuntime {
	private static volatile boolean drivingBreakLoop;

	private BotRuntime() {
	}

	/** Whether the bot, rather than the attack key, is driving block breaking this tick. */
	public static boolean isDrivingBreakLoop() {
		return drivingBreakLoop;
	}

	public static void setDrivingBreakLoop(boolean value) {
		drivingBreakLoop = value;
	}
}
