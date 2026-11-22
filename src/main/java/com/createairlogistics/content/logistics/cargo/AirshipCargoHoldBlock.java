package com.createairlogistics.content.logistics.cargo;

import com.createairlogistics.registry.ModBlockEntities;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.glue.SuperGlueItem;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Açılır Kapaklı Kargo Ambarı (Airship Cargo Hold):
 * - 9 Slot kapasitelidir: Normal eşya koyulursa 9 Stack, Create v0.6 Karton Paketi (Cardboard Package)
 *   koyulursa 9x9 = 81 Stack eşya taşır!
 * - Hem dünyada normal blokken hem de gemi Contraption modundayken sağ tıklanarak manuel açılabilir.
 * - Gemi limana yanaştığında veya oyuncu menüyü açtığında üst kapakları (OPEN=true) açılır.
 */
public class AirshipCargoHoldBlock extends Block implements EntityBlock, IWrenchable {

    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    public static final int WEIGHT = 30;

    public AirshipCargoHoldBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPEN);
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
            UseOnContext ctx = new UseOnContext(level, player, hand, stack, hitResult);
            InteractionResult result = player.isShiftKeyDown() ? onSneakWrenched(state, ctx) : onWrenched(state, ctx);
            if (result.consumesAction()) {
                return ItemInteractionResult.sidedSuccess(level.isClientSide());
            }
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (AllItems.SUPER_GLUE.isIn(stack) || stack.getItem() instanceof SuperGlueItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        if (AllItems.WRENCH.isIn(main) || AllItems.WRENCH.isIn(off)
                || main.getItem() instanceof WrenchItem || off.getItem() instanceof WrenchItem
                || AllItems.SUPER_GLUE.isIn(main) || AllItems.SUPER_GLUE.isIn(off)
                || main.getItem() instanceof SuperGlueItem || off.getItem() instanceof SuperGlueItem) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof AirshipCargoHoldBlockEntity holdBe && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(holdBe);
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AirshipCargoHoldBlockEntity(ModBlockEntities.AIRSHIP_CARGO_HOLD.get(), pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof AirshipCargoHoldBlockEntity holdBe) {
                holdBe.dropContents(level, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AirshipCargoHoldBlockEntity holdBe ? holdBe.getComparatorOutput() : 0;
    }
}
