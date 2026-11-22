package com.createairlogistics.content.pressure;

import com.createairlogistics.content.contraption.AirshipAssemblyValidator;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlock;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlockEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.equipment.armor.BacktankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bir geminin hava depolarını (pervaneye takılı Basınç Motorları + Basınç Tankları + Copper/Netherite Backtank'ler) tarar ve
 * gemi sökülünce havuzda kalan havayı bu bloklara geri yazar.
 */
public final class AirshipAirStorage {

    private static final String BACKTANK_ENCHANT_KEY = "CapacityEnchantment";

    private AirshipAirStorage() {
    }

    public record Scan(int mountedEngines, int unmountedEngines, int backtanks, int capacity, int storedAir) {
        public static final Scan EMPTY = new Scan(0, 0, 0, 0, 0);
    }

    /** Dünyadaki (henüz kurulmamış) gemi bloklarını tarar. */
    public static Scan scan(Level level, Set<BlockPos> shipBlocks) {
        int mounted = 0;
        int unmounted = 0;
        int tanks = 0;
        long capacity = 0;
        long stored = 0;
        Direction pathfinderFacing = AirshipAssemblyValidator.findUniquePathfinderFacing(level, shipBlocks);
        for (BlockPos pos : shipBlocks) {
            if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof PressureEngineBlock) {
                Direction facing = state.getValue(PressureEngineBlock.FACING);
                BlockPos propPos = pos.relative(facing);
                if (!shipBlocks.contains(propPos) || !PressureEngineBlock.isMountedOnPropeller(level, pos, state)) {
                    unmounted++;
                    continue;
                }
                BlockState propState = level.getBlockState(propPos);
                boolean activePropeller = (propState.getBlock() instanceof PathfinderPropellerBlock)
                        || AirshipAssemblyValidator.isActiveExtraThruster(level, propPos, propState, shipBlocks, pathfinderFacing);
                if (!activePropeller) {
                    unmounted++;
                    continue;
                }
                mounted++;
                capacity += AirPressure.engineCapacity();
                if (level.getBlockEntity(pos) instanceof PressureEngineBlockEntity engine) {
                    stored += engine.getAir();
                }
                continue;
            }
            if (state.getBlock() instanceof PressureTankBlock) {
                tanks++;
                int max = PressureTankBlockEntity.getCapacity();
                capacity += max;
                if (level.getBlockEntity(pos) instanceof PressureTankBlockEntity tankBe) {
                    stored += Math.min(max, tankBe.getAir());
                }
                continue;
            }
            if (state.hasBlockEntity() && level.getBlockEntity(pos) instanceof BacktankBlockEntity backtank) {
                tanks++;
                int max = AirPressure.backtankAir(readBacktankEnchant(backtank, level));
                capacity += max;
                stored += Math.min(max, backtank.getAirLevel());
            }
        }
        return new Scan(mounted, unmounted, tanks, (int) Math.min(Integer.MAX_VALUE, capacity),
                (int) Math.min(Integer.MAX_VALUE, Math.min(stored, capacity)));
    }

    private static int readBacktankEnchant(BacktankBlockEntity backtank, Level level) {
        CompoundTag tag = backtank.saveWithoutMetadata(level.registryAccess());
        return tag.getInt(BACKTANK_ENCHANT_KEY);
    }

    /**
     * Kalan havayı önce pervaneye takılı motorlara, sonra Basınç Tanklarına ve Backtank'lere dağıtarak
     * contraption blok verisine yazar.
     */
    public static void writeBack(Contraption contraption, double poolAir) {
        Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = contraption.getBlocks();
        Direction pathfinderFacing = AirshipAssemblyValidator.findUniquePathfinderFacing(blocks);
        List<CompoundTag> engineTags = new ArrayList<>();
        List<CompoundTag> pressureTankTags = new ArrayList<>();
        List<CompoundTag> backtankTags = new ArrayList<>();

        for (StructureTemplate.StructureBlockInfo info : blocks.values()) {
            CompoundTag nbt = info.nbt();
            if (nbt == null) {
                continue;
            }
            BlockState state = info.state();
            if (state.getBlock() instanceof PressureEngineBlock) {
                Direction facing = state.getValue(PressureEngineBlock.FACING);
                BlockPos localPropPos = info.pos().relative(facing);
                StructureTemplate.StructureBlockInfo front = blocks.get(localPropPos);
                boolean mounted = front != null
                        && PressureEngineBlock.isMatchingPropeller(front.state(), facing)
                        && (front.state().getBlock() instanceof PathfinderPropellerBlock
                                || AirshipAssemblyValidator.isActiveExtraThrusterInContraption(
                                        localPropPos, front.state(), blocks, pathfinderFacing));
                if (mounted) {
                    engineTags.add(nbt);
                }
            } else if (state.getBlock() instanceof PressureTankBlock) {
                pressureTankTags.add(nbt);
            } else if (nbt.contains(BACKTANK_ENCHANT_KEY) && nbt.contains("Air")) {
                backtankTags.add(nbt);
            }
        }

        int remaining = (int) Math.floor(Math.max(0.0, poolAir));
        for (CompoundTag tag : engineTags) {
            int put = Math.min(remaining, AirPressure.engineCapacity());
            tag.putInt("Air", put);
            remaining -= put;
        }
        for (CompoundTag tag : pressureTankTags) {
            int put = Math.min(remaining, PressureTankBlockEntity.getCapacity());
            tag.putInt("Air", put);
            remaining -= put;
        }
        for (CompoundTag tag : backtankTags) {
            int put = Math.min(remaining, AirPressure.backtankAir(tag.getInt(BACKTANK_ENCHANT_KEY)));
            tag.putInt("Air", put);
            remaining -= put;
        }
    }
}
