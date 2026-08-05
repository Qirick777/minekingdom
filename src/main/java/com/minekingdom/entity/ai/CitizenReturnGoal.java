package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import com.minekingdom.entity.task.CitizenTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Walks a citizen back to its recorded return point.
 *
 * <p>The citizen makes its own way there and is never moved or teleported, so arriving
 * proves it could actually get back. Travelling is left to {@link CitizenTravel}, which
 * walks where it can and digs or builds where it cannot; a citizen that has mined its way
 * down into a pit has to cut a way back up, and simply asking for a path would never
 * manage it.
 */
public class CitizenReturnGoal extends Goal {
    private static final int GIVE_UP_TICKS = 3600;

    private final CitizenEntity citizen;
    private final CitizenTravel travel;
    private int elapsed;
    private double startDistance;
    /** Arrival is reported once per trip, even though the goal may tick again before it is stopped. */
    private boolean reported;

    public CitizenReturnGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.travel = new CitizenTravel(citizen, speedModifier);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return this.citizen.getTask() == CitizenTask.RETURNING && this.citizen.getReturnPoint() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse() && !this.reported;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.elapsed = 0;
        this.reported = false;
        this.startDistance = this.citizen.distanceToReturnPoint();
        this.citizen.beginReturnTrip();
        BlockPos home = this.citizen.getReturnPoint();
        if (home != null) {
            this.travel.setDestination(home, CitizenEntity.RETURN_ARRIVAL_DISTANCE);
        }
    }

    @Override
    public void stop() {
        this.travel.stop();
    }

    @Override
    public void tick() {
        if (this.reported || this.citizen.getReturnPoint() == null) {
            return;
        }

        if (this.citizen.isAtReturnPoint()) {
            this.finish(true);
            return;
        }
        if (++this.elapsed > GIVE_UP_TICKS) {
            this.finish(false);
            return;
        }

        if (this.travel.tick() == CitizenTravel.Status.ARRIVED) {
            this.finish(true);
        }
    }

    private void finish(boolean arrived) {
        this.reported = true;
        this.travel.stop();
        this.citizen.finishReturn(arrived, this.elapsed, this.startDistance, this.travel.blocksPlaced());
    }
}
