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
 *
 * <p>Getting stuck ends the attempt, not the trip. The goal steps aside for a moment so
 * anything else can shift the citizen, then comes back and tries again from wherever it
 * now stands, because a route that could not be found from one spot is often there from
 * one block over. Only the overall deadline ends the trip for good.
 */
public class CitizenReturnGoal extends Goal {
    private static final int GIVE_UP_TICKS = 3600;
    /**
     * Holding on with nothing to do keeps every other goal shut out, which is what left
     * citizens standing perfectly still until something hit them. Long enough to sit
     * through three attempts at a fresh plan, and no longer.
     */
    private static final int STUCK_PATIENCE = 200;
    /** How long the goal keeps out of the way between attempts. */
    private static final int RETRY_DELAY = 100;

    private final CitizenEntity citizen;
    private final CitizenTravel travel;
    private double startDistance;
    private int stuckTicks;
    /** Arrival is reported once per trip, even though the goal may tick again before it is stopped. */
    private boolean reported;
    /** A trip spans every attempt, so stepping aside and coming back does not reset the clock. */
    private boolean inTrip;
    private long tripStart;
    private long retryAfter;

    public CitizenReturnGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.travel = new CitizenTravel(citizen, speedModifier, "return");
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return this.citizen.getTask() == CitizenTask.RETURNING
                && this.citizen.getReturnPoint() != null
                && this.citizen.level().getGameTime() >= this.retryAfter;
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
        this.stuckTicks = 0;
        if (!this.inTrip) {
            this.inTrip = true;
            this.reported = false;
            this.tripStart = this.citizen.level().getGameTime();
            this.startDistance = this.citizen.distanceToReturnPoint();
            this.citizen.beginReturnTrip();
        }
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
        if (this.elapsed() > GIVE_UP_TICKS) {
            this.finish(false);
            return;
        }

        CitizenTravel.Status status = this.travel.tick();
        if (status == CitizenTravel.Status.ARRIVED) {
            this.finish(true);
            return;
        }
        if (status == CitizenTravel.Status.STUCK) {
            if (++this.stuckTicks > STUCK_PATIENCE) {
                // Say so, let go, and come back to it. Ending the trip here is what left a
                // citizen idle and untouched by every goal for the rest of its life.
                this.citizen.setStuck(true);
                this.stuckTicks = 0;
                this.retryAfter = this.citizen.level().getGameTime() + RETRY_DELAY;
                this.travel.stop();
            }
            return;
        }
        this.stuckTicks = 0;
    }

    private int elapsed() {
        return (int) (this.citizen.level().getGameTime() - this.tripStart);
    }

    private void finish(boolean arrived) {
        this.reported = true;
        this.inTrip = false;
        this.travel.stop();
        this.citizen.finishReturn(arrived, this.elapsed(), this.startDistance, this.travel.blocksPlaced());
    }
}
