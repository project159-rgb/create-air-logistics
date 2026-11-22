package com.createairlogistics.content.contraption.propulsion;

import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.content.pressure.AirTooltip;
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
 * Basınç Motoru BlockEntity: motorun kendi küçük hava deposunu tutar.
 * Gemi uçarken hava gemi havuzunda (TravellingAirship) yaşar; gemi sökülünce kalan hava önce
 * motorlara, sonra backtank'lere geri yazılır.
 */
public class PressureEngineBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    private int air = 0;
    private float prevVisualAirRatio = 0.0f;
    private float visualAirRatio = 0.0f;
    private boolean ratioInitialized = false;

    public PressureEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
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
        int cap = AirPressure.engineCapacity();
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

    public void setAir(int air) {
        int clamped = Math.max(0, Math.min(air, AirPressure.engineCapacity()));
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
        return ComparatorUtil.fractionToRedstoneLevel((double) air / AirPressure.engineCapacity());
    }

    public boolean isMounted() {
        return level != null && PressureEngineBlock.isMountedOnPropeller(level, worldPosition, getBlockState());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        AirTooltip.header(tooltip, Component.translatable("block.create_air_logistics.pressure_engine"));
        AirTooltip.airBar(tooltip, Component.translatable("tooltip.create_air_logistics.air"), air, AirPressure.engineCapacity());
        boolean mounted = isMounted();
        AirTooltip.status(tooltip, Component.translatable(mounted
                ? "tooltip.create_air_logistics.engine.mounted"
                : "tooltip.create_air_logistics.engine.not_mounted"), mounted);
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
        air = Mth.clamp(tag.getInt("Air"), 0, AirPressure.engineCapacity());
    }
}
