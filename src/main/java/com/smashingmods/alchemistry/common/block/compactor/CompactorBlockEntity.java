package com.smashingmods.alchemistry.common.block.compactor;

import com.smashingmods.alchemistry.Config;
import com.smashingmods.alchemistry.api.blockentity.AbstractInventoryBlockEntity;
import com.smashingmods.alchemistry.common.recipe.compactor.CompactorRecipe;
import com.smashingmods.alchemistry.registry.BlockEntityRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.Recipe;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CompactorBlockEntity extends AbstractInventoryBlockEntity {

    public static final int INVENTORY_SIZE = 3;
    public static final int INPUT_SLOT_INDEX = 0;
    public static final int TARGET_SLOT_INDEX = 1;
    public static final int OUTPUT_SLOT_INDEX = 2;

    private CompactorRecipe currentRecipe;
    protected final PropertyDelegate propertyDelegate;
    private final int maxProgress;
    private ItemStack target;

    public CompactorBlockEntity(BlockPos pos, BlockState state) {
        super(DefaultedList.ofSize(INVENTORY_SIZE, ItemStack.EMPTY), BlockEntityRegistry.COMPACTOR_BLOCK_ENTITY, pos, state, Config.Common.compactorEnergyCapacity.get());
        this.target = ItemStack.EMPTY;
        this.maxProgress = Config.Common.compactorTicksPerOperation.get();
        this.propertyDelegate = new PropertyDelegate() {
            public int get(int index) {
                return switch (index) {
                    case 0 -> getProgress();
                    case 1 -> maxProgress;
                    case 2 -> (int) getEnergyStorage().getAmount();
                    case 3 -> (int) getEnergyStorage().getCapacity();
                    default -> 0;
                };
            }
            public void set(int index, int value) {
                switch (index) {
                    case 0 -> setProgress(value);
                    case 2 -> insertEnergy(value);
                }
            }
            public int size() {
                return 4;
            }
        };
    }

    @Override
    public @Nullable ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
        return new CompactorScreenHandler(syncId, inv, this, this, this.propertyDelegate);
    }

    @Override
    public void updateRecipe() {
        if (world == null || world.isClient() || isRecipeLocked()) return;
        if (!getStackInSlot(INPUT_SLOT_INDEX).isEmpty()) {
            SimpleInventory inventory = new SimpleInventory(getItems().size());
            inventory.addStack(getItems().get(INPUT_SLOT_INDEX));
            if (target.isEmpty()) {
                List<CompactorRecipe> recipes = world.getRecipeManager().getAllMatches(CompactorRecipe.Type.INSTANCE, inventory, world);
                if (recipes.size() == 1) {
                    if (currentRecipe == null || !currentRecipe.equals(recipes.get(0))) {
                        setProgress(0);
                        currentRecipe = recipes.get(0);
                        setTarget(recipes.get(0).getOutput().copy());
                    }
                } else {
                    setProgress(0);
                    setRecipe(null);
                }
            } else {
                world.getRecipeManager().getAllMatches(CompactorRecipe.Type.INSTANCE, inventory, world).stream()
                        .filter(recipe -> ItemStack.canCombine(target, recipe.getOutput()))
                        .findFirst()
                        .ifPresent(recipe -> {
                            if (currentRecipe == null || !currentRecipe.equals(recipe)) {
                                setProgress(0);
                                currentRecipe = recipe;
                                setTarget(recipe.getOutput().copy());
                            }
                        });
            }
        }
    }

    @Override
    public boolean canProcessRecipe() {
        if (currentRecipe != null) {
            ItemStack input = getStackInSlot(INPUT_SLOT_INDEX);
            ItemStack output = getStackInSlot(OUTPUT_SLOT_INDEX);
            return getEnergyStorage().getAmount() >= Config.Common.compactorEnergyPerTick.get()
                    && (ItemStack.canCombine(input, currentRecipe.getInput()) && input.getCount() >= currentRecipe.getInput().getCount())
                    && (currentRecipe.getOutput().getCount() + output.getCount()) <= currentRecipe.getOutput().getMaxCount()
                    && (ItemStack.canCombine(output, currentRecipe.getOutput()) || output.isEmpty());
        }
        return false;
    }

    @Override
    public void processRecipe() {
        if (getProgress() < maxProgress) {
            incrementProgress();
        } else {
            setProgress(0);
            decrementSlot(INPUT_SLOT_INDEX, currentRecipe.getInput().getCount());
            setOrIncrement(OUTPUT_SLOT_INDEX, currentRecipe.getOutput().copy());
        }
        extractEnergy(Config.Common.compactorEnergyPerTick.get());
        markDirty();
    }

    @Override
    public <T extends Recipe<SimpleInventory>> void setRecipe(@Nullable T recipe) {
        if (recipe == null) {
            currentRecipe = null;
        } else {
            currentRecipe = (CompactorRecipe) recipe;
            target = recipe.getOutput();
            if (world != null && !world.isClient()) {
                // TODO: Send Packet
                //AlchemistryPacketHandler.sendToNear(new BlockEntityPacket(getBlockPos(), getUpdateTag()), level, getBlockPos(), 64);
            }
        }
    }

    @Override
    public Recipe<SimpleInventory> getRecipe() {
        return currentRecipe;
    }

    public ItemStack getTarget() {
        return target;
    }

    public void setTarget(ItemStack pTarget) {
        if (world != null && !world.isClient() && !isRecipeLocked()) {
            this.target = pTarget;
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        target.writeNbt(nbt);
        super.writeNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        target = ItemStack.fromNbt(nbt);
    }
}