package com.minerbot.action;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the player's head toward a point.
 *
 * <p>The rotation is applied to the player itself rather than sent as a bare packet, so the client
 * and the server agree on where the bot is looking and the server's own reach and line-of-sight
 * checks see the same thing the bot does.
 */
public final class AimHelper {
	/** Degrees per tick; fast enough to keep up, slow enough not to snap instantly. */
	private static final float TURN_SPEED = 30.0F;
	/** Below this the aim counts as on target. */
	private static final float AIM_TOLERANCE = 3.0F;

	private AimHelper() {
	}

	/**
	 * Rotates one tick's worth toward {@code target}.
	 *
	 * @return {@code true} once the player is looking close enough to act on it
	 */
	public static boolean aimAt(LocalPlayer player, Vec3 target) {
		Vec3 eyes = player.getEyePosition();
		double dx = target.x - eyes.x;
		double dy = target.y - eyes.y;
		double dz = target.z - eyes.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		float wantedYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float wantedPitch = (float) (-(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG));

		float yaw = Mth.approachDegrees(player.getYRot(), wantedYaw, TURN_SPEED);
		float pitch = Mth.approachDegrees(player.getXRot(), wantedPitch, TURN_SPEED);

		player.setYRot(yaw);
		player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));

		return Math.abs(Mth.degreesDifference(yaw, wantedYaw)) < AIM_TOLERANCE
				&& Math.abs(Mth.degreesDifference(pitch, wantedPitch)) < AIM_TOLERANCE;
	}

	/** The point on {@code face} of {@code pos} that the bot should look at. */
	public static Vec3 faceCenter(BlockPos pos, Direction face) {
		return new Vec3(
				pos.getX() + 0.5 + face.getStepX() * 0.5,
				pos.getY() + 0.5 + face.getStepY() * 0.5,
				pos.getZ() + 0.5 + face.getStepZ() * 0.5);
	}

	/**
	 * Picks the face of {@code pos} that the player can actually see, so the break or place request
	 * carries a direction the server will accept.
	 *
	 * <p>A face buried against a neighbour needs no separate test: the ray to it stops at the
	 * neighbour, so the line-of-sight check already rejects it.
	 *
	 * @return the visible face, or {@code null} when the block is not reachable from here
	 */
	public static Direction visibleFace(LocalPlayer player, BlockPos pos) {
		return visibleFace(player, player.getEyePosition(), pos);
	}

	/**
	 * As {@link #visibleFace(LocalPlayer, BlockPos)}, but answering for a hypothetical eye position.
	 *
	 * <p>The pathfinder uses this to judge candidate standing positions before walking to them,
	 * which is the only way to stop the bot settling somewhere the block turns out to be blocked
	 * from or a hand's breadth out of range.
	 */
	@Nullable
	public static Direction visibleFace(LocalPlayer player, Vec3 eye, BlockPos pos) {
		double reach = player.blockInteractionRange();
		Direction best = null;
		double bestDistance = Double.MAX_VALUE;

		for (Direction face : Direction.values()) {
			Vec3 point = faceCenter(pos, face);
			double distance = eye.distanceTo(point);

			if (distance > reach || distance >= bestDistance) {
				continue;
			}

			if (hasLineOfSight(player, eye, point, pos)) {
				best = face;
				bestDistance = distance;
			}
		}

		return best;
	}

	/** Whether a ray from the player's eyes to {@code point} reaches {@code pos} unobstructed. */
	public static boolean hasLineOfSight(LocalPlayer player, Vec3 point, BlockPos pos) {
		return hasLineOfSight(player, player.getEyePosition(), point, pos);
	}

	/** Whether a ray from {@code eye} to {@code point} reaches {@code pos} unobstructed. */
	public static boolean hasLineOfSight(LocalPlayer player, Vec3 eye, Vec3 point, BlockPos pos) {
		BlockHitResult hit = player.level().clip(new ClipContext(
				eye,
				point,
				ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE,
				player));

		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}
}
