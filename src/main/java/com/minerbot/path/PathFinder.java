package com.minerbot.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A* over the positions the player's feet can occupy.
 *
 * <p>Besides walking, jumping and falling, the search can also tunnel: a move into a blocked cell
 * is allowed at extra cost if the blocking blocks can be mined, which is what lets the bot reach
 * ore buried inside stone instead of only what is already exposed.
 */
public final class PathFinder {
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private static final double COST_STRAIGHT = 1.0;
	private static final double COST_DIAGONAL = 1.414;
	private static final double COST_JUMP = 1.5;
	private static final double COST_FALL_PER_BLOCK = 0.6;
	/** Flat surcharge for a move that needs digging, on top of the per-block hardness cost. */
	private static final double COST_BREAK_BASE = 4.0;
	private static final double COST_BREAK_PER_HARDNESS = 1.5;

	private final BlockGetter level;
	private final Settings settings;

	public PathFinder(BlockGetter level, Settings settings) {
		this.level = level;
		this.settings = settings;
	}

	/**
	 * @param allowMining whether the path may dig through blocks
	 * @param maxFall     how far the bot is willing to drop in one move
	 * @param maxNodes    search budget, to keep a hopeless request from stalling the client
	 * @param canBreak    extra veto on which blocks may be dug through, e.g. "stay inside the region"
	 */
	public record Settings(boolean allowMining, int maxFall, int maxNodes, Predicate<BlockPos> canBreak) {
		public static Settings walkOnly() {
			return new Settings(false, 3, 12000, pos -> false);
		}

		public static Settings mining(Predicate<BlockPos> canBreak) {
			return new Settings(true, 3, 20000, canBreak);
		}
	}

