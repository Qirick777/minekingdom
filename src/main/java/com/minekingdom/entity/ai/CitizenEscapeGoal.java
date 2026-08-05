package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import com.minekingdom.entity.task.CitizenTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Gets a citizen that has wedged itself somewhere back to ground it came from.
 *
 * <p>Recovery is a journey like any other, so it goes through {@link CitizenTravel} towards
 * somewhere the citizen is known to have stood. That matters: a route is only ever planned
 * to a real place, so a citizen with nothing above it but sky produces no plan and simply
 * stands rather than stacking blocks upwards forever. Digging and stacking still happen
 * where a plan calls for them, and only there.
 *
 * <p>A citizen that can still break stone is left to the mining goal, and one that reports
 * no way through at all is flagged as stuck rather than left looking idle.
 */
public class CitizenEscapeGoal extends Goal {
    /** Calls to canUse without meaningful movement before a citizen counts as stuck. */
    private static final int STUCK_CHECKS = 100;
    private static final double STUCK_RADIUS = 1.5D;
    private static final int SEARCH_HORIZONTAL = 8;
    private static final int SEARCH_VERTICAL = 5;
    private static final int NODE_LIMIT = 256;
    /** A reachable area no bigger than this counts as being boxed in rather than out in the open. */
    private static final int CONFINED_NODES = 24;
    /** How far around itself a citizen looks for something it could still dig through. */
    private static final int DIGGABLE_RADIUS = 2;
    private static final int GIVE_UP_TICKS = 600;

    private final CitizenEntity citizen;
    private final CitizenTravel travel;

    @Nullable
    private BlockPos lastPos;
    private int stillChecks;
    private int lastMined = -1;
    private int elapsed;

    public CitizenEscapeGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.travel = new CitizenTravel(citizen, speedModifier);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.citizen.getTask() == CitizenTask.IDLE) {
            this.stillChecks = 0;
            return false;
        }

        // A citizen chipping away at a seam barely moves, but it is working, not stuck.
        int mined = this.citizen.getMinedBlocks();
        BlockPos pos = this.citizen.blockPosition();
        if (this.lastPos == null || mined != this.lastMined
                || this.lastPos.distSqr(pos) > STUCK_RADIUS * STUCK_RADIUS) {
            this.lastPos = pos;
            this.lastMined = mined;
            this.stillChecks = 0;
            this.citizen.setStuck(false);
            return false;
        }
        if (++this.stillChecks < STUCK_CHECKS) {
            return false;
        }

        if (!this.isBoxedIn()) {
            this.citizen.setStuck(false);
            return false;
        }
        // Being in a small space is not the same as being trapped: if there is still stone
        // to break, the mining goal gets it out.
        if (this.hasSomethingToDig()) {
            this.citizen.setStuck(false);
            return false;
        }

        BlockPos wayOut = this.wayOut();
        this.citizen.setStuck(wayOut == null);
        return wayOut != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.citizen.getTask() != CitizenTask.IDLE && this.elapsed < GIVE_UP_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.elapsed = 0;
        BlockPos wayOut = this.wayOut();
        if (wayOut != null) {
            this.travel.setDestination(wayOut, 1.5D);
        }
    }

    @Override
    public void stop() {
        this.travel.stop();
        this.stillChecks = 0;
        this.lastPos = null;
    }

    @Override
    public void tick() {
        this.elapsed++;
        CitizenTravel.Status status = this.travel.tick();
        if (status == CitizenTravel.Status.ARRIVED) {
            this.citizen.setStuck(false);
            this.elapsed = GIVE_UP_TICKS;
        } else if (status == CitizenTravel.Status.STUCK) {
            // Nothing can be done from here; say so and let other goals have a turn.
            this.citizen.setStuck(true);
            this.elapsed = GIVE_UP_TICKS;
        }
    }

    /** Ground the citizen is known to have stood on, which is somewhere worth aiming for. */
    @Nullable
    private BlockPos wayOut() {
        BlockPos anchor = this.citizen.getPathMemory()
                .oldestWithin(this.citizen.blockPosition(), SEARCH_HORIZONTAL * 2, SEARCH_VERTICAL * 2);
        return anchor != null ? anchor : this.citizen.getReturnPoint();
    }

    /** Any block close by that the citizen is allowed to break its way through. */
    private boolean hasSomethingToDig() {
        BlockPos feet = this.citizen.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -DIGGABLE_RADIUS; dx <= DIGGABLE_RADIUS; dx++) {
            for (int dy = -DIGGABLE_RADIUS; dy <= DIGGABLE_RADIUS; dy++) {
                for (int dz = -DIGGABLE_RADIUS; dz <= DIGGABLE_RADIUS; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (dy < 0 && dx == 0 && dz == 0) {
                        continue;   // its own footing is off limits anyway
                    }
                    if (CitizenMiningGoal.isBreakableSafely(this.citizen.level(), cursor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Confined to a small pocket with nothing higher than the citizen within walking reach. */
    private boolean isBoxedIn() {
        BlockPos feet = this.citizen.blockPosition();
        Set<BlockPos> reachable = ReversibleWalk.reachable(this.citizen.level(), feet,
                SEARCH_HORIZONTAL, SEARCH_VERTICAL, NODE_LIMIT, null);
        if (reachable.size() > CONFINED_NODES) {
            return false;
        }
        for (BlockPos pos : reachable) {
            if (pos.getY() > feet.getY()) {
                return false;
            }
        }
        return true;
    }
}
