package com.createairlogistics.content.contraption.propulsion;

import com.createairlogistics.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Takviye Pervanesi (Boost Propeller):
 * - Yön Bulucu Pervane (Pathfinder Propeller) gibi 3x3 kanat açıklığına sahiptir ancak harita yuvası yoktur.
 * - Gemide istediğiniz kadar Takviye Pervanesi kullanabilirsiniz.
 * - Arkasına Basınç Motoru (Pressure Engine) takıldığında aktifleşir; ağır gemilerin (>360 Ağırlık)
 *   yavaşlamasını önler (+360 Ağırlık itki kapasitesi ekler) ve seyir hızını artırır.
 */
public class BoostPropellerBlock extends DirectionalBlock implements IBE<BoostPropellerBlockEntity>, IWrenchable {

    public static final MapCodec<BoostPropellerBlock> CODEC = simpleCodec(BoostPropellerBlock::new);
    private static final VoxelShape SHAPE = Block.box(1.0, 1.0, 1.0, 15.0, 15.0, 15.0);

    public BoostPropellerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState neighbour = context.getLevel().getBlockState(context.getClickedPos().relative(d));
            if (neighbour.getBlock() instanceof PressureEngineBlock
                    && neighbour.getValue(PressureEngineBlock.FACING) == d.getOpposite()) {
                return defaultBlockState().setValue(FACING, d.getOpposite());
            }
        }
        Direction look = context.getHorizontalDirection();
        Direction facing = (context.getPlayer() != null && context.getPlayer().isShiftKeyDown())
                ? look
                : look.getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        Direction current = originalState.getValue(FACING);
        if (!current.getAxis().isHorizontal()) {
            return originalState.setValue(FACING, Direction.NORTH);
        }
        return originalState.setValue(FACING, targetedFace == Direction.DOWN ? current.getCounterClockWise() : current.getClockWise());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !context.getLevel().mayInteract(player, context.getClickedPos()))) {
            return InteractionResult.PASS;
        }
        return IWrenchable.super.onWrenched(state, context);
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !context.getLevel().mayInteract(player, context.getClickedPos()))) {
            return InteractionResult.PASS;
        }
        return IWrenchable.super.onSneakWrenched(state, context);
    }

    public static BlockPos enginePos(BlockPos propPos, BlockState propState) {
        return propPos.relative(propState.getValue(FACING).getOpposite());
    }

    public static boolean hasMountedEngine(BlockGetter level, BlockPos propPos, BlockState propState) {
        if (!(propState.getBlock() instanceof BoostPropellerBlock)) {
            return false;
        }
        BlockPos enginePos = enginePos(propPos, propState);
        return PressureEngineBlock.isMountedOnPropeller(level, enginePos, level.getBlockState(enginePos));
    }

    @Override
    public Class<BoostPropellerBlockEntity> getBlockEntityClass() {
        return BoostPropellerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends BoostPropellerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.BOOST_PROPELLER.get();
    }
}
