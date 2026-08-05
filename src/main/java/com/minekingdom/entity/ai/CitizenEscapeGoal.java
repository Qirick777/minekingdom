package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import com.minekingdom.entity.task.CitizenTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Last resort for a citizen that has walled itself in: jump and drop a block underfoot to
 * climb out, the way a player pillars up.
 *
 * <p>This is for a citizen with nowhere left to go and nothing left to dig. A citizen that
 * can still break stone is left to the mining goal, and one that has somewhere to be cuts
 * its own way there through {@link CitizenTravel}; only a citizen boxed in with neither
 * option gets here. With an empty inventory it cannot climb at all, which is why the stone
 * a citizen breaks and picks up matters.
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
    private static final int MAX_PILLAR = 8;
    /** How far around itself a citizen looks for something it could still dig through. */
    private static final int DIGGABLE_RADIUS = 2;

    private final CitizenEntity citizen;
    private final CitizenBlockBreaker breaker;
    private final CitizenPillarBuilder pillar;

    @Nullable
    private BlockPos lastPos;
    private int stillChecks;
    private int lastMined = -1;

    public CitizenEscapeGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.breaker = new CitizenBlockBreaker(citizen);
        this.pillar = new CitizenPillarBuilder(citizen);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
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
        // to break, the mining goal gets it out, and pillaring would only waste blocks.
        if (this.hasSomethingToDig()) {
            this.citizen.setStuck(false);
            return false;
        }

        // Either build upwards, or open the ceiling that is stopping the climb.
        boolean canAct = this.pillar.hasHeadroom() ? this.pillar.canBuild() : this.ceilingIsBreakable();
        this.citizen.setStuck(!canAct);
        return canAct;
    }

    /** Any block close by that the citizen is allowed to break its way through. */
    private boolean hasSomethingToDig() {
        Level level = this.citizen.level();
        BlockPos feet = this.citizen.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -DIGGABLE_RADIUS; dx <= DIGGABLE_RADIUS; dx++) {
            for (int dy = -DIGGABLE_RADIUS; dy <= DIGGABLE_RADIUS; dy++) {
                for (int dz = -DIGGABLE_RADIUS; dz <= DIGGABLE_RADIUS; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (dy < 0 && dx == 0 && dz == 0) {
                        continue;   // its own footing is off limits anyway
                    }
                    if (CitizenMiningGoal.isBreakableSafely(level, cursor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.pillar.placedCount() >= MAX_PILLAR || this.citizen.getTask() == CitizenTask.IDLE) {
            return false;
        }
        return this.pillar.hasHeadroom() ? this.pillar.canBuild() : this.ceilingIsBreakable();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.pillar.reset();
        this.citizen.getNavigation().stop();
    }

    @Override
    public void stop() {
        this.breaker.reset();
        this.pillar.reset();
        this.stillChecks = 0;
        this.lastPos = null;
    }

    @Override
    public void tick() {
        if (this.pillar.isMidJump()) {
            this.pillar.tick();
            return;
        }
        // A blocked ceiling has to come down before there is anywhere to climb to.
        if (!this.pillar.hasHeadroom()) {
            BlockPos ceiling = this.citizen.blockPosition().above(2);
            this.citizen.getLookControl().setLookAt(ceiling.getX() + 0.5D, ceiling.getY() + 0.5D, ceiling.getZ() + 0.5D);
            this.breaker.advance(ceiling);
            return;
        }

        this.pillar.tick();
        // Once there is a way up again there is no reason to keep building.
        if (!this.isBoxedIn()) {
            this.citizen.setStuck(false);
        }
    }




    /** The block right above the citizen's head, when it is one a citizen may take out. */
    private boolean ceilingIsBreakable() {
        BlockPos ceiling = this.citizen.blockPosition().above(2);
        return CitizenMiningGoal.isBreakableSafely(this.citizen.level(), ceiling);
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
