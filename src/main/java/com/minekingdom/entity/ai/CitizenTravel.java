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
    /**
     * Ticks of no movement, no block broken and no block placed before travel is called off.
     * Comfortably longer than the slowest thing a citizen can legitimately stand still for,
     * which is a deepslate ore at 135 ticks bare-handed.
     */
    private static final int STALL_TICKS = 200;
    /**
     * How long walking is given to actually move the citizen before its promise is treated
     * as empty. Pathfinding reports it can reach places a citizen cannot physically walk to,
     * and believing that leaves one standing still with a perfectly good dig plan unused.
     */
    private static final int WALK_STALL_TICKS = 60;
    /** How long walking stays out of favour once it has failed to deliver. */
    private static final int WALK_BAN_TICKS = 200;
    /**
     * How far a citizen has to get from where it last made headway for that to count as
     * having gone somewhere. Measured as a distance rather than a change of block: a citizen
     * shuffled back and forth across a block boundary by pathfinding looks busy every tick
     * while covering no ground at all, and that is precisely the case worth catching.
     */
    private static final double PROGRESS_DISTANCE = 2.0D;

    private final CitizenEntity citizen;
    private final double speedModifier;
    /** Which goal this belongs to, so a report can say who is driving. */
    private final String driver;
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

    @Nullable
    private Vec3 walkAnchor;
    private int walkStill;

    @Nullable
    private Vec3 progressAnchor;
    private int lastMined;
    private int lastPlaced;
    private int lastLeg;
    private int stallTicks;

    public CitizenTravel(CitizenEntity citizen, double speedModifier, String driver) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.driver = driver;
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
        this.progressAnchor = null;
        this.stallTicks = 0;
        this.walkAnchor = null;
        this.walkStill = 0;
        this.citizen.getJourney().begin(this.driver, this.destination);
    }

    public void stop() {
        this.breaker.reset();
        this.pillar.reset();
        this.dropPlan();
        this.citizen.getNavigation().stop();
        this.citizen.getJourney().released();
    }

    /** Blocks stacked up on this trip, which is a cost worth reporting rather than hiding. */
    public int blocksPlaced() {
        return this.pillar.placedCount();
    }

    /**
     * A one-line account of how a citizen would try to reach somewhere: whether ordinary
     * pathfinding claims it can, whether it has anything to stack up on, and what the route
     * planner comes back with. Read-only, and the answer to "it is just standing there".
     */
    public static String describeRoute(CitizenEntity citizen, BlockPos target, double arrival) {
        BlockPos live = citizen.getJourney().target();
        if (live != null) {
            // Whatever is actually driving decides where the citizen is going. Reporting a
            // route to the return point while the escape goal heads for a breadcrumb
            // describes a journey nobody is making.
            target = live;
        }
        Path path = citizen.getNavigation().createPath(target, 1);
        boolean canBuild = new CitizenPillarBuilder(citizen).canBuild();
        List<CitizenRoutePlanner.Step> plan = CitizenRoutePlanner.plan(citizen.level(), citizen.blockPosition(),
                target, arrival, PLAN_HORIZONTAL, PLAN_VERTICAL, PLAN_NODE_LIMIT, canBuild,
                citizen::placedOwnBlock);

        StringBuilder legs = new StringBuilder();
        for (int i = 0; i < Math.min(5, plan.size()); i++) {
            CitizenRoutePlanner.Step step = plan.get(i);
            legs.append(' ').append(step.stand().toShortString());
            if (step.buildUnderfoot()) {
                legs.append("+build");
            }
            if (!step.clear().isEmpty()) {
                legs.append("+dig").append(step.clear().size());
            }
        }
        return "walkable=" + (path != null && path.canReach())
                + " canBuild=" + canBuild
                + " legs=" + plan.size() + legs;
    }

    public Status tick() {
        BlockPos target = this.destination;
        if (target == null) {
            return Status.STUCK;
        }

        Vec3 centre = Vec3.atCenterOf(target);
        double distance = this.citizen.position().distanceTo(new Vec3(centre.x, target.getY(), centre.z));
        if (distance <= this.arrivalDistance) {
            this.report(CitizenJourney.Mode.ARRIVED);
            return Status.ARRIVED;
        }
        this.citizen.getLookControl().setLookAt(centre.x, centre.y, centre.z);

        // Whether the citizen is getting anywhere is judged on what it has actually done,
        // not on what this class reports. Saying MOVING while standing perfectly still is
        // exactly how a citizen used to stare at its home for three minutes without ever
        // being counted as stuck.
        if (this.stalled()) {
            this.report(CitizenJourney.Mode.STALLED);
            return Status.STUCK;
        }

        // A climb already under way is seen through before anything is re-examined.
        if (this.pillar.isMidJump()) {
            this.pillar.tick();
            this.report(CitizenJourney.Mode.BUILDING);
            return Status.MOVING;
        }

        if (this.replanCooldown > 0) {
            this.replanCooldown--;
        }

        // Walking wins whenever it genuinely gets there. Checked on a cooldown because
        // working out a path is not free, and the answer does not change tick to tick.
        if (this.citizen.isWalkingBanned()) {
            this.walkable = false;
        } else if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            Path path = this.citizen.getNavigation().createPath(target, 1);
            boolean reaches = path != null && path.canReach();
            if (reaches) {
                // Only a fresh turn to walking starts the clock again. Restarting it on
                // every re-check let a citizen re-promise itself the same walk every twenty
                // ticks, so the count of how long it had stood still never got anywhere.
                if (!this.walkable) {
                    this.walkAnchor = null;
                    this.walkStill = 0;
                }
                this.dropPlan();
                this.breaker.reset();
                this.citizen.getNavigation().moveTo(path, this.speedModifier);
            }
            this.walkable = reaches;
        }

        if (this.walkable) {
            Vec3 now = this.citizen.position();
            if (this.walkAnchor == null || now.distanceToSqr(this.walkAnchor) >= PROGRESS_DISTANCE * PROGRESS_DISTANCE) {
                this.walkAnchor = now;
                this.walkStill = 0;
            } else if (++this.walkStill > WALK_STALL_TICKS) {
                // Pathfinding said it could get there, and then the citizen covered no
                // ground. Asking again gets the same answer for as long as the ground stays
                // as it is, so walking is put aside and the dig plan gets its turn.
                this.citizen.banWalking(WALK_BAN_TICKS);
                this.citizen.getJourney().countWalkBan();
                this.walkable = false;
                this.walkStill = 0;
                this.citizen.getNavigation().stop();
                return this.followPlan(target);
            }
            // Pathfinding calls an adjacent block close enough and stops, so steer the last bit.
            if (distance < CLOSE_RANGE) {
                this.citizen.getMoveControl().setWantedPosition(centre.x, target.getY(), centre.z, this.speedModifier);
            }
            this.report(CitizenJourney.Mode.WALKING);
            return Status.MOVING;
        }

        return this.followPlan(target);
    }

    private Status followPlan(BlockPos target) {
        if (this.plan.isEmpty() || this.leg >= this.plan.size()) {
            // Waiting out the cooldown with nothing to follow is not progress. Reporting it
            // as such kept the goal holding on to the citizen while it did nothing at all.
            if (this.replanCooldown > 0) {
                this.report(CitizenJourney.Mode.NO_PLAN);
                return Status.STUCK;
            }
            this.plan = CitizenRoutePlanner.plan(this.citizen.level(), this.citizen.blockPosition(), target,
                    this.arrivalDistance, PLAN_HORIZONTAL, PLAN_VERTICAL, PLAN_NODE_LIMIT,
                    this.pillar.canBuild(), this.citizen::placedOwnBlock);
            this.leg = 0;
            this.legTicks = 0;
            this.citizen.getJourney().countPlan(this.plan.isEmpty());
            if (this.plan.isEmpty()) {
                // Backing off is for a search that came up empty. A plan the citizen has
                // simply walked to the end of gets its next stretch straight away, since
                // one plan only ever covers as far as the search box reaches.
                this.replanCooldown = REPLAN_INTERVAL;
                this.report(CitizenJourney.Mode.NO_PLAN);
                return Status.STUCK;
            }
        }

        CitizenRoutePlanner.Step step = this.plan.get(this.leg);
        Level level = this.citizen.level();

        // Clear whatever this leg needs out of the way first.
        for (BlockPos blocking : step.clear()) {
            if (!ReversibleWalk.isPassable(level, blocking, null)) {
                this.citizen.getNavigation().stop();
                if (!CitizenRoutePlanner.isDiggable(level, blocking, this.citizen::placedOwnBlock)) {
                    this.dropPlan();
                    return Status.MOVING;
                }
                if (this.breaker.advance(blocking)) {
                    this.citizen.forgetOwnBlock(blocking);
                }
                this.report(CitizenJourney.Mode.DIGGING);
                return Status.MOVING;
            }
        }

        // Standing where this leg was meant to end finishes it, however it was reached.
        // Checked ahead of building: behind it, a leg that stacks a block never completed,
        // so one block of climb turned into a tower as tall as the citizen's pockets.
        if (this.citizen.blockPosition().equals(step.stand())) {
            this.leg++;
            this.legTicks = 0;
            return Status.MOVING;
        }

        if (step.buildUnderfoot()) {
            this.citizen.getNavigation().stop();
            if (!this.pillar.tick()) {
                this.dropPlan();
            }
            this.report(CitizenJourney.Mode.BUILDING);
            return Status.MOVING;
        }

        if (++this.legTicks > LEG_TIMEOUT) {
            this.citizen.getJourney().countLegTimeout();
            this.dropPlan();
            return Status.MOVING;
        }

        BlockPos stand = step.stand();
        this.citizen.getMoveControl().setWantedPosition(stand.getX() + 0.5D, stand.getY(),
                stand.getZ() + 0.5D, this.speedModifier);
        this.report(CitizenJourney.Mode.PLAN_MOVE);
        return Status.MOVING;
    }

    /**
     * Whether the citizen has gone nowhere and done nothing for long enough to give up on.
     * Digging and climbing count as getting somewhere even though the citizen holds still
     * for them, so this only fires on a citizen that is genuinely doing nothing.
     */
    private boolean stalled() {
        Vec3 pos = this.citizen.position();
        int mined = this.citizen.getMinedBlocks();
        int placed = this.pillar.placedCount();
        boolean moved = this.progressAnchor == null
                || pos.distanceToSqr(this.progressAnchor) >= PROGRESS_DISTANCE * PROGRESS_DISTANCE;
        if (moved || mined != this.lastMined || placed != this.lastPlaced || this.leg != this.lastLeg) {
            this.progressAnchor = pos;
            this.lastMined = mined;
            this.lastPlaced = placed;
            this.lastLeg = this.leg;
            this.stallTicks = 0;
            return false;
        }
        if (++this.stallTicks == STALL_TICKS + 1) {
            this.citizen.getJourney().countStallTrip();
        }
        return this.stallTicks > STALL_TICKS;
    }

    /** Publishes what this tick amounted to, so a report can say it rather than guess it. */
    private void report(CitizenJourney.Mode mode) {
        this.citizen.getJourney().update(this.citizen.level().getGameTime(), mode,
                this.leg, this.plan.size(), this.legTicks, this.stallTicks, this.walkStill,
                this.pillar.placedCount(), this.breaker.current(), this.breaker.progress());
    }

    private void dropPlan() {
        this.plan = List.of();
        this.leg = 0;
        this.legTicks = 0;
    }
}