	/**
	 * @param isGoal   accepts any position the bot is happy to end up at
	 * @param goalHint drives the heuristic; it does not have to be reachable or even walkable
	 * @return the steps to walk, or an empty list when no path was found
	 */
	public List<PathStep> find(BlockPos start, Predicate<BlockPos> isGoal, BlockPos goalHint) {
		Map<BlockPos, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.estimate));

		Node startNode = new Node(start, null, null, 0.0, heuristic(start, goalHint));
		nodes.put(start, startNode);
		open.add(startNode);

		Node best = startNode;
		int expanded = 0;

		while (!open.isEmpty() && expanded < settings.maxNodes()) {
			Node current = open.poll();

			if (current.closed) {
				continue;
			}

			current.closed = true;
			expanded++;

			if (isGoal.test(current.pos)) {
				return rebuild(current);
			}

			if (current.estimate - current.cost < best.estimate - best.cost) {
				best = current;
			}

			for (Move move : movesFrom(current.pos)) {
				double cost = current.cost + move.cost;
				Node neighbour = nodes.get(move.feet);

				if (neighbour == null) {
					neighbour = new Node(move.feet, current, move, cost, cost + heuristic(move.feet, goalHint));
					nodes.put(move.feet, neighbour);
					open.add(neighbour);
				} else if (cost < neighbour.cost && !neighbour.closed) {
					neighbour.parent = current;
					neighbour.move = move;
					neighbour.cost = cost;
					neighbour.estimate = cost + heuristic(move.feet, goalHint);
					open.add(neighbour);
				}
			}
		}

		// Nothing reached the goal: walking to the closest point still makes progress, and the
		// caller will search again from there.
		return best == startNode ? List.of() : rebuild(best);
	}

	private double heuristic(BlockPos from, BlockPos to) {
		return Math.sqrt(from.distSqr(to));
	}

	private List<PathStep> rebuild(Node end) {
		List<PathStep> steps = new ArrayList<>();

		for (Node node = end; node.parent != null; node = node.parent) {
			steps.add(new PathStep(node.pos, node.move.toBreak, node.move.needsJump));
		}

		Collections.reverse(steps);
		return steps;
	}

	private List<Move> movesFrom(BlockPos from) {
		List<Move> moves = new ArrayList<>(12);

		for (Direction direction : HORIZONTAL) {
			BlockPos side = from.relative(direction);

			addVerticalMoves(moves, from, side, COST_STRAIGHT);
		}

		addDiagonalMoves(moves, from);
		return moves;
	}

	private void addVerticalMoves(List<Move> moves, BlockPos from, BlockPos side, double baseCost) {
		// Step across, at the same height.
		Move level = tryMove(from, side, baseCost, false);

		if (level != null) {
			moves.add(level);
		}

		// Step up one block.
		Move up = tryMove(from, side.above(), baseCost + COST_JUMP, true);

		if (up != null && headroomForJump(from)) {
			moves.add(up);
		}

		// Drop down, as far as the settings allow.
		for (int drop = 1; drop <= settings.maxFall(); drop++) {
			BlockPos below = side.below(drop);

			if (!WorldHelper.hasRoomFor(this.level, below)) {
				continue;
			}

			if (!WorldHelper.isStandable(this.level, below.below())) {
				continue;
			}

			// The column the bot falls through has to be clear.
			if (!columnClear(side, drop)) {
				break;
			}

			moves.add(new Move(below, baseCost + drop * COST_FALL_PER_BLOCK, List.of(), false));
			break;
		}
	}

	private boolean columnClear(BlockPos side, int drop) {
		for (int dy = 0; dy < drop; dy++) {
			if (!WorldHelper.isPassable(this.level, side.below(dy))) {
				return false;
			}
		}

		return true;
	}

	private boolean headroomForJump(BlockPos from) {
		return WorldHelper.isPassable(this.level, from.above(WorldHelper.PLAYER_HEIGHT_BLOCKS));
	}

	private void addDiagonalMoves(List<Move> moves, BlockPos from) {
		for (Direction first : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
			for (Direction second : new Direction[]{Direction.EAST, Direction.WEST}) {
				BlockPos corner = from.relative(first).relative(second);

				// Cutting a corner through two solid blocks is not something a player can do.
				if (!WorldHelper.hasRoomFor(this.level, from.relative(first))
						|| !WorldHelper.hasRoomFor(this.level, from.relative(second))) {
					continue;
				}

				Move move = tryMove(from, corner, COST_DIAGONAL, false);

				if (move != null && move.toBreak.isEmpty()) {
					moves.add(move);
				}
			}
		}
	}

	/**
	 * Builds the move onto {@code feet}, digging out whatever is in the way when that is allowed.
	 *
	 * @return the move, or {@code null} when the destination is unreachable
	 */
	private Move tryMove(BlockPos from, BlockPos feet, double cost, boolean needsJump) {
		// The bot walks, it does not bridge, so every step needs solid ground already there.
		if (!WorldHelper.isStandable(this.level, feet.below())) {
			return null;
		}

		List<BlockPos> toBreak = new ArrayList<>(2);
		double breakCost = 0.0;

		for (int dy = 0; dy < WorldHelper.PLAYER_HEIGHT_BLOCKS; dy++) {
			BlockPos cell = feet.above(dy);

			if (WorldHelper.isPassable(this.level, cell)) {
				continue;
			}

			if (!settings.allowMining() || !mayBreak(cell)) {
				return null;
			}

			toBreak.add(cell);
			breakCost += COST_BREAK_BASE + hardnessOf(cell) * COST_BREAK_PER_HARDNESS;
		}

		// A jump also needs the ceiling above the starting position out of the way.
		if (needsJump && !WorldHelper.isPassable(this.level, from.above(WorldHelper.PLAYER_HEIGHT_BLOCKS))) {
			BlockPos ceiling = from.above(WorldHelper.PLAYER_HEIGHT_BLOCKS);

			if (!settings.allowMining() || !mayBreak(ceiling)) {
				return null;
			}

			toBreak.add(ceiling);
			breakCost += COST_BREAK_BASE + hardnessOf(ceiling) * COST_BREAK_PER_HARDNESS;
		}

		return new Move(feet, cost + breakCost, List.copyOf(toBreak), needsJump);
	}

	private boolean mayBreak(BlockPos pos) {
		if (!settings.canBreak().test(pos)) {
			return false;
		}

		BlockState state = this.level.getBlockState(pos);

		if (WorldHelper.isUnsafeToBreak(state) || WorldHelper.isDangerous(state)) {
			return false;
		}

		if (state.getDestroySpeed(this.level, pos) < 0.0F) {
			return false;
		}

		// Digging out the block holding up sand or gravel just refills the hole.
		return !WorldHelper.isFallingBlock(this.level.getBlockState(pos.above()));
	}

	private double hardnessOf(BlockPos pos) {
		return Math.max(0.0, this.level.getBlockState(pos).getDestroySpeed(this.level, pos));
	}

	private record Move(BlockPos feet, double cost, List<BlockPos> toBreak, boolean needsJump) {
	}

	private static final class Node {
		final BlockPos pos;
		Node parent;
		Move move;
		double cost;
		double estimate;
		boolean closed;

		Node(BlockPos pos, Node parent, Move move, double cost, double estimate) {
			this.pos = pos;
			this.parent = parent;
			this.move = move;
			this.cost = cost;
			this.estimate = estimate;
		}
	}
}
