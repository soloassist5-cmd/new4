package com.minerbot.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minerbot.bot.BotRuntime;

import net.minecraft.client.Minecraft;

/**
 * Vanilla calls {@code stopDestroyBlock} on every tick the attack key is not held, which would
 * cancel the bot's mining progress. While the bot is breaking a block it drives the break loop
 * itself, so vanilla's handling is skipped entirely.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
	private void minerbot$suppressVanillaBreaking(boolean down, CallbackInfo ci) {
		if (BotRuntime.isDrivingBreakLoop()) {
			ci.cancel();
		}
	}
}
