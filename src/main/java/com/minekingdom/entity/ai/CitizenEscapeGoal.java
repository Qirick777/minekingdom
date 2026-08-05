package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import com.minekingdom.entity.task.CitizenTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Last resort for a citizen that has walled itself in: jump and drop a block underfoot to
 * climb out, the way a player pillars up.
 *
 * <p>The mining rules try hard not to let this happen, but terrain also changes underneath
 * a citizen for reasons it did not cause, so there has to be a way back out. It only runs
 * when the citizen is carrying something to build with; with an empty inventory the mining
 * goal keeps working instead and the stone it breaks is picked up, which is what supplies
 * the blocks for the climb.
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
    private static final int JUMP_TIMEOUT = 20;
    /** How far around itself a citizen looks for something it could still dig through. */
    private static final int DIGGABLE_RADIUS = 2;

    private final CitizenEntity citizen;
    private final CitizenBlockBreaker breaker;

    @Nullable
    private BlockPos lastPos;
    private int stillChecks;
    private int lastMined = -1;
    private int placed;
    @Nullable
    private BlockPos jumpFrom;
    private int jumpTicks;

    public CitizenEscapeGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.breaker = new CitizenBlockBreaker(citizen);
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
        boolean canAct = this.hasHeadroom() ? this.canBuild() : this.ceilingIsBreakable();
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
        if (this.placed >= MAX_PILLAR || this.citizen.getTask() == CitizenTask.IDLE) {
            return false;
        }
        return this.hasHeadroom() ? this.canBuild() : this.ceilingIsBreakable();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.placed = 0;
        this.jumpFrom = null;
        this.jumpTicks = 0;
        this.citizen.getNavigation().stop();
    }

    @Override
    public void stop() {
        this.breaker.reset();
        this.jumpFrom = null;
        this.stillChecks = 0;
        this.lastPos = null;
    }

    @Override
    public void tick() {
        // A blocked ceiling has to come down before there is anywhere to climb to.
        if (!this.hasHeadroom()) {
            this.jumpFrom = null;
            BlockPos ceiling = this.citizen.blockPosition().above(2);
            this.citizen.getLookControl().setLookAt(ceiling.getX() + 0.5D, ceiling.getY() + 0.5D, ceiling.getZ() + 0.5D);
            this.breaker.advance(ceiling);
            return;
        }

        if (this.jumpFrom == null) {
            if (this.citizen.onGround()) {
                this.jumpFrom = this.citizen.blockPosition();
                this.jumpTicks = 0;
                this.citizen.getJumpControl().jump();
            }
            return;
        }

        // Wait until the citizen is clear of the space before filling it in.
        if (this.citizen.getY() >= this.jumpFrom.getY() + 1.0D) {
            this.placeUnderfoot(this.jumpFrom);
            this.jumpFrom = null;
            return;
        }
        if (++this.jumpTicks > JUMP_TIMEOUT) {
            this.jumpFrom = null;
        }
    }

    private void placeUnderfoot(BlockPos pos) {
        Level level = this.citizen.level();
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || !ReversibleWalk.isPassable(level, pos, null)) {
            return;
        }

        int slot = this.buildingSlot();
        if (slot < 0) {
            return;
        }
        ItemStack stack = this.citizen.getInventory().getItem(slot);
        BlockState state = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();

        level.setBlockAndUpdate(pos, state);
        stack.shrink(1);
        this.citizen.getInventory().setChanged();
        this.placed++;

        // Once there is a way up again there is no reason to keep building.
        if (!this.isBoxedIn()) {
            this.citizen.setStuck(false);
            this.placed = MAX_PILLAR;
        }
    }

    private boolean canBuild() {
        return this.buildingSlot() >= 0;
    }

    /** First inventory slot holding something solid enough to stand on. */
    private int buildingSlot() {
        for (int i = 0; i < this.citizen.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.citizen.getInventory().getItem(i);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (state.canOcclude() && !(blockItem.getBlock() instanceof FallingBlock)) {
                return i;
            }
        }
        return -1;
    }

    /** The block right above the citizen's head, when it is one a citizen may take out. */
    private boolean ceilingIsBreakable() {
        BlockPos ceiling = this.citizen.blockPosition().above(2);
        return CitizenMiningGoal.isBreakableSafely(this.citizen.level(), ceiling);
    }

    private boolean hasHeadroom() {
        BlockPos feet = this.citizen.blockPosition();
        return ReversibleWalk.isPassable(this.citizen.level(), feet.above(2), null);
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
