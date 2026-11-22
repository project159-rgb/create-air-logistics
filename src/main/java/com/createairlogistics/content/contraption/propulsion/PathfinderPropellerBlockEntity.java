package com.createairlogistics.content.contraption.propulsion;

import com.createairlogistics.content.pressure.AirTooltip;
import com.createairlogistics.registry.ModDataComponents;
import com.createairlogistics.registry.ModDataComponents.FlightRouteData;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Pathfinder Propeller BlockEntity:
 * - Filtre gibi yerleştirilen FlightChartItem'ı saklar.
 * - Kinetik değildir: dönüş gücü arkasındaki Basınç Motorundan gelir (dünyada rotor durur, uçarken döner).
 * - Uçuş sırasında geminin gittiği yöne görsel eğilme açılarını (visualSteerYaw, visualSteerPitch) yumuşatarak (lerp) tutar.
 */
public class PathfinderPropellerBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    private ItemStack heldChart = ItemStack.EMPTY;
    private float targetSteerYaw = 0.0f;
    private float targetSteerPitch = 0.0f;
    private float currentSteerYaw = 0.0f;
    private float currentSteerPitch = 0.0f;

    public PathfinderPropellerBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        // Yumuşak görsel eğilme geçişi (Gimbal interpolation)
        currentSteerYaw = Mth.lerp(0.2f, currentSteerYaw, targetSteerYaw);
        currentSteerPitch = Mth.lerp(0.2f, currentSteerPitch, targetSteerPitch);
    }

    public boolean hasChart() {
        return !heldChart.isEmpty();
    }

    public ItemStack getHeldChart() {
        return heldChart;
    }

    public void setHeldChart(ItemStack chart) {
        this.heldChart = chart;
        setChanged();
        sendData();
    }

    public ItemStack removeHeldChart() {
        ItemStack removed = this.heldChart;
        this.heldChart = ItemStack.EMPTY;
        setChanged();
        sendData();
        return removed;
    }

    public FlightRouteData getRouteData() {
        if (heldChart.isEmpty()) return FlightRouteData.EMPTY;
        return heldChart.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
    }

    public boolean hasMountedEngine() {
        return level != null && PathfinderPropellerBlock.hasMountedEngine(level, worldPosition, getBlockState());
    }

    public void setTargetSteeringAngles(float yawDegrees, float pitchDegrees) {
        this.targetSteerYaw = Mth.clamp(yawDegrees, -25.0f, 25.0f);
        this.targetSteerPitch = Mth.clamp(pitchDegrees, -20.0f, 20.0f);
    }

    public float getVisualSteerYaw() {
        return currentSteerYaw;
    }

    public float getVisualSteerPitch() {
        return currentSteerPitch;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        AirTooltip.header(tooltip, Component.translatable("block.create_air_logistics.pathfinder_propeller"));
        boolean engine = hasMountedEngine();
        AirTooltip.status(tooltip, Component.translatable(engine
                ? "tooltip.create_air_logistics.propeller.engine_ok"
                : "tooltip.create_air_logistics.propeller.engine_missing"), engine);
        FlightRouteData route = getRouteData();
        if (route.isComplete()) {
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.propeller.route_ready",
                    route.resolveDockADisplayName(), route.resolveDockBDisplayName()), true);
        } else {
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.propeller.no_route"), false);
        }
        return true;
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        if (!heldChart.isEmpty()) {
            compound.put("HeldChart", heldChart.save(registries));
        }
        compound.putFloat("SteerYaw", targetSteerYaw);
        compound.putFloat("SteerPitch", targetSteerPitch);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        if (compound.contains("HeldChart")) {
            heldChart = ItemStack.parseOptional(registries, compound.getCompound("HeldChart"));
        } else {
            heldChart = ItemStack.EMPTY;
        }
        targetSteerYaw = compound.getFloat("SteerYaw");
        targetSteerPitch = compound.getFloat("SteerPitch");
    }
}
