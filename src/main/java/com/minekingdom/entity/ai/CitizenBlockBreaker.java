package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Chips away at one block at a time, keeping the crack overlay in step with progress.
 * Shared by the goals that break blocks so digging always looks and counts the same.
 */
public class CitizenBlockBreaker {
    private final CitizenEntity citizen;

    @Nullable
    private BlockPos current;
    private float progress;
    private int lastStage = -1;

    public CitizenBlockBreaker(CitizenEntity citizen) {
        this.citizen = citizen;
    }

    /**
     * Works on the block for one tick.
     *
     * @return true if this tick finished it off
     */
    public boolean advance(BlockPos pos) {
        if (!pos.equals(this.current)) {
            this.reset();
            this.current = pos;
        }

        Level level = this.citizen.level();
        BlockState state = level.getBlockState(pos);
        this.citizen.swing(InteractionHand.MAIN_HAND);
        this.progress += this.citizen.getDestroyProgressPerTick(state, pos);

        int stage = Mth.clamp((int) (this.progress * 10.0F), 0, 9);
        if (stage != this.lastStage) {
            level.destroyBlockProgress(this.citizen.getId(), pos, stage);
            this.lastStage = stage;
        }

        if (this.progress < 1.0F) {
            return false;
        }

        this.reset();
        if (level.destroyBlock(pos, true, this.citizen, 512)) {
            this.citizen.recordMinedBlock();
        }
        return true;
    }

    /** The block being worked on, or null when nothing is. */
    @Nullable
    public BlockPos current() {
        return this.current;
    }

    /** How far through the current block, from 0 to 1. */
    public float progress() {
        return this.progress;
    }

    /** Drops any part-broken block and clears its crack overlay. */
    public void reset() {
        if (this.lastStage != -1 && this.current != null) {
            this.citizen.level().destroyBlockProgress(this.citizen.getId(), this.current, -1);
        }
        this.current = null;
        this.progress = 0.0F;
        this.lastStage = -1;
    }
}
