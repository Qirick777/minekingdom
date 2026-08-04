package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Mines the nearest exposed stone or ore the citizen can safely get to and back from.
 *
 * <p>The rules, in the order they are applied while picking a block:
 * <ol>
 *   <li>only exposed stone/ore counts as a target, nearest first;</li>
 *   <li>never the block underfoot, nor anything in the column the citizen stands on;</li>
 *   <li>never a block that would let liquid through or drop sand/gravel from above;</li>
 *   <li>the citizen must be able to walk to a spot beside it using steps of at most
 *       one block up or down with two blocks of headroom, which makes every step
 *       reversible and so the walk is always one it can come back from;</li>
 *   <li>if nothing qualifies this goal stands down and the stroll goal takes over.</li>
 * </ol>
 */
public class CitizenMiningGoal extends Goal {
    private static final int SEARCH_HORIZONTAL = 6;
    private static final int SEARCH_VERTICAL = 3;
    private static final int WALK_NODE_LIMIT = 768;
    private static final double MINING_REACH = 4.5D;
    private static final int RESCAN_INTERVAL = 20;
    private static final int APPROACH_TIMEOUT = 200;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable
    private BlockPos targetBlock;
    @Nullable
    private BlockPos standSpot;
    /** Skipped once on the next search so an unreachable block cannot be re-picked forever. */
    @Nullable
    private BlockPos skipTarget;
    private float destroyProgress;
    private int lastBreakStage = -1;
    private int rescanCooldown;
    private int approachTicks;

