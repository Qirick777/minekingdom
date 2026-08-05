package com.minekingdom.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Walking a citizen can do and undo: one block horizontally, at most one block of rise
 * or drop, two blocks of headroom. Because every step can be walked in reverse, anything
 * reachable this way is also somewhere the citizen can come back from.
 *
 * <p>All queries take an optional position to pretend is empty, which lets callers ask
 * what the walk would look like after mining a block without touching the world.
 */
public final class ReversibleWalk {
    private ReversibleWalk() {
    }

    public static Set<BlockPos> reachable(Level level, BlockPos origin, int horizontal, int vertical,
                                          int nodeLimit, @Nullable BlockPos treatAsAir) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && visited.size() < nodeLimit) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos next = current.relative(direction).above(dy);
                    if (visited.contains(next) || outsideBox(origin, next, horizontal, vertical)) {
                        continue;
                    }
                    if (!canStandAt(level, next, treatAsAir)) {
                        continue;
                    }
                    // Climbing needs the space above the citizen's head to be clear as well.
                    if (dy > 0 && !isPassable(level, current.above(2), treatAsAir)) {
                        continue;
                    }
                    visited.add(next);
                    queue.add(next);
                    break;
                }
            }
        }
        return visited;
    }

    public static boolean canStandAt(Level level, BlockPos pos, @Nullable BlockPos treatAsAir) {
        BlockPos floor = pos.below();
        if (floor.equals(treatAsAir)) {
            return false;
        }
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            return false;
        }
        return isPassable(level, pos, treatAsAir) && isPassable(level, pos.above(), treatAsAir);
    }

    public static boolean isPassable(Level level, BlockPos pos, @Nullable BlockPos treatAsAir) {
        if (pos.equals(treatAsAir)) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isPathfindable(level, pos, PathComputationType.LAND);
    }

    /** Whether any single step leads anywhere at all from the given spot. */
    public static boolean hasAnyStep(Level level, BlockPos from, @Nullable BlockPos treatAsAir) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos next = from.relative(direction).above(dy);
                if (canStandAt(level, next, treatAsAir) && (dy <= 0 || isPassable(level, from.above(2), treatAsAir))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean outsideBox(BlockPos origin, BlockPos pos, int horizontal, int vertical) {
        return Math.abs(pos.getX() - origin.getX()) > horizontal
                || Math.abs(pos.getZ() - origin.getZ()) > horizontal
                || Math.abs(pos.getY() - origin.getY()) > vertical;
    }
}
