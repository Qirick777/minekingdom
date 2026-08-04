package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import com.minekingdom.entity.task.CitizenTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Walks a citizen back to its recorded return point.
 *
 * <p>The citizen only ever pathfinds; it is never moved or teleported directly, so
 * reaching the return point proves it could actually walk the route back. The path
 * is reissued periodically because a single request only reaches as far as the
 * citizen's follow range, and a long way home is covered a leg at a time.
 */
public class CitizenReturnGoal extends Goal {
    private static final int REPATH_INTERVAL = 20;
    private static final int GIVE_UP_TICKS = 2400;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private int elapsed;
    private int repathCooldown;
    private double startDistance;
    /** Arrival is reported once per trip, even though the goal may tick again before it is stopped. */
    private boolean reported;

    public CitizenReturnGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.citizen.getTask() == CitizenTask.RETURNING && this.citizen.getReturnPoint() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.elapsed = 0;
        this.repathCooldown = 0;
        this.reported = false;
        this.startDistance = this.citizen.distanceToReturnPoint();
    }

    @Override
    public void stop() {
        this.citizen.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos home = this.citizen.getReturnPoint();
        if (home == null || this.reported) {
            return;
        }

        if (this.citizen.isAtReturnPoint()) {
            this.reported = true;
            this.citizen.getNavigation().stop();
            this.citizen.finishReturn(true, this.elapsed, this.startDistance);
            return;
        }

        if (++this.elapsed > GIVE_UP_TICKS) {
            this.reported = true;
            this.citizen.finishReturn(false, this.elapsed, this.startDistance);
            return;
        }

        Vec3 center = Vec3.atCenterOf(home);
        this.citizen.getLookControl().setLookAt(center.x, center.y, center.z);

        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.citizen.getNavigation().moveTo(center.x, home.getY(), center.z, this.speedModifier);
        }
    }
}