    public CitizenMiningGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Blocks citizens treat as worth mining. Public so future jobs can reuse the same notion. */
    public static boolean isMineable(BlockState state) {
        return state.is(Tags.Blocks.ORES)
                || state.is(Tags.Blocks.STONE)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER);
    }

    @Override
    public boolean canUse() {
        if (!this.citizen.isMining()) {
            return false;
        }
        if (this.rescanCooldown > 0) {
            this.rescanCooldown--;
            return false;
        }
        return this.findTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return this.citizen.isMining() && this.targetBlock != null && this.isStillValid(this.targetBlock);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.approachTicks = 0;
        this.moveToStandSpot();
    }

    @Override
    public void stop() {
        this.clearBreakProgress();
        this.targetBlock = null;
        this.standSpot = null;
        this.citizen.getNavigation().stop();
        this.rescanCooldown = RESCAN_INTERVAL;
    }

    @Override
    public void tick() {
        BlockPos target = this.targetBlock;
        if (target == null) {
            return;
        }

        Vec3 center = Vec3.atCenterOf(target);
        this.citizen.getLookControl().setLookAt(center.x, center.y, center.z);

        if (this.withinReach(target)) {
            this.citizen.getNavigation().stop();
            this.mineTick(target);
            return;
        }

        if (++this.approachTicks > APPROACH_TIMEOUT) {
            this.abandonTarget();
            return;
        }
        if (this.citizen.getNavigation().isDone()) {
            this.moveToStandSpot();
        }
    }

    private void moveToStandSpot() {
        BlockPos spot = this.standSpot;
        if (spot == null) {
            this.abandonTarget();
            return;
        }
        boolean moving = this.citizen.getNavigation()
                .moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, this.speedModifier);
        if (!moving && !this.withinReach(this.targetBlock)) {
            this.abandonTarget();
        }
    }

    private void abandonTarget() {
        this.skipTarget = this.targetBlock;
        this.clearBreakProgress();
        this.targetBlock = null;
        this.standSpot = null;
    }

    private void mineTick(BlockPos target) {
        Level level = this.citizen.level();
        BlockState state = level.getBlockState(target);

        this.citizen.swing(InteractionHand.MAIN_HAND);
        this.destroyProgress += this.citizen.getDestroyProgressPerTick(state, target);

        int stage = Mth.clamp((int) (this.destroyProgress * 10.0F), 0, 9);
        if (stage != this.lastBreakStage) {
            level.destroyBlockProgress(this.citizen.getId(), target, stage);
            this.lastBreakStage = stage;
        }

        if (this.destroyProgress >= 1.0F) {
            this.clearBreakProgress();
            if (level.destroyBlock(target, true, this.citizen, 512)) {
                this.citizen.recordMinedBlock();
            }
            this.targetBlock = null;
            this.standSpot = null;
            this.skipTarget = null;
        }
    }

    private void clearBreakProgress() {
        if (this.lastBreakStage != -1 && this.targetBlock != null) {
            this.citizen.level().destroyBlockProgress(this.citizen.getId(), this.targetBlock, -1);
        }
        this.lastBreakStage = -1;
        this.destroyProgress = 0.0F;
    }

    private boolean withinReach(@Nullable BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return this.citizen.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= MINING_REACH * MINING_REACH;
    }

    // ---------------------------------------------------------------- target search

    private boolean findTarget() {
        this.rescanCooldown = RESCAN_INTERVAL;

        BlockPos origin = this.citizen.blockPosition();
        Set<BlockPos> reachable = this.walkableSpots(origin);
        BlockPos skipped = this.skipTarget;
        this.skipTarget = null;

        BlockPos bestBlock = null;
        BlockPos bestSpot = null;
        double bestDistance = Double.MAX_VALUE;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SEARCH_HORIZONTAL; dx <= SEARCH_HORIZONTAL; dx++) {
            for (int dy = -SEARCH_VERTICAL; dy <= SEARCH_VERTICAL; dy++) {
                for (int dz = -SEARCH_HORIZONTAL; dz <= SEARCH_HORIZONTAL; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    double distance = cursor.distSqr(origin);
                    if (distance >= bestDistance || cursor.equals(skipped)) {
                        continue;
                    }
                    if (!this.isMineableTarget(cursor) || !this.isExposed(cursor)) {
                        continue;
                    }
                    BlockPos spot = this.findStandSpot(cursor, reachable);
                    if (spot == null) {
                        continue;
                    }
                    bestBlock = cursor.immutable();
                    bestSpot = spot;
                    bestDistance = distance;
                }
            }
        }

        this.targetBlock = bestBlock;
        this.standSpot = bestSpot;
        return bestBlock != null;
    }

    /** Everything except exposure, which only ever increases once mining starts. */
    private boolean isStillValid(BlockPos pos) {
        return this.isMineableTarget(pos);
    }

    private boolean isMineableTarget(BlockPos pos) {
        Level level = this.citizen.level();
        BlockState state = level.getBlockState(pos);
        if (!isMineable(state) || state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        return !this.isOwnFooting(pos) && !this.wouldExposeLiquid(pos) && !this.wouldDropFallingBlock(pos);
    }

    private boolean isExposed(BlockPos pos) {
        Level level = this.citizen.level();
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** The block underfoot and the whole column below it — digging those drops the citizen. */
    private boolean isOwnFooting(BlockPos pos) {
        AABB box = this.citizen.getBoundingBox();
        if (pos.getY() >= Mth.floor(box.minY)) {
            return false;
        }
        return pos.getX() >= Mth.floor(box.minX) && pos.getX() <= Mth.floor(box.maxX - 1.0E-7D)
                && pos.getZ() >= Mth.floor(box.minZ) && pos.getZ() <= Mth.floor(box.maxZ - 1.0E-7D);
    }

    private boolean wouldExposeLiquid(BlockPos pos) {
        Level level = this.citizen.level();
        if (!level.getFluidState(pos).isEmpty()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (!level.getFluidState(pos.relative(direction)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean wouldDropFallingBlock(BlockPos pos) {
        return this.citizen.level().getBlockState(pos.above()).getBlock() instanceof FallingBlock;
    }

    /**
     * Standing positions beside the block: level with it, one below so it sits at head
     * height, or one above so a citizen on top of a flat expanse can open it up beside
     * itself. Without that last case a citizen standing on a stone plain has nothing it
     * is allowed to mine, since everything at its own level is under its feet.
     */
    @Nullable
    private BlockPos findStandSpot(BlockPos target, Set<BlockPos> reachable) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos origin = this.citizen.blockPosition();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy : new int[]{0, -1, 1}) {
                BlockPos spot = target.relative(direction).above(dy);
                if (!reachable.contains(spot)) {
                    continue;
                }
                double distance = spot.distSqr(origin);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = spot;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- reversible walk

    /**
     * Flood fills the standing positions the citizen can walk to. Every step is one
     * block horizontally with at most one block of rise or drop, so each step can be
     * walked in reverse and the citizen can always get back.
     */
    private Set<BlockPos> walkableSpots(BlockPos origin) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && visited.size() < WALK_NODE_LIMIT) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos next = current.relative(direction).above(dy);
                    if (!this.withinSearchBox(origin, next) || visited.contains(next)) {
                        continue;
                    }
                    if (!this.canStandAt(next)) {
                        continue;
                    }
                    // Climbing needs the space above the citizen's head to be clear as well.
                    if (dy > 0 && !this.isPassable(current.above(2))) {
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

    private boolean withinSearchBox(BlockPos origin, BlockPos pos) {
        return Math.abs(pos.getX() - origin.getX()) <= SEARCH_HORIZONTAL + 1
                && Math.abs(pos.getZ() - origin.getZ()) <= SEARCH_HORIZONTAL + 1
                && Math.abs(pos.getY() - origin.getY()) <= SEARCH_VERTICAL + 1;
    }

    private boolean canStandAt(BlockPos pos) {
        Level level = this.citizen.level();
        BlockPos floor = pos.below();
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            return false;
        }
        return this.isPassable(pos) && this.isPassable(pos.above());
    }

    private boolean isPassable(BlockPos pos) {
        Level level = this.citizen.level();
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isPathfindable(level, pos, PathComputationType.LAND);
    }
}
