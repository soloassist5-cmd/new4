package com.minerbot.config;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * Where copies of a structure go.
 *
 * <p>{@link #FACING} is resolved when the job starts rather than when it is picked, so the player
 * can choose "the way I am looking" from the menu and then turn around before pressing the button
 * without the choice going stale.
 */
public enum BuildDirection {
	FACING("facing", null),
	NORTH("north", Direction.NORTH),
	SOUTH("south", Direction.SOUTH),
	EAST("east", Direction.EAST),
	WEST("west", Direction.WEST),
	UP("up", Direction.UP),
	DOWN("down", Direction.DOWN);

	private final String key;
	private final Direction fixed;

	BuildDirection(String key, Direction fixed) {
		this.key = key;
		this.fixed = fixed;
	}

	public String key() {
		return key;
	}

	public Component label() {
		return Component.translatable("minerbot.direction." + key);
	}

	public Direction resolve(LocalPlayer player) {
		return fixed != null ? fixed : player.getDirection();
	}

	/** Parses a name typed into a command; returns {@code null} when it matches nothing. */
	public static BuildDirection byName(String name) {
		String wanted = name.toLowerCase(java.util.Locale.ROOT);

		if (wanted.equals("forward")) {
			return FACING;
		}

		for (BuildDirection direction : values()) {
			if (direction.key.equals(wanted)) {
				return direction;
			}
		}

		return null;
	}
}
