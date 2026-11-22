package com.createairlogistics.content.contraption;

import com.createairlogistics.content.contraption.balloon.AirshipSuperHeaterBlock;
import com.createairlogistics.content.contraption.balloon.CanvasBalloonBlock;
import com.createairlogistics.content.contraption.propulsion.BoostPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlock;
import com.createairlogistics.content.dock.ShipPortBlock;
import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.content.pressure.PressureTankBlock;
import com.createairlogistics.registry.ModBlocks;
import com.createairlogistics.registry.ModContraptionTypes;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class AirshipContraption extends Contraption {

    private Direction initialPortFacing = Direction.NORTH;
    private Direction shipForwardFacing = Direction.NORTH;
    private List<StructureTemplate.StructureBlockInfo> renderableSpecialBlocks;
    private Set<BlockPos> activeBoostPropellers;
    private Set<BlockPos> activeEngines;
    private int syncedClientStructureVersion = -1;

    public AirshipContraption() {
        super();
    }

    public void invalidateRenderCache() {
        this.renderableSpecialBlocks = null;
        this.activeBoostPropellers = null;
        this.activeEngines = null;
    }

    @Override
    public void resetClientContraption() {
        this.syncedClientStructureVersion = -1;
        super.resetClientContraption();
    }

    public int getSyncedClientStructureVersion() {
        return syncedClientStructureVersion;
    }

    public void setSyncedClientStructureVersion(int syncedClientStructureVersion) {
        this.syncedClientStructureVersion = syncedClientStructureVersion;
    }

    @Override
    public boolean assemble(Level level, BlockPos pos) throws AssemblyException {
        BlockState anchorState = level.getBlockState(pos);
        if (anchorState.hasProperty(ShipPortBlock.FACING)) {
            this.initialPortFacing = anchorState.getValue(ShipPortBlock.FACING);
            this.shipForwardFacing = this.initialPortFacing.getClockWise();
        }

        if (!searchMovedStructure(level, pos, null)) {
            return false;
        }

        for (StructureTemplate.StructureBlockInfo info : this.blocks.values()) {
            if (info.state().is(ModBlocks.PATHFINDER_PROPELLER.get())
                    && info.state().hasProperty(PathfinderPropellerBlock.FACING)) {
                Direction propFacing = info.state().getValue(PathfinderPropellerBlock.FACING);
                if (propFacing.getAxis().isHorizontal()) {
                    // Propeller faces aft; bow is opposite
                    this.shipForwardFacing = propFacing.getOpposite();
                }
                break;
            }
        }

        invalidateRenderCache();
        startMoving(level);
        expandBoundsAroundAxis(Direction.Axis.Y);
        return !this.blocks.isEmpty();
    }

    @Override
    protected boolean isAnchoringBlockAt(BlockPos pos) {
        // ShipPortBlock at anchor IS part of the moving airship contraption!
        return false;
    }

    @Override
    protected boolean moveBlock(Level world, Direction forcedDirection, Queue<BlockPos> frontier, Set<BlockPos> visited)
            throws AssemblyException {
        BlockPos current = frontier.peek();
        boolean moved = super.moveBlock(world, forcedDirection, frontier, visited);
        if (moved && current != null && !world.isOutsideBuildHeight(current) && world.isLoaded(current)) {
            BlockState state = world.getBlockState(current);
            if (CanvasBalloonBlock.isBalloonBlock(state)) {
                AirshipAssemblyValidator.enqueueDiagonalGluedBalloons(
                        world, current, state, frontier, visited, null, null, false);
            }
        }
        return moved;
    }

    @Override
    protected boolean movementAllowed(BlockState state, Level level, BlockPos pos) {
        if (state.getBlock() instanceof SkyDockBlock) {
            return false;
        }
        return super.movementAllowed(state, level, pos);
    }

    @Override
    public boolean canBeStabilized(Direction facing, BlockPos localPos) {
        return false;
    }

    @Override
    public ContraptionType getType() {
        return ModContraptionTypes.AIRSHIP.get();
    }

    public Direction getInitialPortFacing() {
        return initialPortFacing;
    }

    public void setInitialPortFacing(Direction initialPortFacing) {
        this.initialPortFacing = initialPortFacing;
    }

    public Direction getShipForwardFacing() {
        return shipForwardFacing;
    }

    public void setShipForwardFacing(Direction shipForwardFacing) {
        this.shipForwardFacing = shipForwardFacing;
    }

    /**
     * Finds the local StructureBlockInfo of the PathfinderPropellerBlock on this contraption, if any.
     */
    public StructureTemplate.StructureBlockInfo findPropellerBlockInfo() {
        for (StructureTemplate.StructureBlockInfo info : this.blocks.values()) {
            if (info.state().is(ModBlocks.PATHFINDER_PROPELLER.get())) {
                return info;
            }
        }
        return null;
    }

    private void ensureRenderCache() {
        if (this.renderableSpecialBlocks != null
                && this.activeBoostPropellers != null
                && this.activeEngines != null) {
            return;
        }
        List<StructureTemplate.StructureBlockInfo> special = new ArrayList<>();
        Set<BlockPos> activeBoosts = new HashSet<>();
        Set<BlockPos> activeEngs = new HashSet<>();
        Direction pathfinderFacing = AirshipAssemblyValidator.findUniquePathfinderFacing(this.blocks);

        for (StructureTemplate.StructureBlockInfo info : this.blocks.values()) {
            BlockState state = info.state();
            Block block = state.getBlock();
            if (block instanceof PathfinderPropellerBlock
                    || block instanceof BoostPropellerBlock
                    || block instanceof PressureTankBlock
                    || block instanceof PressureEngineBlock
                    || block instanceof AirshipSuperHeaterBlock) {
                special.add(info);
            }
            if (block instanceof BoostPropellerBlock
                    && AirshipAssemblyValidator.isActiveExtraThrusterInContraption(
                            info.pos(), state, this.blocks, pathfinderFacing)) {
                activeBoosts.add(info.pos());
            } else if (block instanceof PressureEngineBlock
                    && state.hasProperty(PressureEngineBlock.FACING)) {
                Direction facing = state.getValue(PressureEngineBlock.FACING);
                BlockPos localPropPos = info.pos().relative(facing);
                StructureTemplate.StructureBlockInfo front = this.blocks.get(localPropPos);
                if (front != null
                        && PressureEngineBlock.isMatchingPropeller(front.state(), facing)
                        && (front.state().getBlock() instanceof PathfinderPropellerBlock
                                || AirshipAssemblyValidator.isActiveExtraThrusterInContraption(
                                        localPropPos, front.state(), this.blocks, pathfinderFacing))) {
                    activeEngs.add(info.pos());
                }
            }
        }

        this.renderableSpecialBlocks = List.copyOf(special);
        this.activeBoostPropellers = Set.copyOf(activeBoosts);
        this.activeEngines = Set.copyOf(activeEngs);
    }

    public List<StructureTemplate.StructureBlockInfo> getRenderableSpecialBlocks() {
        ensureRenderCache();
        return this.renderableSpecialBlocks;
    }

    public boolean isBoostPropellerActive(BlockPos localPos) {
        ensureRenderCache();
        return this.activeBoostPropellers.contains(localPos);
    }

    public boolean isEngineActive(BlockPos localPos) {
        ensureRenderCache();
        return this.activeEngines.contains(localPos);
    }

    @Override
    public CompoundTag writeNBT(HolderLookup.Provider registries, boolean spawnPacket) {
        CompoundTag tag = super.writeNBT(registries, spawnPacket);
        tag.putString("InitialPortFacing", initialPortFacing.getName());
        tag.putString("ShipForwardFacing", shipForwardFacing.getName());
        return tag;
    }

    @Override
    public void readNBT(Level level, CompoundTag tag, boolean clientData) {
        super.readNBT(level, tag, clientData);
        invalidateRenderCache();
        this.syncedClientStructureVersion = -1;
        if (tag.contains("InitialPortFacing")) {
            Direction d = Direction.byName(tag.getString("InitialPortFacing"));
            if (d != null && d.getAxis().isHorizontal()) {
                this.initialPortFacing = d;
            }
        }
        if (tag.contains("ShipForwardFacing")) {
            Direction d = Direction.byName(tag.getString("ShipForwardFacing"));
            if (d != null && d.getAxis().isHorizontal()) {
                this.shipForwardFacing = d;
            }
        }
    }
}
