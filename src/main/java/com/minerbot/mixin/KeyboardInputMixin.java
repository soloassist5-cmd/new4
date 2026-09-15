package com.minerbot.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.minerbot.bot.BotInputState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;

/**
 * Replaces the keyboard-derived movement with the bot's, right after vanilla has polled the keys
 * and before {@code LocalPlayer#aiStep} consumes the result.
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
	@Inject(method = "tick()V", at = @At("RETURN"))
	private void minerbot$applyBotInput(CallbackInfo ci) {
		if (!BotInputState.isActive()) {
			return;
		}

		LocalPlayer player = Minecraft.getInstance().player;

		if (player == null || player.input != (Object) this) {
			return;
		}

		BotInputState.applyTo((ClientInput) (Object) this, player.getYRot());
	}
}
