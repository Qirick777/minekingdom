package com.minekingdom.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A villager-like citizen that uses the player model. Spawns randomly as
 * male (classic/Steve model) or female (slim/Alex model), and carries an
 * eight slot inventory whose selected slot is mirrored into the main hand
 * so it renders like a held player item.
 *
 * <p>Citizens are neutral: they never flee, but fight back whatever hurts
 * them and stay angry for a while afterwards.
 */
public class CitizenEntity extends PathfinderMob implements InventoryCarrier, NeutralMob {
    public static final int INVENTORY_SIZE = 8;

    private static final EntityDataAccessor<Boolean> DATA_FEMALE =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    private static final UniformInt PERSISTENT_ANGER_TIME = TimeUtil.rangeOfSeconds(20, 39);

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private int selectedSlot;
    private int remainingPersistentAngerTime;
    @Nullable
    private UUID persistentAngerTarget;

    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
        ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
        // The main hand only mirrors the inventory, so the inventory alone handles drops.
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        this.setCanPickUpLoot(true);
        this.inventory.addListener(container -> this.updateHeldItem());
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.5D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                // Not part of createMobAttributes; without it retaliation cannot deal damage.
                // A held weapon adds its own modifier on top of this.
                .add(Attributes.ATTACK_DAMAGE, 2.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.65D));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new ResetUniversalAngerTargetGoal<>(this, true));
    }

    @Override
    protected void customServerAiStep() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.updatePersistentAnger(serverLevel, true);
        }
        super.customServerAiStep();
    }

    @Override
    public int getRemainingPersistentAngerTime() {
        return this.remainingPersistentAngerTime;
    }

    @Override
    public void setRemainingPersistentAngerTime(int time) {
        this.remainingPersistentAngerTime = time;
    }

    @Nullable
    @Override
    public UUID getPersistentAngerTarget() {
        return this.persistentAngerTarget;
    }

    @Override
    public void setPersistentAngerTarget(@Nullable UUID target) {
        this.persistentAngerTarget = target;
    }

    @Override
    public void startPersistentAngerTimer() {
        this.setRemainingPersistentAngerTime(PERSISTENT_ANGER_TIME.sample(this.random));
    }

    /**
     * Only takes what fits, and only from items already within reach. There is no
     * goal steering towards dropped items, so citizens pick things up by walking
     * over them rather than being pulled to them.
     */
    @Override
    public boolean wantsToPickUp(ItemStack stack) {
        return this.inventory.canAddItem(stack);
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        InventoryCarrier.pickUpItem(this, this, itemEntity);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_FEMALE, false);
    }

    public boolean isFemale() {
        return this.entityData.get(DATA_FEMALE);
    }

    public void setFemale(boolean female) {
        this.entityData.set(DATA_FEMALE, female);
    }

    @Override
    public SimpleContainer getInventory() {
        return this.inventory;
    }

    public int getSelectedSlot() {
        return this.selectedSlot;
    }

    public void setSelectedSlot(int slot) {
        this.selectedSlot = Mth.clamp(slot, 0, INVENTORY_SIZE - 1);
        this.updateHeldItem();
    }

    /** Copies the selected inventory slot into the main hand so it renders and syncs to clients. */
    private void updateHeldItem() {
        if (this.level().isClientSide) {
            return;
        }
        // Saving drops empty slots, so a reload packs items towards the front and the
        // remembered slot can land on a gap. Fall back rather than appear empty-handed.
        if (this.inventory.getItem(this.selectedSlot).isEmpty()) {
            this.selectedSlot = this.firstOccupiedSlot();
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, this.inventory.getItem(this.selectedSlot).copy());
    }

    private int firstOccupiedSlot() {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            if (!this.inventory.getItem(i).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    public int getOccupiedSlots() {
        int count = 0;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            if (!this.inventory.getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }

        ItemStack offered = player.getItemInHand(hand);
        if (!offered.isEmpty()) {
            return this.receiveItem(player, offered);
        }
        if (player.isSecondaryUseActive()) {
            return this.returnInventoryTo(player);
        }
        return this.cycleHeldSlot(player);
    }

    /** Stores the offered stack and switches the citizen to holding it. */
    private InteractionResult receiveItem(Player player, ItemStack offered) {
        int before = offered.getCount();
        ItemStack leftover = this.inventory.addItem(offered.copy());
        int accepted = before - leftover.getCount();
        if (accepted <= 0) {
            player.displayClientMessage(Component.translatable("message.minekingdom.citizen.inventory_full"), true);
            return InteractionResult.CONSUME;
        }

        Item item = offered.getItem();
        if (!player.getAbilities().instabuild) {
            offered.shrink(accepted);
        }
        this.selectSlotWith(item);
        this.playSound(SoundEvents.ITEM_PICKUP, 0.6F, 1.0F);
        this.announceInventory(player);
        return InteractionResult.CONSUME;
    }

    /** Hands everything back so nothing put into a citizen can be lost. */
    private InteractionResult returnInventoryTo(Player player) {
        if (this.inventory.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.minekingdom.citizen.empty"), true);
            return InteractionResult.CONSUME;
        }

        for (ItemStack stack : this.inventory.removeAllItems()) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        this.setSelectedSlot(0);
        this.playSound(SoundEvents.ITEM_PICKUP, 0.6F, 1.2F);
        player.displayClientMessage(Component.translatable("message.minekingdom.citizen.returned"), true);
        return InteractionResult.CONSUME;
    }

    /** Moves to the next filled slot so every carried item can be shown in hand. */
    private InteractionResult cycleHeldSlot(Player player) {
        if (this.inventory.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.minekingdom.citizen.empty"), true);
            return InteractionResult.CONSUME;
        }

        for (int step = 1; step <= INVENTORY_SIZE; step++) {
            int next = (this.selectedSlot + step) % INVENTORY_SIZE;
            if (!this.inventory.getItem(next).isEmpty()) {
                this.setSelectedSlot(next);
                break;
            }
        }
        this.announceInventory(player);
        return InteractionResult.CONSUME;
    }

    private void selectSlotWith(Item item) {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            if (this.inventory.getItem(i).is(item)) {
                this.setSelectedSlot(i);
                return;
            }
        }
    }

    private void announceInventory(Player player) {
        ItemStack held = this.getMainHandItem();
        Component heldName = held.isEmpty()
                ? Component.translatable("message.minekingdom.citizen.nothing_held")
                : held.getHoverName();
        player.displayClientMessage(Component.translatable("message.minekingdom.citizen.inventory",
                this.getOccupiedSlots(), INVENTORY_SIZE, heldName), true);
    }

    @Override
    protected void dropEquipment() {
        super.dropEquipment();
        for (ItemStack stack : this.inventory.removeAllItems()) {
            this.spawnAtLocation(stack);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag dataTag) {
        this.setFemale(level.getRandom().nextBoolean());
        return super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Female", this.isFemale());
        tag.putByte("SelectedSlot", (byte) this.selectedSlot);
        this.writeInventoryToTag(tag);
        this.addPersistentAngerSaveData(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setFemale(tag.getBoolean("Female"));
        this.readInventoryFromTag(tag);
        this.setSelectedSlot(tag.getByte("SelectedSlot"));
        this.readPersistentAngerSaveData(this.level(), tag);
    }
}
