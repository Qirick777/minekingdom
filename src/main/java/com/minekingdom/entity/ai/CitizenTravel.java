package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Gets a citizen to a block, walking where it can and cutting through where it cannot.
 *
 * <p>Written against a destination rather than any particular errand, so returning home,
 * heading to a work site or anything a later job needs can drive the same thing.
 *
 * <p>Walking is tried first. When the distance stops coming down the citizen digs out
 * whatever is in the way, or builds upwards if the destination is above it. Digging keeps
 * the usual safety rules, so a citizen making its way back does not flood the tunnel or
 * pull sand down on itself.
 */
public class CitizenTravel {
    public enum Status {
        /** On its way, by whatever means. */
        MOVING,
        ARRIVED,
        /** Cannot walk, dig or build any closer. */
        STUCK
    }

    private static final int REPATH_INTERVAL = 20;
    /** Inside this range steering beats pathfinding, which stops a block short. */
    private static final double CLOSE_RANGE = 4.0D;
    private static final double PROGRESS_STEP = 0.35D;
    private static final int STALL_TICKS = 60;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private final CitizenBlockBreaker breaker;
    private final CitizenPillarBuilder pillar;

    @Nullable
    private BlockPos destination;
    private double arrivalDistance = 1.5D;
    private int repathCooldown;
    private double bestDistance = Double.MAX_VALUE;
    private int stallTicks;
    /**
     * Sticky once the citizen starts cutting a way through. Climbing takes a jump, a wait
     * and a placement across several ticks, so dropping back to walking in between meant
     * the pillar was never finished.
     */
    private boolean cutting;

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
        this.bestDistance = Double.MAX_VALUE;
        this.stallTicks = 0;
        this.cutting = false;
        this.breaker.reset();
        this.pillar.reset();
    }

    public void stop() {
        this.breaker.reset();
        this.pillar.reset();
        this.citizen.getNavigation().stop();
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

        if (distance < this.bestDistance - PROGRESS_STEP) {
            this.bestDistance = distance;
            this.stallTicks = 0;
        } else {
            this.stallTicks++;
        }

        // Getting nowhere on foot: make an opening, and keep at it until close enough that
        // walking can finish the job.
        if (this.cutting && distance <= CLOSE_RANGE) {
            this.cutting = false;
        } else if (this.cutting || this.stallTicks > STALL_TICKS) {
            this.cutting = true;
            return this.cutThrough(target) ? Status.MOVING : Status.STUCK;
        }

        this.breaker.reset();
        if (distance < CLOSE_RANGE) {
            this.citizen.getMoveControl().setWantedPosition(centre.x, target.getY(), centre.z, this.speedModifier);
            return Status.MOVING;
        }
        if (--this.repathCooldown <= 0) {
            this.repathCooldown = REPATH_INTERVAL;
            this.citizen.getNavigation().moveTo(centre.x, target.getY(), centre.z, this.speedModifier);
        }
        return Status.MOVING;
    }

    /** Digs or builds one step towards the destination. */
    private boolean cutThrough(BlockPos target) {
        this.citizen.getNavigation().stop();
        // See a climb through before looking at anything else.
        if (this.pillar.isMidJump()) {
            this.pillar.tick();
            return true;
        }

        Level level = this.citizen.level();
        BlockPos feet = this.citizen.blockPosition();

        if (target.getY() > feet.getY()) {
            BlockPos ceiling = feet.above(2);
            if (!ReversibleWalk.isPassable(level, ceiling, null)) {
                return this.dig(ceiling);
            }
            if (this.pillar.tick()) {
                return true;
            }
        }

        Direction facing = this.towards(feet, target);
        for (BlockPos ahead : new BlockPos[]{feet.relative(facing).above(), feet.relative(facing)}) {
            if (!ReversibleWalk.isPassable(level, ahead, null) && this.dig(ahead)) {
                return true;
            }
        }

        // Only ever dig downwards when that is the way the destination actually lies.
        if (target.getY() >= feet.getY()) {
            return false;
        }
        BlockPos down = feet.relative(facing).below();
        return !ReversibleWalk.isPassable(level, down, null) && this.dig(down);
    }

    private boolean dig(BlockPos pos) {
        if (!CitizenMiningGoal.isBreakableSafely(this.citizen.level(), pos)) {
            return false;
        }
        this.breaker.advance(pos);
        return true;
    }

    private Direction towards(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
