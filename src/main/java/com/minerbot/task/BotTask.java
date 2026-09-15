package com.minerbot.task;

import java.util.List;

import com.minerbot.selection.Region;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** One job the bot works through, ticked once per client tick while it is the active task. */
public interface BotTask {
	/** What happened during a tick. */
	enum Result {
		/** More to do next tick. */
		RUNNING,
		/** Nothing left; the bot stops cleanly. */
		FINISHED,
		/** The task cannot continue; {@link #status()} explains why. */
		FAILED
	}

	/** Short name shown in chat and on the HUD. */
	String name();

	Result tick(LocalPlayer player);

	/** One line describing what the task is doing right now. */
	Component status();

	/** Areas to outline in the world while the task runs, so the player can see where it works. */
	default List<Region> highlightedRegions() {
		return List.of();
	}

	/** Called when the task stops for any reason, including being cancelled. */
	default void onStop() {
	}
}
