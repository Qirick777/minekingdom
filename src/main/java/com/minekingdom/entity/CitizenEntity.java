package com.minekingdom.entity;

import com.minekingdom.MineKingdom;
import com.minekingdom.entity.ai.CitizenEscapeGoal;
import com.minekingdom.entity.ai.CitizenJourney;
import com.minekingdom.entity.ai.CitizenMiningGoal;
import com.minekingdom.entity.ai.CitizenPathMemory;
import com.minekingdom.entity.ai.CitizenReturnGoal;
import com.minekingdom.entity.ai.CitizenWatch;
import com.minekingdom.entity.task.CitizenAssignment;
import com.minekingdom.entity.task.CitizenTask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
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
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private static final float BARE_HAND_DIG_SPEED = 1.0F;
    /** How close a citizen has to get before a return counts as made. */
    public static final double RETURN_ARRIVAL_DISTANCE = 1.5D;
    /** No thought of heading back for the first couple of minutes of a shift. */
    private static final int RETURN_WARMUP_TICKS = 2400;
    /**
     * After the warm-up the chance of turning for home rises with every tick worked, which
     * puts the middle of the spread at five minutes with citizens setting off between
     * roughly three and seven and a half. A flat chance would have some leaving almost at
     * once and others staying out for twenty minutes.
     */
    private static final double RETURN_URGE_PER_TICK = 1.07E-7D;
    /** Far more than any single climb needs; only there so the list cannot grow without end. */
    private static final int OWN_BLOCK_LIMIT = 256;

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private int selectedSlot;
    private CitizenTask task = CitizenTask.IDLE;
    private CitizenAssignment assignment = CitizenAssignment.NONE;
    private int workTicks;
    private int returnTrips;
    private int returnArrivals;
    @Nullable
    private BlockPos returnPoint;
    private int minedBlocks;
    private boolean stuck;
    /** How the last return trip ended, so a frozen citizen is not read as a successful one. */
    private String returnOutcome = "none";
    private int returnBlocksPlaced;
    /** The ground this citizen has walked over, kept walkable whatever it is working on. */
    private final CitizenPathMemory pathMemory = new CitizenPathMemory();
    /**
     * Blocks this citizen stacked up underfoot, so it can take them back down again.
     * Cobblestone is nothing a citizen is otherwise allowed to break, and without this a
     * citizen that pillars up is left on top of its own work with no legal way off.
     */
    private final Set<BlockPos> ownBlocks = new LinkedHashSet<>();
    /** Game time until which ordinary walking is not to be trusted for this citizen. */
    private long walkBanUntil;
    /** Game time until which routes that climb by stacking are not worth planning. */
    private long buildBanUntil;
    /** What this citizen is doing about getting somewhere, kept current as it does it. */
    private final CitizenJourney journey = new CitizenJourney();
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
                // Timed over open ground at two settings: ground speed is not linear in the
                // attribute but close to 42.6 x (attribute x goal modifier) squared blocks per
                // second. Strolling at 0.315 gives 4.2, about a walking player's 4.3.
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
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
        // Outranks the work goals: a walled-in citizen has to dig itself out before anything else.
        this.goalSelector.addGoal(3, new CitizenEscapeGoal(this, 1.0D));
        this.goalSelector.addGoal(4, new CitizenReturnGoal(this, 1.0D));
        // Sits above strolling: when it finds nothing to mine it stands down and the citizen wanders.
        this.goalSelector.addGoal(5, new CitizenMiningGoal(this, 0.9D));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.9D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new ResetUniversalAngerTargetGoal<>(this, true));
    }

    @Override
    protected void customServerAiStep() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.updatePersistentAnger(serverLevel, true);
        }
        this.pathMemory.record(this.blockPosition());
        CitizenWatch.sample(this);
        this.tickReturnUrge();
        super.customServerAiStep();
    }

    public CitizenPathMemory getPathMemory() {
        return this.pathMemory;
    }

    public CitizenJourney getJourney() {
        return this.journey;
    }

    /** Notes a block this citizen put down, forgetting the oldest once the list is long. */
    public void rememberOwnBlock(BlockPos pos) {
        if (this.ownBlocks.size() >= OWN_BLOCK_LIMIT) {
            Iterator<BlockPos> oldest = this.ownBlocks.iterator();
            oldest.next();
            oldest.remove();
        }
        this.ownBlocks.add(pos.immutable());
    }

    /** Whether this citizen is the one who put a block here, and so may take it back. */
    public boolean placedOwnBlock(BlockPos pos) {
        return this.ownBlocks.contains(pos);
    }

    public void forgetOwnBlock(BlockPos pos) {
        this.ownBlocks.remove(pos);
    }

    /**
     * Records that ordinary walking has been tried here and got the citizen nowhere.
     *
     * <p>Kept on the citizen rather than on whatever is driving it at the time. Goals hand a
     * citizen back and forth, and a verdict that starts over on every handover never lasts
     * long enough to be acted on, which leaves the citizen shuffling on the spot.
     */
    public void banWalking(int ticks) {
        this.walkBanUntil = this.level().getGameTime() + ticks;
    }

    public boolean isWalkingBanned() {
        return this.level().getGameTime() < this.walkBanUntil;
    }

    /**
     * Records that climbing by stacking has been tried here and cannot work.
     *
     * <p>Kept as a spell rather than as a list of forbidden places. A route search that is
     * told not to count on stacking comes back with a way that digs or walks instead, and a
     * ban that expires on its own can never write off good ground for ever -- which a list
     * of positions would, since the usual reason a climb fails is something momentary.
     */
    public void banBuilding(int ticks) {
        this.buildBanUntil = this.level().getGameTime() + ticks;
    }

    public boolean isBuildingBanned() {
        return this.level().getGameTime() < this.buildBanUntil;
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

    public CitizenTask getTask() {
        return this.task;
    }

    /**
     * The single entry point for putting a citizen to work. Commands use it today;
     * jobs, workplace blocks or anything else can drive citizens the same way.
     */
    public void setTask(CitizenTask task) {
        this.task = task;
        if (task == CitizenTask.IDLE) {
            this.pathMemory.clear();
        }
    }

    public CitizenAssignment getAssignment() {
        return this.assignment;
    }

    /** Puts a citizen to work, or calls it off. The activity follows from the assignment. */
    public void setAssignment(CitizenAssignment assignment) {
        this.assignment = assignment;
        this.workTicks = 0;
        this.pathMemory.clear();
        this.setTask(assignment == CitizenAssignment.NONE ? CitizenTask.IDLE : CitizenTask.MINING);
    }

    public int getReturnTrips() {
        return this.returnTrips;
    }

    public int getReturnArrivals() {
        return this.returnArrivals;
    }

    /** Nowhere left to put anything, which is reason enough to head back. */
    public boolean isInventoryFull() {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            if (this.inventory.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void tickReturnUrge() {
        if (this.assignment != CitizenAssignment.MINING_WITH_RETURN || this.task != CitizenTask.MINING) {
            return;
        }
        if (this.returnPoint == null) {
            return;
        }

        this.workTicks++;
        if (this.isInventoryFull()) {
            this.setTask(CitizenTask.RETURNING);
            return;
        }
        if (this.workTicks <= RETURN_WARMUP_TICKS) {
            return;
        }
        double urge = RETURN_URGE_PER_TICK * (this.workTicks - RETURN_WARMUP_TICKS);
        if (this.random.nextDouble() < urge) {
            this.setTask(CitizenTask.RETURNING);
        }
    }

    public boolean isMining() {
        return this.task == CitizenTask.MINING;
    }

    /** How many blocks this citizen has mined, for checking nobody is standing around idle. */
    public int getMinedBlocks() {
        return this.minedBlocks;
    }

    public void recordMinedBlock() {
        this.minedBlocks++;
    }

    public void resetMinedBlocks() {
        this.minedBlocks = 0;
    }

    @Nullable
    public BlockPos getReturnPoint() {
        return this.returnPoint;
    }

    /** Records where this citizen should be able to walk back to. */
    public void setReturnPoint(@Nullable BlockPos pos) {
        this.returnPoint = pos == null ? null : pos.immutable();
        this.pathMemory.clear();
    }

    public boolean isAtReturnPoint() {
        return this.distanceToReturnPoint() <= RETURN_ARRIVAL_DISTANCE;
    }

    /** Distance to the return point, or {@link Double#NaN} when none is recorded. */
    public double distanceToReturnPoint() {
        BlockPos home = this.returnPoint;
        if (home == null) {
            return Double.NaN;
        }
        return Math.sqrt(this.distanceToSqr(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D));
    }

    /**
     * Ends a return trip and logs the outcome. The distance the citizen set out from is
     * included so a log line shows on its own whether a walk actually happened.
     */
    public void finishReturn(boolean arrived, int ticks, double startDistance, int blocksPlaced) {
        this.returnOutcome = arrived ? "arrived" : "gave_up";
        this.returnBlocksPlaced = blocksPlaced;
        this.returnTrips++;
        if (arrived) {
            this.returnArrivals++;
        }
        // The trip is over either way, so the way it went is no longer worth keeping.
        this.pathMemory.clear();
        this.workTicks = 0;
        // A trip that ran out of time leaves the citizen where it failed, which is worth
        // going on saying: the flag is what lets the escape goal keep working on a citizen
        // no job would otherwise touch again.
        this.setStuck(!arrived);
        if (this.assignment == CitizenAssignment.MINING_WITH_RETURN) {
            // Back to work: a failed trip must not leave a citizen standing about.
            this.setTask(CitizenTask.MINING);
        } else {
            this.setTask(CitizenTask.IDLE);
        }
        if (arrived) {
            MineKingdom.LOGGER.info("Citizen {} returned to {} after {} ticks, having set out {} blocks away, "
                            + "standing {} it and stacking {} block(s) on the way",
                    this.getStringUUID(), this.returnPoint, ticks, String.format("%.2f", startDistance),
                    this.isExactlyAtReturnPoint() ? "exactly on" : "beside", blocksPlaced);
        } else {
            // Everything the attempt ran into, in one greppable line. Without the tallies a
            // failure that never got a plan and one that got plenty and could not walk them
            // are the same sentence.
            MineKingdom.LOGGER.info("CZPOST uuid={} at={} home={} ticks={} short={} setOut={} placed={} mined={} {}",
                    this.getStringUUID(), this.blockPosition().toShortString(),
                    this.returnPoint == null ? "none" : this.returnPoint.toShortString(), ticks,
                    String.format("%.2f", this.distanceToReturnPoint()), String.format("%.2f", startDistance),
                    blocksPlaced, this.minedBlocks, this.journey.describe(this.level().getGameTime()));
        }
    }

    public String getReturnOutcome() {
        return this.returnOutcome;
    }

    public int getReturnBlocksPlaced() {
        return this.returnBlocksPlaced;
    }

    public void beginReturnTrip() {
        this.returnOutcome = "travelling";
        this.returnBlocksPlaced = 0;
    }

    /** Standing on the exact block it set out from, rather than merely near it. */
    public boolean isExactlyAtReturnPoint() {
        return this.returnPoint != null && this.blockPosition().equals(this.returnPoint);
    }

    /** Walled in with no way to dig or build out. Reported rather than left to look like idling. */
    public boolean isStuck() {
        return this.stuck;
    }

    public void setStuck(boolean stuck) {
        if (stuck && !this.stuck) {
            MineKingdom.LOGGER.info("Citizen {} is stuck at {} with no way to dig or build out",
                    this.getStringUUID(), this.blockPosition());
        }
        this.stuck = stuck;
    }

    /** Which AI goals currently hold this citizen, for {@code /ctest debug}. */
    public String describeRunningGoals() {
        return this.goalSelector.getRunningGoals()
                .map(wrapped -> wrapped.getPriority() + ":" + wrapped.getGoal().getClass().getSimpleName())
                .collect(Collectors.joining(", "));
    }

    /**
     * How much of a block a citizen breaks per tick, as a fraction of the whole.
     * Citizens currently mine bare handed at the rate a suitable tool would give;
     * once they carry pickaxes, the held tool's speed and harvest level hook in here.
     */
    public float getDestroyProgressPerTick(BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(this.level(), pos);
        if (hardness < 0.0F) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return 1.0F;
        }
        return BARE_HAND_DIG_SPEED / hardness / 30.0F;
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
        tag.putString("Task", this.task.getSerializedName());
        tag.putString("Assignment", this.assignment.getSerializedName());
        tag.putInt("WorkTicks", this.workTicks);
        tag.putInt("ReturnTrips", this.returnTrips);
        tag.putInt("ReturnArrivals", this.returnArrivals);
        tag.putInt("MinedBlocks", this.minedBlocks);
        if (this.returnPoint != null) {
            tag.put("ReturnPoint", NbtUtils.writeBlockPos(this.returnPoint));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setFemale(tag.getBoolean("Female"));
        this.readInventoryFromTag(tag);
        this.setSelectedSlot(tag.getByte("SelectedSlot"));
        this.readPersistentAngerSaveData(this.level(), tag);
        this.setTask(CitizenTask.byName(tag.getString("Task")));
        this.assignment = CitizenAssignment.byName(tag.getString("Assignment"));
        this.workTicks = tag.getInt("WorkTicks");
        this.returnTrips = tag.getInt("ReturnTrips");
        this.returnArrivals = tag.getInt("ReturnArrivals");
        this.minedBlocks = tag.getInt("MinedBlocks");
        this.setReturnPoint(tag.contains("ReturnPoint") ? NbtUtils.readBlockPos(tag.getCompound("ReturnPoint")) : null);
    }
}
