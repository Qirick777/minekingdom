package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
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
 *   <li>never a block whose removal would strand the citizen away from ground it has
 *       recently stood on, since mining changes the very terrain rule four is judged on;</li>
 *   <li>if nothing qualifies this goal stands down and the stroll goal takes over.</li>
 * </ol>
 */
public class CitizenMiningGoal extends Goal {
    private static final int SEARCH_HORIZONTAL = 6;
    private static final int SEARCH_VERTICAL = 3;
    private static final int WALK_NODE_LIMIT = 768;
    /** Short enough that citizens mine what they are standing next to, not across a room. */
    private static final double MINING_REACH = 3.0D;
    private static final int RESCAN_INTERVAL = 20;
    private static final int APPROACH_TIMEOUT = 200;
    /** Caps the ray casts spent looking for a visible block in one search. */
    private static final int MAX_SIGHT_CHECKS = 16;
    private static final int SAFETY_HORIZONTAL = 8;
    private static final int SAFETY_VERTICAL = 5;
    private static final int SAFETY_NODE_LIMIT = 512;

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

        // Both conditions matter: close enough, and actually looking at it. Reach alone let
        // citizens break blocks on the far side of a wall without ever walking round.
        if (this.withinReach(target) && this.canSeeFrom(this.citizen.getEyePosition(), target)) {
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
            // Line up the next block straight away. Ending the goal here instead left the
            // citizen standing idle through the rescan delay after every single block.
            if (this.findTarget()) {
                this.approachTicks = 0;
            }
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
        this.targetBlock = null;
        this.standSpot = null;

        BlockPos origin = this.citizen.blockPosition();
        Set<BlockPos> reachable = ReversibleWalk.reachable(this.citizen.level(), origin,
                SEARCH_HORIZONTAL + 1, SEARCH_VERTICAL + 1, WALK_NODE_LIMIT, null);
        BlockPos skipped = this.skipTarget;
        this.skipTarget = null;

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SEARCH_HORIZONTAL; dx <= SEARCH_HORIZONTAL; dx++) {
            for (int dy = -SEARCH_VERTICAL; dy <= SEARCH_VERTICAL; dy++) {
                for (int dz = -SEARCH_HORIZONTAL; dz <= SEARCH_HORIZONTAL; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (cursor.equals(skipped) || !this.isMineableTarget(cursor) || !this.isExposed(cursor)) {
                        continue;
                    }
                    candidates.add(cursor.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.distSqr(origin)));

        int sightChecks = 0;
        for (BlockPos candidate : candidates) {
            BlockPos spot = this.findStandSpot(candidate, reachable);
            if (spot == null) {
                continue;
            }
            if (++sightChecks > MAX_SIGHT_CHECKS) {
                break;
            }
            // Nearest first, but only if the citizen could actually see it from where it
            // would stand. Without this citizens reach straight through walls.
            if (!this.canSeeFromSpot(spot, candidate)) {
                continue;
            }
            if (!this.keepsEscapeRoute(spot, candidate)) {
                continue;
            }
            this.targetBlock = candidate;
            this.standSpot = spot;
            return true;
        }
        return false;
    }

    private boolean canSeeFromSpot(BlockPos spot, BlockPos target) {
        Vec3 eye = new Vec3(spot.getX() + 0.5D, spot.getY() + this.citizen.getEyeHeight(), spot.getZ() + 0.5D);
        return this.canSeeFrom(eye, target);
    }

    /** Whether an exposed face of the block is in plain sight from the given eye position. */
    private boolean canSeeFrom(Vec3 eye, BlockPos target) {
        Level level = this.citizen.level();
        Vec3 center = Vec3.atCenterOf(target);

        for (Direction direction : Direction.values()) {
            if (!level.getBlockState(target.relative(direction)).isAir()) {
                continue;
            }
            // Aim just past the face into the open air beside it, so an unobstructed
            // line of sight registers as hitting nothing at all.
            Vec3 aim = center.add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.55D));
            BlockHitResult hit = level.clip(new ClipContext(eye, aim,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.citizen));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
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

    // ---------------------------------------------------------------- staying un-trapped

    /**
     * Whether the citizen could still walk out after taking this block. Mining changes the
     * ground the reversible-walk rule is judged against, so without this a citizen can dig
     * away the very step it needs to climb back up.
     */
    private boolean keepsEscapeRoute(BlockPos spot, BlockPos target) {
        Level level = this.citizen.level();
        // Cheap first pass: taking the block must not leave the standing spot walled in.
        if (!ReversibleWalk.hasAnyStep(level, spot, target)) {
            return false;
        }

        BlockPos anchor = this.findAnchor(spot, target);
        if (anchor == null) {
            return true;
        }
        return ReversibleWalk.reachable(level, spot, SAFETY_HORIZONTAL, SAFETY_VERTICAL,
                SAFETY_NODE_LIMIT, target).contains(anchor);
    }

    /**
     * The oldest ground the citizen is known to have stood on that is still within reach of
     * the safety check. Older is better: it is further back along the way the citizen came.
     */
    @Nullable
    private BlockPos findAnchor(BlockPos spot, BlockPos target) {
        Level level = this.citizen.level();
        for (BlockPos crumb : this.citizen.getBreadcrumbs()) {
            if (crumb.equals(spot)) {
                continue;
            }
            if (Math.abs(crumb.getX() - spot.getX()) > SAFETY_HORIZONTAL
                    || Math.abs(crumb.getZ() - spot.getZ()) > SAFETY_HORIZONTAL
                    || Math.abs(crumb.getY() - spot.getY()) > SAFETY_VERTICAL) {
                continue;
            }
            if (ReversibleWalk.canStandAt(level, crumb, target)) {
                return crumb;
            }
        }
        return null;
    }
}
