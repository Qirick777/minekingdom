package com.minekingdom.entity.ai;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * What a citizen is doing about getting somewhere, as it is doing it.
 *
 * <p>{@link CitizenTravel} keeps this up to date every tick, and whichever goal is driving
 * says so when it takes over. Without it the only way to ask why a citizen is standing
 * still was to work out a fresh route and hope it resembled the one the citizen was
 * actually following, which it often did not: goals travel towards different places.
 *
 * <p>The tallies are lifetime counts per citizen. They are what a give-up report is made
 * of, and they separate "never got a plan" from "got a plan and could not walk it".
 */
public class CitizenJourney {
    public enum Mode {
        /** Not travelling. */
        NONE,
        /** Following an ordinary path, which is what happens whenever one exists. */
        WALKING,
        /** Walking towards the end of the current leg of a route plan. */
        PLAN_MOVE,
        /** Standing still breaking a block the plan calls for. */
        DIGGING,
        /** Jumping and dropping a block underfoot. */
        BUILDING,
        /** Route search came back empty and the next attempt is on a cooldown. */
        NO_PLAN,
        ARRIVED,
        /** Went nowhere and did nothing for long enough to give up on. */
        STALLED
    }

    private String driver = "none";
    @Nullable
    private BlockPos target;
    private Mode mode = Mode.NONE;
    private int leg;
    private int legCount;
    private int legTicks;
    private int stallTicks;
    private int hardStallTicks;
    private int walkStill;
    private int placed;
    @Nullable
    private BlockPos digPos;
    private float digProgress;
    private long updatedAt = Long.MIN_VALUE;

    private int plansMade;
    private int emptyPlans;
    private int legTimeouts;
    private int walkBans;
    private int stallTrips;

    public void begin(String driver, BlockPos target) {
        this.driver = driver;
        this.target = target.immutable();
        this.mode = Mode.NONE;
        this.leg = 0;
        this.legCount = 0;
        this.legTicks = 0;
        this.stallTicks = 0;
        this.walkStill = 0;
        this.digPos = null;
        this.digProgress = 0.0F;
    }

    public void update(long gameTime, Mode mode, int leg, int legCount, int legTicks,
                       int stallTicks, int walkStill, int placed,
                       @Nullable BlockPos digPos, float digProgress) {
        this.updatedAt = gameTime;
        this.mode = mode;
        this.leg = leg;
        this.legCount = legCount;
        this.legTicks = legTicks;
        this.stallTicks = stallTicks;
        this.walkStill = walkStill;
        this.placed = placed;
        this.digPos = digPos;
        this.digProgress = digProgress;
    }

    public void released() {
        this.mode = Mode.NONE;
    }

    /**
     * How long the citizen has been getting nowhere, kept beside what it is doing rather
     * than replacing it. A citizen reported only as STALLED hides whether it was trying to
     * dig, trying to climb, or doing nothing at all, which is the first thing worth knowing.
     */
    public void setStalledFor(int stallTicks, int hardStallTicks) {
        this.stallTicks = stallTicks;
        this.hardStallTicks = hardStallTicks;
    }

    public void countPlan(boolean empty) {
        this.plansMade++;
        if (empty) {
            this.emptyPlans++;
        }
    }

    public void countLegTimeout() {
        this.legTimeouts++;
    }

    public void countWalkBan() {
        this.walkBans++;
    }

    public void countStallTrip() {
        this.stallTrips++;
    }

    public String driver() {
        return this.driver;
    }

    @Nullable
    public BlockPos target() {
        return this.target;
    }

    public Mode mode() {
        return this.mode;
    }

    public long updatedAt() {
        return this.updatedAt;
    }

    /** One greppable line: what is being attempted, how far in, and what has gone wrong so far. */
    public String describe(long gameTime) {
        return "driver=" + this.driver
                + " target=" + (this.target == null ? "none" : this.target.toShortString())
                + " mode=" + this.mode
                + " age=" + (this.updatedAt == Long.MIN_VALUE ? "never" : String.valueOf(gameTime - this.updatedAt))
                + " leg=" + this.leg + "/" + this.legCount
                + " legTicks=" + this.legTicks
                + " stalledFor=" + this.stallTicks + "/" + this.hardStallTicks
                + " walkStill=" + this.walkStill
                + " placed=" + this.placed
                + " dig=" + (this.digPos == null ? "none"
                        : this.digPos.toShortString() + "@" + Math.round(this.digProgress * 100.0F) + "%")
                + " plans=" + this.plansMade + " empty=" + this.emptyPlans
                + " legTimeouts=" + this.legTimeouts
                + " walkBans=" + this.walkBans
                + " stallTrips=" + this.stallTrips;
    }
}
