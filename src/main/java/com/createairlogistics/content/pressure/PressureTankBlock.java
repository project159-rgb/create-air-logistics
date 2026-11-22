package com.createairlogistics.content.pressure;

import com.createairlogistics.registry.ModBlockEntities;
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
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Basınç Tankı (Pressure Tank)
 *
 * Uzun menzilli uçuşlar için geminin gövdesine (dikey veya yatay) Super Glue ile yapıştırılan,
 * +900 Basınçlı Hava kapasiteli bakır-pirinç hava deposu bloğudur.
 * - Liman Vinci (Sky Dock) gemiye hava basarken Basınç Motorlarıyla birlikte ortak havuzda otomatik dolar.
 * - Gemi söküldüğünde ve blok kırıldığında içinde kalan basınçlı havayı korur.
 */
public class PressureTankBlock extends RotatedPillarBlock implements IBE<PressureTankBlockEntity>, IWrenchable {

    private static final VoxelShape SHAPE_Y = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);
    private static final VoxelShape SHAPE_Z = Block.box(2.0, 2.0, 0.0, 14.0, 14.0, 16.0);
    private static final VoxelShape SHAPE_X = Block.box(0.0, 2.0, 2.0, 16.0, 14.0, 14.0);

    public PressureTankBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Y));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(AXIS, context.getClickedFace().getAxis());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(AXIS)) {
            case X -> SHAPE_X;
            case Z -> SHAPE_Z;
            case Y -> SHAPE_Y;
        };
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

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        List<ItemStack> drops = super.getDrops(state, builder);
        BlockEntity be = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof PressureTankBlockEntity tankBe && tankBe.getAir() > 0) {
            for (ItemStack stack : drops) {
                if (stack.is(asItem())) {
                    stack.set(AllDataComponents.BACKTANK_AIR, tankBe.getAir());
                }
            }
        }
        return drops;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        int storedAir = stack.getOrDefault(AllDataComponents.BACKTANK_AIR, 0);
        if (storedAir > 0 && !level.isClientSide() && level.getBlockEntity(pos) instanceof PressureTankBlockEntity be) {
            be.setAir(storedAir);
        }
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state, target, level, pos, player);
        if (level.getBlockEntity(pos) instanceof PressureTankBlockEntity be && be.getAir() > 0) {
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
                    air, PressureTankBlockEntity.getCapacity());
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PressureTankBlockEntity be ? be.getComparatorOutput() : 0;
    }

    @Override
    public Class<PressureTankBlockEntity> getBlockEntityClass() {
        return PressureTankBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PressureTankBlockEntity> getBlockEntityType() {
        return ModBlockEntities.PRESSURE_TANK.get();
    }
}
