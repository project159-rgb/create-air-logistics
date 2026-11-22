package com.createairlogistics.content.pressure;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.ComparatorUtil;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Basınç Tankı BlockEntity (Pressure Tank BlockEntity):
 * - +900 Basınçlı Hava (Create Copper Backtank ile aynı kapasite) depolar.
 * - Gemiye yapıştırıldığında ortak basınç havuzuna katılır; gemi söküldüğünde kalan havayı saklar.
 */
public class PressureTankBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    private int air = 0;
    private float prevVisualAirRatio = 0.0f;
    private float visualAirRatio = 0.0f;
    private boolean ratioInitialized = false;

    public PressureTankBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && level.isClientSide()) {
            float target = getTargetAirRatio();
            if (!ratioInitialized) {
                prevVisualAirRatio = target;
                visualAirRatio = target;
                ratioInitialized = true;
            } else {
                prevVisualAirRatio = visualAirRatio;
                visualAirRatio = Mth.lerp(0.2f, visualAirRatio, target);
            }
        }
    }

    public float getTargetAirRatio() {
        int cap = getCapacity();
        return cap > 0 ? Mth.clamp((float) air / (float) cap, 0.0f, 1.0f) : 0.0f;
    }

    public float getVisualAirRatio(float partialTicks) {
        if (!ratioInitialized) {
            return getTargetAirRatio();
        }
        return Mth.lerp(partialTicks, prevVisualAirRatio, visualAirRatio);
    }

    public int getAir() {
        return air;
    }

    public static int getCapacity() {
        return AirPressure.backtankAir();
    }

    public void setAir(int air) {
        int clamped = Math.max(0, Math.min(air, getCapacity()));
        if (clamped != this.air) {
            this.air = clamped;
            setChanged();
            sendData();
            if (level != null) {
                level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            }
        }
    }

    public int getComparatorOutput() {
        return ComparatorUtil.fractionToRedstoneLevel((double) air / getCapacity());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        AirTooltip.header(tooltip, Component.translatable("block.create_air_logistics.pressure_tank"));
        AirTooltip.airBar(tooltip, Component.translatable("tooltip.create_air_logistics.air"), air, getCapacity());
        AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.pressure_tank.info", getCapacity()), true);
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Air", air);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        air = Mth.clamp(tag.getInt("Air"), 0, getCapacity());
    }
}
