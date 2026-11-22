package com.createairlogistics.content.contraption.propulsion;

import com.createairlogistics.content.navigation.FlightChartItem;
import com.createairlogistics.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Yön Bulucu Pervane (Pathfinder Propeller):
 * - Mechanical Pathfinder + Propeller ile craftlanır.
 * - Gemide tam 1 adet bulunmak zorundadır (0 veya >1 ise contraption hatası verir).
 * - Şaft girişi YOKTUR: arkasına (FACING.getOpposite()) takılan Basınç Motoru ile döner.
 *   Dönüş gücü dünyadaki bir kaynağa bağlı olmadığından contraption'a dönüşünce "donmuş RPM" açığı oluşmaz.
 * - Ön yüzünde (FACING) 4 kanatlı, geminin hareket ettiği yöne görsel olarak eğilen (Gimbal) pervane bulunur.
 * - Üzerindeki Filtre Yuvasına sağ tıkla Uçuş Haritası (Flight Chart) yerleştirilir.
 */
public class PathfinderPropellerBlock extends DirectionalBlock implements IBE<PathfinderPropellerBlockEntity>, IWrenchable {

    public static final MapCodec<PathfinderPropellerBlock> CODEC = simpleCodec(PathfinderPropellerBlock::new);
    public static final BooleanProperty HAS_CHART = BooleanProperty.create("has_chart");
    private static final VoxelShape SHAPE = Block.box(1.0, 1.0, 1.0, 15.0, 15.0, 15.0);

    public PathfinderPropellerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(HAS_CHART, false));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HAS_CHART);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Yanında ön flanşı bu hücreye bakan bir Basınç Motoru varsa, arka yüz motora oturacak şekilde hizalan
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
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, pos))) {
            return InteractionResult.PASS;
        }
        ItemStack savedChart = ItemStack.EMPTY;
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof PathfinderPropellerBlockEntity be && be.hasChart()) {
            savedChart = be.removeHeldChart();
        }
        InteractionResult result = IWrenchable.super.onSneakWrenched(state, context);
        if (!savedChart.isEmpty()) {
            if (!level.getBlockState(pos).is(this)) {
                if (player != null) {
                    player.getInventory().placeItemBackInInventory(savedChart);
                } else {
                    net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), savedChart);
                }
            } else if (level.getBlockEntity(pos) instanceof PathfinderPropellerBlockEntity stillBe) {
                stillBe.setHeldChart(savedChart);
            }
        }
        return result;
    }

    /** Pervanenin arkasındaki hücre (Basınç Motorunun takılacağı yer). */
    public static BlockPos enginePos(BlockPos propPos, BlockState propState) {
        return propPos.relative(propState.getValue(FACING).getOpposite());
    }

    /** Pervanenin arkasında doğru hizalanmış bir Basınç Motoru var mı? */
    public static boolean hasMountedEngine(BlockGetter level, BlockPos propPos, BlockState propState) {
        if (!(propState.getBlock() instanceof PathfinderPropellerBlock)) {
            return false;
        }
        BlockPos enginePos = enginePos(propPos, propState);
        return PressureEngineBlock.isMountedOnPropeller(level, enginePos, level.getBlockState(enginePos));
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult
    ) {
        if (!(level.getBlockEntity(pos) instanceof PathfinderPropellerBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, pos)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // 1. Elde Uçuş Haritası (FlightChartItem) varsa -> Filtre yuvasına yerleştir!
        if (stack.getItem() instanceof FlightChartItem) {
            if (!level.isClientSide) {
                ItemStack previous = be.getHeldChart();
                be.setHeldChart(stack.copyWithCount(1));
                if (!player.isCreative()) {
                    stack.shrink(1);
                }
                if (!previous.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(previous);
                }
                level.setBlock(pos, state.setValue(HAS_CHART, true), Block.UPDATE_ALL);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // 2. Boş elle tıklanırsa -> Filtre yuvasındaki haritayı geri çıkar!
        if (stack.isEmpty() && be.hasChart()) {
            if (!level.isClientSide) {
                ItemStack extracted = be.removeHeldChart();
                player.setItemInHand(hand, extracted);
                level.setBlock(pos, state.setValue(HAS_CHART, false), Block.UPDATE_ALL);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof PathfinderPropellerBlockEntity be && be.hasChart()) {
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), be.removeHeldChart());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public Class<PathfinderPropellerBlockEntity> getBlockEntityClass() {
        return PathfinderPropellerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PathfinderPropellerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.PATHFINDER_PROPELLER.get();
    }
}
