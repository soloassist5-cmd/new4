package com.minerbot;

import com.minerbot.bot.BotController;
import com.minerbot.command.MinerBotCommands;
import com.minerbot.render.BotHud;
import com.minerbot.render.RegionParticles;
import com.minerbot.selection.Region;
import com.minerbot.selection.SelectionManager;
import com.minerbot.task.BotTask;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class MinerBotClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MinerBot.id("main"));

	private static final KeyMapping SET_FIRST_CORNER = new KeyMapping(
			"key.minerbot.pos1", InputConstants.Type.KEYSYM, InputConstants.KEY_LBRACKET, CATEGORY);
	private static final KeyMapping SET_SECOND_CORNER = new KeyMapping(
			"key.minerbot.pos2", InputConstants.Type.KEYSYM, InputConstants.KEY_RBRACKET, CATEGORY);
	private static final KeyMapping STOP = new KeyMapping(
			"key.minerbot.stop", InputConstants.Type.KEYSYM, InputConstants.KEY_BACKSLASH, CATEGORY);

	@Override
	public void onInitializeClient() {
		KeyMappingHelper.registerKeyMapping(SET_FIRST_CORNER);
		KeyMappingHelper.registerKeyMapping(SET_SECOND_CORNER);
		KeyMappingHelper.registerKeyMapping(STOP);

		MinerBotCommands.register();
		BotHud.register();

		// The bot runs at the start of the tick so its decisions reach the movement code in the
		// same tick rather than one tick late.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			handleKeys(client);
			BotController.get().tick(client);
		});

		ClientTickEvents.END_CLIENT_TICK.register(MinerBotClient::drawOutlines);

		MinerBot.LOGGER.info("MinerBot ready");
	}

	private static void handleKeys(Minecraft client) {
		LocalPlayer player = client.player;

		if (player == null) {
			return;
		}

		while (SET_FIRST_CORNER.consumeClick()) {
			markCorner(client, player, true);
		}

		while (SET_SECOND_CORNER.consumeClick()) {
			markCorner(client, player, false);
		}

		while (STOP.consumeClick()) {
			if (BotController.get().isRunning()) {
				BotController.get().stop(Component.literal("stopped").withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	private static void markCorner(Minecraft client, LocalPlayer player, boolean first) {
		BlockPos pos = player.blockPosition();

		if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			pos = hit.getBlockPos();
		}

		if (first) {
			SelectionManager.get().setFirst(pos);
		} else {
			SelectionManager.get().setSecond(pos);
		}

		BotController.tell(Component.literal("corner %d: %d %d %d"
				.formatted(first ? 1 : 2, pos.getX(), pos.getY(), pos.getZ()))
				.withStyle(ChatFormatting.GRAY));
	}

	private static void drawOutlines(Minecraft client) {
		if (client.level == null || client.player == null) {
			return;
		}

		if (!RegionParticles.shouldDraw(client.level.getGameTime())) {
			return;
		}

		Region selection = SelectionManager.get().region();

		if (selection != null) {
			RegionParticles.draw(client.level, selection, RegionParticles.selectionParticle());
		}

		BotTask task = BotController.get().task();

		if (task != null) {
			for (Region region : task.highlightedRegions()) {
				RegionParticles.draw(client.level, region, RegionParticles.targetParticle());
			}
		}
	}
}
