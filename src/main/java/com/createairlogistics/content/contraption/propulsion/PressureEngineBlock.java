package com.createairlogistics.content.contraption.propulsion;

import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.content.pressure.AirTooltip;
import com.createairlogistics.registry.ModBlockEntities;
import com.createairlogistics.registry.ModTags;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Basınç Motoru (Pressure Engine)
 *
 * Her pervanenin tam arkasına takılan, sıkıştırılmış havayla çalışan motor.
 * - FACING: motorun ön flanşının baktığı yön = önündeki pervanenin FACING değeri.
 *   (Pervane {@code enginePos.relative(FACING)} konumunda, aynı yöne bakar.)
 * - ROLL: namlu ekseni etrafında 90° adımlarla döndürme (0 = Taban, 1 = Sağ Duvar, 2 = Tavan, 3 = Sol Duvar).
 * - Kendi küçük hava deposu vardır; gemideki Copper/Netherite Backtank'ler menzili büyütür.
 * - Pervanenin şaft girişi yoktur: dönüş gücü yalnızca bu motordan gelir, bu yüzden
 *   "1 saniye krank çevir, kaynağı sök" açığı yapısal olarak imkânsızdır.
 */
public class PressureEngineBlock extends DirectionalBlock implements IBE<PressureEngineBlockEntity>, IWrenchable {

    public static final MapCodec<PressureEngineBlock> CODEC = simpleCodec(PressureEngineBlock::new);
    public static final IntegerProperty ROLL = IntegerProperty.create("roll", 0, 3);

    private static final VoxelShape SHAPE_FLOOR = Block.box(1.0, 0.0, 1.0, 15.0, 15.0, 15.0);
    private static final VoxelShape SHAPE_CEILING = Block.box(1.0, 1.0, 1.0, 15.0, 16.0, 15.0);
    private static final VoxelShape SHAPE_WALL = Block.box(0.0, 1.0, 0.0, 16.0, 15.0, 16.0);

    public PressureEngineBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(ROLL, 0));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROLL);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(ROLL)) {
            case 2 -> SHAPE_CEILING;
            case 1, 3 -> SHAPE_WALL;
            default -> SHAPE_FLOOR;
        };
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        // 1) Tıklanan blok arkası bize bakan bir pervaneyse ona kilitlen
        Direction towardClicked = context.getClickedFace().getOpposite();
        if (isPropellerRearFacing(level, pos, towardClicked)) {
            return defaultBlockState().setValue(FACING, towardClicked);
        }
        // 2) Komşulardan herhangi biri arkasını bize dönmüş bir pervaneyse ona kilitlen
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (isPropellerRearFacing(level, pos, d)) {
                return defaultBlockState().setValue(FACING, d);
            }
        }
        // 3) Oyuncunun baktığı yöne (pervaneye doğru) bak
        Direction look = context.getHorizontalDirection();
        return defaultBlockState().setValue(FACING,
                (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) ? look.getOpposite() : look);
    }

    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return originalState.setValue(ROLL, (originalState.getValue(ROLL) + 1) & 3);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) {
            return state;
        }
        int roll = state.getValue(ROLL);
        int mirroredRoll = (roll == 1) ? 3 : (roll == 3 ? 1 : roll);
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(ROLL, mirroredRoll);
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

    /** {@code pos}'un {@code dir} yönündeki komşusu, arka yüzü {@code pos}'a bakan bir pervane mi? */
    private static boolean isPropellerRearFacing(Level level, BlockPos pos, Direction dir) {
        BlockState neighbour = level.getBlockState(pos.relative(dir));
        return isMatchingPropeller(neighbour, dir);
    }

    /** Bu motor önündeki pervaneye (Pathfinder veya Boost Propeller) doğru hizalanmış mı? */
    public static boolean isMountedOnPropeller(BlockGetter level, BlockPos enginePos, BlockState engineState) {
        if (!(engineState.getBlock() instanceof PressureEngineBlock)) {
            return false;
        }
        Direction facing = engineState.getValue(FACING);
        BlockState front = level.getBlockState(enginePos.relative(facing));
        return isMatchingPropeller(front, facing);
    }

    public static boolean isMatchingPropeller(BlockState state, Direction expectedFacing) {
        if (expectedFacing == null || !expectedFacing.getAxis().isHorizontal()) {
            return false;
        }
        if (state.getBlock() instanceof PathfinderPropellerBlock) {
            return state.getValue(PathfinderPropellerBlock.FACING) == expectedFacing;
        }
        if (state.getBlock() instanceof BoostPropellerBlock) {
            return state.getValue(BoostPropellerBlock.FACING) == expectedFacing;
        }
        if (state.is(ModTags.EXTRA_THRUSTERS)) {
            if (state.hasProperty(BlockStateProperties.FACING)) {
                return state.getValue(BlockStateProperties.FACING) == expectedFacing;
            }
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return state.getValue(BlockStateProperties.HORIZONTAL_FACING) == expectedFacing;
            }
        }
        return false;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        List<ItemStack> drops = super.getDrops(state, builder);
        BlockEntity be = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof PressureEngineBlockEntity engineBe && engineBe.getAir() > 0) {
            for (ItemStack stack : drops) {
                if (stack.is(asItem())) {
                    stack.set(AllDataComponents.BACKTANK_AIR, engineBe.getAir());
                }
            }
        }
        return drops;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        int storedAir = stack.getOrDefault(AllDataComponents.BACKTANK_AIR, 0);
        if (storedAir > 0 && !level.isClientSide() && level.getBlockEntity(pos) instanceof PressureEngineBlockEntity be) {
            be.setAir(storedAir);
        }
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state, target, level, pos, player);
        if (level.getBlockEntity(pos) instanceof PressureEngineBlockEntity be && be.getAir() > 0) {
            stack.set(AllDataComponents.BACKTANK_AIR, be.getAir());
        }
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        int air = stack.getOrDefault(AllDataComponents.BACKTANK_AIR, 0);
        if (air > 0) {
            AirTooltip.airBar(tooltip, Component.translatable("tooltip.create_air_logistics.air"),
                    air, AirPressure.engineCapacity());
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PressureEngineBlockEntity be ? be.getComparatorOutput() : 0;
    }

    @Override
    public Class<PressureEngineBlockEntity> getBlockEntityClass() {
        return PressureEngineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PressureEngineBlockEntity> getBlockEntityType() {
        return ModBlockEntities.PRESSURE_ENGINE.get();
    }
}
