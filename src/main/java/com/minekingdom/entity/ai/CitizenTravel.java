package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Gets a citizen to a block, walking where it can and cutting a way where it cannot.
 *
 * <p>Written against a destination rather than any particular errand, so returning home,
 * heading to a work site or anything a later job needs can drive the same thing.
 *
 * <p>Walking comes first and is checked properly: if pathfinding reports a route that
 * actually reaches the destination, the citizen simply walks it. Only when it does not does
 * {@link CitizenRoutePlanner} work out a way through, and the citizen follows that plan a
 * leg at a time, digging or stacking as each leg calls for. The plan is dropped and walking
 * resumes the moment an ordinary route appears, so a citizen never keeps stacking blocks
 * once there is a way out.
 */
public class CitizenTravel {
    public enum Status {
        MOVING,
        ARRIVED,
        /** Cannot walk, dig or build any closer. */
        STUCK
    }

    private static final int REPATH_INTERVAL = 20;
    /** Inside this range steering beats pathfinding, which stops a block short. */
    private static final double CLOSE_RANGE = 4.0D;
    private static final int PLAN_HORIZONTAL = 8;
    private static final int PLAN_VERTICAL = 6;
    private static final int PLAN_NODE_LIMIT = 3000;
    private static final int REPLAN_INTERVAL = 60;
    private static final int LEG_TIMEOUT = 120;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private final CitizenBlockBreaker breaker;
    private final CitizenPillarBuilder pillar;

    @Nullable
    private BlockPos destination;
    private double arrivalDistance = 1.5D;
    private int repathCooldown;
    private boolean walkable;

    private List<CitizenRoutePlanner.Step> plan = List.of();
    private int leg;
    private int legTicks;
    private int replanCooldown;

    public CitizenTravel(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.breaker = new CitizenBlockBreaker(citizen);
        this.pillar = new CitizenPillarBuilder(citizen);
    }

    public void setDestination(BlockPos destination, double arrivalDistance) {
        this.destination = destination.immutable();
        this.arrivalDistance = arrivalDistance;
        this.repathCooldown = 0;
        this.replanCooldown = 0;
        this.walkable = false;
        this.dropPlan();
        this.breaker.reset();
        this.pillar.reset();
    }

    public void stop() {
        this.breaker.reset();
        this.pillar.reset();
        this.dropPlan();
        this.citizen.getNavigation().stop();
    }

    /** Blocks stacked up on this trip, which is a cost worth reporting rather than hiding. */
    public int blocksPlaced() {
        return this.pillar.placedCount();
    }

    public Status tick() {
        BlockPos target = this.destination;
        if (target == null) {
            return Status.STUCK;
        }

        Vec3 centre = Vec3.atCenterOf(target);
        double distance = this.citizen.position().distanceTo(new Vec3(centre.x, target.getY(), centre.z));
        if (distance <= this.arrivalDistance) {
            return Status.ARRIVED;
        }
        this.citizen.getLookControl().setLookAt(centre.x, centre.y, centre.z);

        // A climb already under way is seen through before anything is re-examined.
        if (this.pillar.isMidJump()) {
            this.pillar.tick();
            return Status.MOVING;
        }

        if (this.replanCooldown > 0) {
            this.replanCooldown--;
        }

        // Walking wins whenever it genuinely gets there. Checked on a cooldown because
        // working out a path is not free, and the answer does not change tick to tick.
        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            Path path = this.citizen.getNavigation().createPath(target, 1);
            this.walkable = path != null && path.canReach();
            if (this.walkable) {
                this.dropPlan();
                this.breaker.reset();
                this.citizen.getNavigation().moveTo(path, this.speedModifier);
            }
        }

        if (this.walkable) {
            // Pathfinding calls an adjacent block close enough and stops, so steer the last bit.
            if (distance < CLOSE_RANGE) {
                this.citizen.getMoveControl().setWantedPosition(centre.x, target.getY(), centre.z, this.speedModifier);
            }
            return Status.MOVING;
        }

        return this.followPlan(target);
    }

    private Status followPlan(BlockPos target) {
        if (this.plan.isEmpty() || this.leg >= this.plan.size()) {
            if (this.replanCooldown > 0) {
                return Status.MOVING;
            }
            this.replanCooldown = REPLAN_INTERVAL;
            this.plan = CitizenRoutePlanner.plan(this.citizen.level(), this.citizen.blockPosition(), target,
                    this.arrivalDistance, PLAN_HORIZONTAL, PLAN_VERTICAL, PLAN_NODE_LIMIT, this.pillar.canBuild());
            this.leg = 0;
            this.legTicks = 0;
            if (this.plan.isEmpty()) {
                return Status.STUCK;
            }
        }

        CitizenRoutePlanner.Step step = this.plan.get(this.leg);
        Level level = this.citizen.level();

        // Clear whatever this leg needs out of the way first.
        for (BlockPos blocking : step.clear()) {
            if (!ReversibleWalk.isPassable(level, blocking, null)) {
                this.citizen.getNavigation().stop();
                if (!CitizenRoutePlanner.isDiggable(level, blocking)) {
                    this.dropPlan();
                    return Status.MOVING;
                }
                this.breaker.advance(blocking);
                return Status.MOVING;
            }
        }

        if (step.buildUnderfoot()) {
            this.citizen.getNavigation().stop();
            if (!this.pillar.tick()) {
                this.dropPlan();
            }
            return Status.MOVING;
        }

        if (this.citizen.blockPosition().equals(step.stand())) {
            this.leg++;
            this.legTicks = 0;
            return Status.MOVING;
        }

        if (++this.legTicks > LEG_TIMEOUT) {
            this.dropPlan();
            return Status.MOVING;
        }

        BlockPos stand = step.stand();
        this.citizen.getMoveControl().setWantedPosition(stand.getX() + 0.5D, stand.getY(),
                stand.getZ() + 0.5D, this.speedModifier);
        return Status.MOVING;
    }

    private void dropPlan() {
        this.plan = List.of();
        this.leg = 0;
        this.legTicks = 0;
    }
}
