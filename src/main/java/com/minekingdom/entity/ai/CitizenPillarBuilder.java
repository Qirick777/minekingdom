package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Climbs by jumping and dropping a block underfoot, the way a player pillars up.
 *
 * <p>Kept separate from any one goal because getting a block higher is useful to anything
 * a citizen might be doing, not only to escaping.
 */
public class CitizenPillarBuilder {
    /** What a tick of the climb amounted to. */
    public enum Climb {
        /** Going up, or waiting out a jump already under way. */
        CLIMBING,
        /** Something is in the way that the citizen is allowed to break first. */
        NEEDS_CLEAR,
        /** Nothing to stack on, or something in the way it may not break. */
        IMPOSSIBLE
    }

    private static final int JUMP_TIMEOUT = 20;
    /**
     * A hard ceiling on stacking for one errand. Nothing a citizen legitimately needs takes
     * anywhere near this many, so hitting it means something has gone wrong, and without it
     * a citizen that keeps deciding it is stuck builds a tower into the sky.
     */
    private static final int MAX_PER_ERRAND = 12;

    private final CitizenEntity citizen;

    @Nullable
    private BlockPos jumpFrom;
    @Nullable
    private BlockPos blocker;
    private int jumpTicks;
    private int placed;

    public CitizenPillarBuilder(CitizenEntity citizen) {
        this.citizen = citizen;
    }

    public boolean canBuild() {
        return this.placed < MAX_PER_ERRAND && this.buildingSlot() >= 0;
    }

    public boolean hitBuildLimit() {
        return this.placed >= MAX_PER_ERRAND;
    }

    public int placedCount() {
        return this.placed;
    }

    public void reset() {
        this.jumpFrom = null;
        this.blocker = null;
        this.jumpTicks = 0;
        this.placed = 0;
    }

    /**
     * Blocks in the way of rising one block, asked of the citizen's own collision box rather
     * than of a chosen coordinate.
     *
     * <p>The old test looked at one position, two above the citizen's feet. A citizen is only
     * 0.6 wide and stands wherever it stands, so a block overhanging from the next column
     * along stops the jump dead while that one position reads clear: the citizen jumps, fails
     * to rise, times out, and jumps again for ever without ever placing anything. One block
     * is exactly the right height to test, because rising one block is exactly the condition
     * for dropping a block underfoot.
     */
    private List<BlockPos> risingBlockers() {
        Level level = this.citizen.level();
        AABB raised = this.citizen.getBoundingBox().move(0.0D, 1.0D, 0.0D).deflate(1.0E-3D);
        List<BlockPos> blockers = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(raised.minX), Mth.floor(raised.minY), Mth.floor(raised.minZ),
                Mth.floor(raised.maxX), Mth.floor(raised.maxY), Mth.floor(raised.maxZ))) {
            VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            if (shape.bounds().move(pos.getX(), pos.getY(), pos.getZ()).intersects(raised)) {
                blockers.add(pos.immutable());
            }
        }
        return blockers;
    }

    /** The block to break before the next jump, set when {@link #tick} says NEEDS_CLEAR. */
    @Nullable
    public BlockPos blocker() {
        return this.blocker;
    }

    /**
     * Drives one tick of the climb.
     *
     * @return what the caller has to do about it
     */
    public Climb tick() {
        // A jump already under way is seen through first. Checks against the citizen's
        // block position mean something different once it is off the ground, so anything
        // else here would abandon the climb halfway up every time.
        if (this.jumpFrom != null) {
            if (this.citizen.getY() >= this.jumpFrom.getY() + 1.0D) {
                this.placeUnderfoot(this.jumpFrom);
                this.jumpFrom = null;
            } else if (++this.jumpTicks > JUMP_TIMEOUT) {
                this.jumpFrom = null;
            }
            return Climb.CLIMBING;
        }

        this.blocker = null;
        if (!this.canBuild()) {
            return Climb.IMPOSSIBLE;
        }

        List<BlockPos> blockers = this.risingBlockers();
        if (!blockers.isEmpty()) {
            for (BlockPos pos : blockers) {
                // Whether a block may be broken is a question already answered elsewhere,
                // in the same six ways the route planner answers it. All that was missing
                // was knowing which block to ask about.
                if (CitizenRoutePlanner.digVerdict(this.citizen.level(), pos,
                        this.citizen::placedOwnBlock) != CitizenRoutePlanner.DigVerdict.OK) {
                    return Climb.IMPOSSIBLE;
                }
            }
            this.blocker = blockers.get(0);
            return Climb.NEEDS_CLEAR;
        }

        if (this.citizen.onGround()) {
            this.jumpFrom = this.citizen.blockPosition();
            this.jumpTicks = 0;
            this.citizen.getJumpControl().jump();
        }
        return Climb.CLIMBING;
    }

    /** True while the citizen is off the ground waiting to drop a block into the gap. */
    public boolean isMidJump() {
        return this.jumpFrom != null;
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
        // Noted as the citizen's own, which is what later lets it dig the block back out
        // and climb down. Cobblestone is off limits to citizens in every other context.
        this.citizen.rememberOwnBlock(pos);
        stack.shrink(1);
        this.citizen.getInventory().setChanged();
        this.placed++;
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
}
