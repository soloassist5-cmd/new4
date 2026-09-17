package com.minerbot.config;

import org.jspecify.annotations.Nullable;

import com.minerbot.blueprint.Blueprint;

/**
 * What the player has chosen, shared by the menu and the commands so the two never disagree.
 *
 * <p>Kept in memory for the session: it is the state of a tool being used, not a preference worth
 * a config file, and a copied structure could not be written to one anyway.
 */
public final class BotSettings {
	/** Highest number of copies the build screen offers, to keep one keystroke from queueing a day of work. */
	public static final int MAX_COPIES = 64;

	/** Empty means "every breakable block in the region". */
	private static String blockName = "";
	/** Empty or {@code auto} means "pick the fastest tool in the hotbar". */
	private static String toolName = "";
	private static boolean tunnelling;
	private static BuildDirection buildDirection = BuildDirection.FACING;
	private static int copies = 1;
	@Nullable
	private static Blueprint clipboard;

	private BotSettings() {
	}

	public static String blockName() {
		return blockName;
	}

	public static void setBlockName(String value) {
		blockName = value.trim();
	}

	public static String toolName() {
		return toolName;
	}

	public static void setToolName(String value) {
		toolName = value.trim();
	}

	/** Whether mining may dig through blocks outside the selected region to reach the ones inside. */
	public static boolean tunnelling() {
		return tunnelling;
	}

	public static void setTunnelling(boolean value) {
		tunnelling = value;
	}

	public static BuildDirection buildDirection() {
		return buildDirection;
	}

	public static void setBuildDirection(BuildDirection value) {
		buildDirection = value;
	}

	public static int copies() {
		return copies;
	}

	public static void setCopies(int value) {
		copies = Math.clamp(value, 1, MAX_COPIES);
	}

	@Nullable
	public static Blueprint clipboard() {
		return clipboard;
	}

	public static void setClipboard(Blueprint value) {
		clipboard = value;
	}
}
