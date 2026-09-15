package com.minerbot.bot;

import com.minerbot.mixin.ClientInputAccessor;

import net.minecraft.client.player.ClientInput;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * The movement the bot wants for the current tick.
 *
 * <p>Vanilla rebuilds {@link ClientInput#keyPresses} and the move vector from the keyboard every
 * tick inside {@code LocalPlayer#aiStep}, so the bot's wishes are written on top of that from
 * {@code KeyboardInputMixin} rather than from a client tick callback, which would be overwritten.
 *
 * <p>The desired direction is stored in world space; converting it to the player-relative
 * strafe/forward pair is deferred until the moment it is applied, so a rotation performed later in
 * the same tick cannot skew the movement.
 */
public final class BotInputState {
	/** A move vector shorter than this is treated as "stand still". */
	private static final double EPSILON = 1.0E-4;
	/** How much of an axis must be requested before the matching key counts as held. */
	private static final float KEY_THRESHOLD = 0.3F;

	private static boolean active;
	private static double moveX;
	private static double moveZ;
	private static boolean jump;
	private static boolean sneak;
	private static boolean sprint;

	private BotInputState() {
	}

	/** Hands control back to the keyboard. */
	public static void release() {
		active = false;
		clearMovement();
	}

	/** Declares that the bot drives the player this tick; movement starts out empty. */
	public static void takeControl() {
		active = true;
		clearMovement();
	}

	private static void clearMovement() {
		moveX = 0.0;
		moveZ = 0.0;
		jump = false;
		sneak = false;
		sprint = false;
	}

	public static boolean isActive() {
		return active;
	}

	/** Sets the horizontal direction to walk in, in world space. The magnitude is ignored. */
	public static void moveTowards(double x, double z) {
		double length = Math.sqrt(x * x + z * z);
		if (length < EPSILON) {
			moveX = 0.0;
			moveZ = 0.0;
			return;
		}

		moveX = x / length;
		moveZ = z / length;
	}

	public static void stopMoving() {
		moveX = 0.0;
		moveZ = 0.0;
	}

	public static void setJump(boolean value) {
		jump = value;
	}

	public static void setSneak(boolean value) {
		sneak = value;
	}

	public static void setSprint(boolean value) {
		sprint = value;
	}

	/**
	 * Overwrites the freshly polled keyboard input with the bot's own.
	 *
	 * @param yawDegrees the yaw the player will move with this tick
	 */
	public static void applyTo(ClientInput input, float yawDegrees) {
		float yaw = yawDegrees * Mth.DEG_TO_RAD;
		float sin = Mth.sin(yaw);
		float cos = Mth.cos(yaw);

		// Entity#getInputVector maps (strafe, forward) to world space as
		//   x = strafe * cos - forward * sin
		//   z = forward * cos + strafe * sin
		// so this is that rotation inverted.
		float forward = (float) (moveZ * cos - moveX * sin);
		float strafe = (float) (moveX * cos + moveZ * sin);

		input.keyPresses = new Input(
				forward > KEY_THRESHOLD,
				forward < -KEY_THRESHOLD,
				strafe > KEY_THRESHOLD,
				strafe < -KEY_THRESHOLD,
				jump,
				sneak,
				sprint);
		((ClientInputAccessor) input).minerbot$setMoveVector(new Vec2(strafe, forward));
	}
}
