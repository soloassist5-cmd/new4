package com.minerbot.render;

import com.minerbot.MinerBot;
import com.minerbot.bot.BotController;
import com.minerbot.selection.Region;
import com.minerbot.selection.SelectionManager;
import com.minerbot.task.BotTask;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A two-line status readout in the top-left corner while the bot is busy or a selection exists. */
public final class BotHud {
	private static final int MARGIN = 4;
	private static final int LINE_HEIGHT = 10;
	private static final int TEXT_COLOUR = 0xFFFFFFFF;
	private static final int BACKDROP_COLOUR = 0x80000000;

	private BotHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(MinerBot.id("status"), (graphics, deltaTracker) -> {
			Minecraft client = Minecraft.getInstance();

			if (client.player == null || client.options.hideGui) {
				return;
			}

			BotTask task = BotController.get().task();
			Region region = SelectionManager.get().region();

			if (task == null && region == null) {
				return;
			}

			int y = MARGIN;

			if (task != null) {
				y = line(graphics, Component.translatable("minerbot.hud.task", task.label())
						.withStyle(ChatFormatting.AQUA), y);
				y = line(graphics, task.status(), y);
			}

			if (region != null) {
				line(graphics, Component.translatable("minerbot.screen.selection", region.toString(), region.volume())
						.withStyle(ChatFormatting.GRAY), y);
			}
		});
	}

	private static int line(GuiGraphicsExtractor graphics, Component text, int y) {
		Minecraft client = Minecraft.getInstance();
		int width = client.font.width(text);

		graphics.fill(MARGIN - 2, y - 1, MARGIN + width + 2, y + LINE_HEIGHT - 1, BACKDROP_COLOUR);
		graphics.text(client.font, text, MARGIN, y, TEXT_COLOUR);
		return y + LINE_HEIGHT;
	}
}
