package com.createairlogistics.content.dock;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Gemi Yanaşma Kapısı (Ship Port Block)
 *
 * Geminin sağ veya sol bordasına (yan dış duvarına) yerleştirilen kapı bloğudur.
 * Limandaki SkyDock (Liman Vinci) ile arasında 1 blok boşluk olacak şekilde
 * (veya bitişik) hizalanır; gemi kurulumu/sökümü SkyDock üzerinden tetiklenir.
 * Wrench ile döndürülebilir ve Shift+Wrench ile sökülebilir.
 */
public class ShipPortBlock extends HorizontalDirectionalBlock implements IWrenchable {

    public static final MapCodec<ShipPortBlock> CODEC = simpleCodec(ShipPortBlock::new);

    public ShipPortBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
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
    protected ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult
    ) {
        if (AllItems.WRENCH.isIn(stack) || stack.getItem() instanceof WrenchItem) {
            if (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, pos)) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            UseOnContext ctx = new UseOnContext(level, player, hand, stack, hitResult);
            InteractionResult result = player.isShiftKeyDown() ? onSneakWrenched(state, ctx) : onWrenched(state, ctx);
            if (result.consumesAction()) {
                return ItemInteractionResult.sidedSuccess(level.isClientSide());
            }
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
