package com.minerbot.render;

import com.minerbot.selection.Region;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/**
 * Traces a region's outline with particles.
 *
 * <p>Particles rather than a line renderer: the 26.x render pipeline builds its own draw lists and
 * there is no stable hook for a handful of debug lines, while particles work from any tick and cost
 * nothing to maintain.
 */
public final class RegionParticles {
	/** One marker every this many blocks along an edge. */
	private static final double SPACING = 1.0;
	/** Emitting every tick is a blizzard; this is often enough to read as a solid outline. */
	private static final int INTERVAL_TICKS = 10;
	/** Edges longer than this are drawn sparsely, so a huge selection does not tank the frame rate. */
	private static final int SPARSE_THRESHOLD = 48;

	private RegionParticles() {
	}

	public static boolean shouldDraw(long gameTime) {
		return gameTime % INTERVAL_TICKS == 0;
	}

	/** Draws the twelve edges of {@code region}. */
	public static void draw(ClientLevel level, Region region, ParticleOptions particle) {
		double minX = region.min().getX();
		double minY = region.min().getY();
		double minZ = region.min().getZ();
		double maxX = region.max().getX() + 1.0;
		double maxY = region.max().getY() + 1.0;
		double maxZ = region.max().getZ() + 1.0;

		double step = SPACING * Math.max(1, longestEdge(region) / SPARSE_THRESHOLD);

		for (double x = minX; x <= maxX; x += step) {
			mark(level, particle, x, minY, minZ);
			mark(level, particle, x, minY, maxZ);
			mark(level, particle, x, maxY, minZ);
			mark(level, particle, x, maxY, maxZ);
		}

		for (double y = minY; y <= maxY; y += step) {
			mark(level, particle, minX, y, minZ);
			mark(level, particle, minX, y, maxZ);
			mark(level, particle, maxX, y, minZ);
			mark(level, particle, maxX, y, maxZ);
		}

		for (double z = minZ; z <= maxZ; z += step) {
			mark(level, particle, minX, minY, z);
			mark(level, particle, minX, maxY, z);
			mark(level, particle, maxX, minY, z);
			mark(level, particle, maxX, maxY, z);
		}
	}

	public static ParticleOptions selectionParticle() {
		return ParticleTypes.HAPPY_VILLAGER;
	}

	public static ParticleOptions targetParticle() {
		return ParticleTypes.END_ROD;
	}

	private static int longestEdge(Region region) {
		return Math.max(region.sizeX(), Math.max(region.sizeY(), region.sizeZ()));
	}

	private static void mark(ClientLevel level, ParticleOptions particle, double x, double y, double z) {
		// Past the count limiter and the distance cut-off, so the outline reads the same on the
		// lowest particle setting and from across a large selection.
		level.addParticle(particle, true, true, x, y, z, 0.0, 0.0, 0.0);
	}
}
