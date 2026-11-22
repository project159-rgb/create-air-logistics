package com.createairlogistics.content.contraption.propulsion;

import com.createairlogistics.content.pressure.AirTooltip;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Takviye Pervanesi BlockEntity (Boost Propeller BlockEntity):
 * - Arkasına Basınç Motoru takıldığında ağır gemilere ek +120 Ağırlık itki kapasitesi ve hız bonusu sağlar.
 * - Mühendis Gözlüğü (Goggles) ile bakıldığında motor bağlantı durumunu gösterir.
 */
public class BoostPropellerBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    public BoostPropellerBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    public boolean hasMountedEngine() {
        return level != null && BoostPropellerBlock.hasMountedEngine(level, worldPosition, getBlockState());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        AirTooltip.header(tooltip, Component.translatable("block.create_air_logistics.boost_propeller"));
        boolean engine = hasMountedEngine();
        AirTooltip.status(tooltip, Component.translatable(engine
                ? "tooltip.create_air_logistics.propeller.engine_ok"
                : "tooltip.create_air_logistics.propeller.engine_missing"), engine);
        AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.boost_propeller.info"), engine);
        return true;
    }
}
