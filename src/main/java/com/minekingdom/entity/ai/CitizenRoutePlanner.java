package com.minekingdom.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Works out how a citizen can get somewhere when walking alone will not do it.
 *
 * <p>The search runs over standing positions, but unlike ordinary pathfinding a wall is
 * not necessarily the end of the road: ground a citizen is allowed to dig counts as a way
 * through at a price, and climbing by dropping a block underfoot is another priced move.
 * Anything a citizen may not break — bedrock, and anything built rather than grown — stays
 * a wall, so a route around it is found instead of a route through it.
 *
 * <p>Because the prices favour walking over digging and digging over building, a plan only
 * cuts or stacks where there is genuinely no cheaper way, which is what keeps citizens from
 * pillaring up out of holes they could have walked out of.
 */
public final class CitizenRoutePlanner {
    private static final int MOVE_COST = 10;
    private static final int DIG_BASE_COST = 20;
    private static final int DIG_HARDNESS_COST = 30;
    /** Deliberately steep: building leaves blocks behind and spends what the citizen carries. */
    private static final int BUILD_COST = 140;

    private CitizenRoutePlanner() {
    }

    /** One leg of a plan: clear these, maybe stack a block underfoot, end up standing here. */
    public record Step(BlockPos stand, List<BlockPos> clear, boolean buildUnderfoot) {
    }

    /**
     * Natural ground a citizen may cut through. Deliberately narrow: stone, dirt and the
     * like, so citizens tunnel through terrain but never through anything anyone built.
     */
    public static boolean isDiggable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        boolean natural = state.is(Tags.Blocks.ORES)
                || state.is(Tags.Blocks.STONE)
                || state.is(Tags.Blocks.GRAVEL)
                || state.is(Tags.Blocks.NETHERRACK)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.GRAVEL);
        if (!natural) {
            return false;
        }
        // The same care the mining rules take: do not let water in, and do not pull sand down.
        if (level.getBlockState(pos.above()).getBlock() instanceof FallingBlock) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty()) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            if (!level.getFluidState(pos.relative(direction)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param canBuild whether the citizen is carrying something to stack up on
     * @return the legs to walk, in order, or an empty list if there is no way through
     */
    public static List<Step> plan(Level level, BlockPos from, BlockPos goal, double arrival,
                                  int horizontal, int vertical, int nodeLimit, boolean canBuild) {
        Map<BlockPos, Node> seen = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>();
        Node start = new Node(from, 0, null, List.of(), false);
        seen.put(from, start);
        queue.add(start);

        Node best = null;
        Node closest = null;
        double closestDistance = Double.MAX_VALUE;
        int expanded = 0;
        while (!queue.isEmpty() && expanded < nodeLimit) {
            Node current = queue.poll();
            if (current.stale) {
                continue;
            }
            expanded++;

            if (within(current.pos, goal, arrival)) {
                best = current;
                break;
            }
            double toGoal = current.pos.distSqr(goal);
            if (toGoal < closestDistance) {
                closestDistance = toGoal;
                closest = current;
            }

            for (Edge edge : edges(level, current.pos, horizontal, vertical, from, canBuild)) {
                int cost = current.cost + edge.cost;
                Node known = seen.get(edge.target);
                if (known != null && known.cost <= cost) {
                    continue;
                }
                if (known != null) {
                    known.stale = true;
                }
                Node next = new Node(edge.target, cost, current, edge.clear, edge.build);
                seen.put(edge.target, next);
                queue.add(next);
            }
        }

        // Somewhere further out than the search box, or too tangled to solve in one go:
        // head for whatever got closest and plan the rest again from there.
        if (best == null) {
            best = closest;
        }
        if (best == null || best.parent == null) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>();
        for (Node node = best; node.parent != null; node = node.parent) {
            steps.add(new Step(node.pos, node.clear, node.build));
        }
        Collections.reverse(steps);
        return steps;
    }

    private static List<Edge> edges(Level level, BlockPos from, int horizontal, int vertical,
                                    BlockPos origin, boolean canBuild) {
        List<Edge> edges = new ArrayList<>(13);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = 1; dy >= -1; dy--) {
                BlockPos target = from.relative(direction).above(dy);
                if (outside(origin, target, horizontal, vertical)) {
                    continue;
                }
                Edge edge = stepEdge(level, from, target, dy);
                if (edge != null) {
                    edges.add(edge);
                }
            }
        }

        if (canBuild) {
            BlockPos above = from.above();
            if (!outside(origin, above, horizontal, vertical)) {
                Set<BlockPos> clear = new HashSet<>();
                if (clearable(level, above, clear) && clearable(level, above.above(), clear)) {
                    edges.add(new Edge(above, BUILD_COST + cost(level, clear), List.copyOf(clear), true));
                }
            }
        }
        return edges;
    }

    private static Edge stepEdge(Level level, BlockPos from, BlockPos target, int dy) {
        Set<BlockPos> clear = new HashSet<>();

        BlockPos floor = target.below();
        BlockState floorState = level.getBlockState(floor);
        if (!floorState.isFaceSturdy(level, floor, Direction.UP)) {
            return null;
        }
        if (!clearable(level, target, clear) || !clearable(level, target.above(), clear)) {
            return null;
        }
        // Climbing needs the space over the citizen's own head opened up too.
        if (dy > 0 && !clearable(level, from.above(2), clear)) {
            return null;
        }
        return new Edge(target, MOVE_COST + cost(level, clear), List.copyOf(clear), false);
    }

    /** Already open, or something the citizen is allowed to open up. */
    private static boolean clearable(Level level, BlockPos pos, Set<BlockPos> clear) {
        if (ReversibleWalk.isPassable(level, pos, null)) {
            return true;
        }
        if (!isDiggable(level, pos)) {
            return false;
        }
        clear.add(pos.immutable());
        return true;
    }

    private static int cost(Level level, Set<BlockPos> clear) {
        int total = 0;
        for (BlockPos pos : clear) {
            float hardness = Math.max(0.0F, level.getBlockState(pos).getDestroySpeed(level, pos));
            total += DIG_BASE_COST + (int) (hardness * DIG_HARDNESS_COST);
        }
        return total;
    }

    private static boolean outside(BlockPos origin, BlockPos pos, int horizontal, int vertical) {
        return Math.abs(pos.getX() - origin.getX()) > horizontal
                || Math.abs(pos.getZ() - origin.getZ()) > horizontal
                || Math.abs(pos.getY() - origin.getY()) > vertical;
    }

    private static boolean within(BlockPos pos, BlockPos goal, double arrival) {
        return pos.distSqr(goal) <= arrival * arrival;
    }

    private record Edge(BlockPos target, int cost, List<BlockPos> clear, boolean build) {
    }

    private static final class Node implements Comparable<Node> {
        private final BlockPos pos;
        private final int cost;
        private final Node parent;
        private final List<BlockPos> clear;
        private final boolean build;
        private boolean stale;

        private Node(BlockPos pos, int cost, Node parent, List<BlockPos> clear, boolean build) {
            this.pos = pos;
            this.cost = cost;
            this.parent = parent;
            this.clear = clear;
            this.build = build;
        }

        @Override
        public int compareTo(Node other) {
            return Integer.compare(this.cost, other.cost);
        }
    }
}
