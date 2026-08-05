package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Climbs by jumping and dropping a block underfoot, the way a player pillars up.
 *
 * <p>Kept separate from any one goal because getting a block higher is useful to anything
 * a citizen might be doing, not only to escaping.
 */
public class CitizenPillarBuilder {
    private static final int JUMP_TIMEOUT = 20;

    private final CitizenEntity citizen;

    @Nullable
    private BlockPos jumpFrom;
    private int jumpTicks;
    private int placed;

    public CitizenPillarBuilder(CitizenEntity citizen) {
        this.citizen = citizen;
    }

    public boolean canBuild() {
        return this.buildingSlot() >= 0;
    }

    public int placedCount() {
        return this.placed;
    }

    public void reset() {
        this.jumpFrom = null;
        this.jumpTicks = 0;
        this.placed = 0;
    }

    /** Room to end up standing a block higher than the citizen is now. */
    public boolean hasHeadroom() {
        return ReversibleWalk.isPassable(this.citizen.level(), this.citizen.blockPosition().above(2), null);
    }

    /**
     * Drives one tick of the climb.
     *
     * @return true while it is making progress, false if it cannot build here
     */
    public boolean tick() {
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
            return true;
        }

        if (!this.canBuild() || !this.hasHeadroom()) {
            return false;
        }
        if (this.citizen.onGround()) {
            this.jumpFrom = this.citizen.blockPosition();
            this.jumpTicks = 0;
            this.citizen.getJumpControl().jump();
        }
        return true;
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
