package com.createairlogistics.content.logistics.cargo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

public class AirshipCargoHoldBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_COUNT = 9;

    private int openCount = 0;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final Container containerView = new Container() {
        @Override
        public int getContainerSize() {
            return SLOT_COUNT;
        }

        @Override
        public boolean isEmpty() {
            return AirshipCargoHoldBlockEntity.this.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack result = inventory.extractItem(slot, amount, false);
            if (!result.isEmpty()) {
                setChanged();
            }
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack current = inventory.getStackInSlot(slot);
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
            return current;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inventory.setStackInSlot(slot, stack);
            setChanged();
        }

        @Override
        public void setChanged() {
            AirshipCargoHoldBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return AirshipCargoHoldBlockEntity.this.level != null
                    && AirshipCargoHoldBlockEntity.this.level.getBlockEntity(AirshipCargoHoldBlockEntity.this.worldPosition) == AirshipCargoHoldBlockEntity.this
                    && player.distanceToSqr(
                    AirshipCargoHoldBlockEntity.this.worldPosition.getX() + 0.5,
                    AirshipCargoHoldBlockEntity.this.worldPosition.getY() + 0.5,
                    AirshipCargoHoldBlockEntity.this.worldPosition.getZ() + 0.5
            ) <= 64.0;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < SLOT_COUNT; i++) {
                inventory.setStackInSlot(i, ItemStack.EMPTY);
            }
            setChanged();
        }

        @Override
        public void startOpen(Player player) {
            if (isRemoved() || player.isSpectator() || level == null || level.isClientSide()
                    || level.getBlockEntity(worldPosition) != AirshipCargoHoldBlockEntity.this) {
                return;
            }
            openCount++;
            if (openCount == 1) {
                BlockState liveState = level.getBlockState(worldPosition);
                if (liveState.getBlock() instanceof AirshipCargoHoldBlock && liveState.hasProperty(AirshipCargoHoldBlock.OPEN)) {
                    level.playSound(null, worldPosition, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.65f, 0.95f);
                    if (!liveState.getValue(AirshipCargoHoldBlock.OPEN)) {
                        level.setBlock(worldPosition, liveState.setValue(AirshipCargoHoldBlock.OPEN, true), 3);
                    }
                }
            }
        }

        @Override
        public void stopOpen(Player player) {
            if (player.isSpectator() || level == null || level.isClientSide()) {
                return;
            }
            openCount = Math.max(0, openCount - 1);
            if (openCount == 0) {
                if (isRemoved() || level.getBlockEntity(worldPosition) != AirshipCargoHoldBlockEntity.this) {
                    return;
                }
                BlockState liveState = level.getBlockState(worldPosition);
                if (liveState.getBlock() instanceof AirshipCargoHoldBlock && liveState.hasProperty(AirshipCargoHoldBlock.OPEN)) {
                    level.playSound(null, worldPosition, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.65f, 0.95f);
                    if (liveState.getValue(AirshipCargoHoldBlock.OPEN)) {
                        level.setBlock(worldPosition, liveState.setValue(AirshipCargoHoldBlock.OPEN, false), 3);
                    }
                }
            }
        }
    };

    public AirshipCargoHoldBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.create_air_logistics.airship_cargo_hold");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new ChestMenu(MenuType.GENERIC_9x1, containerId, playerInventory, containerView, 1);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    public boolean isFull() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty() {
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int getFilledSlotCount() {
        int filled = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    public int getComparatorOutput() {
        return AbstractContainerMenu.getRedstoneSignalFromContainer(containerView);
    }

    public void dropContents(Level level, BlockPos pos) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
    }
}
