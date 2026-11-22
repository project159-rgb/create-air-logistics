package com.createairlogistics.content.dock;

import com.createairlogistics.content.navigation.FlightChartItem;
import com.createairlogistics.content.navigation.SkyNetworkSavedData;
import com.createairlogistics.registry.ModBlockEntities;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.item.ItemHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Liman Vinci (Sky Dock Block)
 *
 * İskelenin kenarına yerleştirilen, yan yanaşma (side-docking) destekli ana liman bloğudur.
 * - Boş elle sağ tık: Liman menüsü (mod, kalkış şartı, checklist, gemiyi kur/sök).
 * - Create Anahtarı (Wrench), ön yüz HARİÇ bir yüze: o yüzde şaft soketi açılır (tek soket; başka yüze
 *   tıklamak soketi taşır, soketli yüze tekrar tıklamak kapatır). Soketten gelen dönüş, Copper Backtank ile
 *   birebir aynı hız ve SU maliyetiyle limanın hava tankını doldurur.
 * - Wrench, ön yüze (gemi bağlantı ağzı): blok Create standardında döner. Gemi yanaşıkken döndürülemez.
 * - Shift + Wrench: Create standardı (bloğu söküp al).
 */
public class SkyDockBlock extends KineticBlock implements IBE<SkyDockBlockEntity>, IWrenchable {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DockShaftSocket> SHAFT = EnumProperty.create("shaft", DockShaftSocket.class);

    public SkyDockBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(SHAFT, DockShaftSocket.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAFT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    // ---------------------------------------------------------------- Kinetik (şaft soketi)

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        Direction socket = state.getValue(SHAFT).direction();
        return socket != null && socket == face;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        Direction socket = state.getValue(SHAFT).direction();
        return socket != null ? socket.getAxis() : Direction.Axis.Y;
    }

    @Override
    protected boolean areStatesKineticallyEquivalent(BlockState oldState, BlockState newState) {
        // Soket aynı eksende karşı yüze taşınsa bile bağlantı yüzü değişir → ağ yeniden kurulmalı
        return super.areStatesKineticallyEquivalent(oldState, newState)
                && oldState.getValue(SHAFT) == newState.getValue(SHAFT);
    }

    // ---------------------------------------------------------------- Wrench

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, pos))) {
            return InteractionResult.PASS;
        }
        Direction face = context.getClickedFace();
        Direction facing = state.getValue(FACING);

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (face == facing) {
            // Ön yüz: Create standardında döndür (gemi yanaşıkken değil)
            if (level instanceof ServerLevel serverLevel
                    && SkyNetworkSavedData.get(serverLevel).getDockedAirshipAt(pos).isPresent()) {
                return InteractionResult.SUCCESS;
            }
            BlockState rotated = rotate(state, Rotation.CLOCKWISE_90);
            KineticBlockEntity.switchToBlockState(level, pos, rotated);
            IWrenchable.playRotateSound(level, pos);
            if (level instanceof ServerLevel serverLevel) {
                SkyNetworkSavedData.get(serverLevel).onDockRotated(serverLevel, pos, rotated.getValue(FACING));
            }
            return InteractionResult.SUCCESS;
        }

        // Diğer yüzler: şaft soketini aç / taşı / kapat
        DockShaftSocket current = state.getValue(SHAFT);
        DockShaftSocket next = current.direction() == face ? DockShaftSocket.NONE : DockShaftSocket.of(face);
        KineticBlockEntity.switchToBlockState(level, pos, state.setValue(SHAFT, next));
        if (level.getBlockEntity(pos) instanceof SkyDockBlockEntity dockBe) {
            dockBe.detachKinetics();
            dockBe.removeSource();
            dockBe.updateSpeed = true;
            dockBe.attachKinetics();
            dockBe.sendData();
        }
        IWrenchable.playRotateSound(level, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !context.getLevel().mayInteract(player, context.getClickedPos()))) {
            return InteractionResult.PASS;
        }
        return super.onSneakWrenched(state, context);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)))
                .setValue(SHAFT, state.getValue(SHAFT).rotate(rotation));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        Direction socket = state.getValue(SHAFT).direction();
        BlockState mirrored = state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
        if (socket != null && socket.getAxis().isHorizontal()) {
            mirrored = mirrored.setValue(SHAFT, DockShaftSocket.of(mirror.mirror(socket)));
        }
        return mirrored;
    }

    // ---------------------------------------------------------------- Etkileşim

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult
    ) {
        // 1. Oyuncu elinde Create Anahtarı (Wrench) tutuyorsa menüyü açma; doğrudan şaft soketi aç/kapat veya döndür!
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

        // 2. Oyuncu elinde Uçuş Haritası (Flight Chart) tutarken sağ tıklarsa menüyü açma; doğrudan haritaya limanı kaydet!
        if (stack.getItem() instanceof FlightChartItem chartItem) {
            InteractionResult result = chartItem.useOn(new UseOnContext(level, player, hand, stack, hitResult));
            if (result.consumesAction()) {
                return ItemInteractionResult.sidedSuccess(level.isClientSide());
            }
        }

        // 3. Oyuncu elinde isimlendirilmiş İsim Etiketi (Name Tag) tutarken sağ tıklarsa istasyon adını kaydet!
        if (stack.is(Items.NAME_TAG) && stack.has(DataComponents.CUSTOM_NAME)) {
            if (player.isSpectator() || !player.mayBuild() || !level.mayInteract(player, pos)) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SkyDockBlockEntity be) {
                be.setConfiguration(stack.getHoverName().getString(), be.getPackageFilter());
                if (!player.isCreative()) {
                    stack.shrink(1);
                }
                level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.4f, 1.4f);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }

        // 4. Açık şaft soketi olan yüze elinde Şaft/Çark (BlockItem) ile tıklanırsa menüyü açmak yerine bloğu yerleştir!
        Direction socketDir = state.getValue(SHAFT).direction();
        if (socketDir != null && socketDir == hitResult.getDirection() && stack.getItem() instanceof BlockItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player.isSpectator()) {
            return InteractionResult.PASS;
        }
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        if (main.getItem() instanceof FlightChartItem || off.getItem() instanceof FlightChartItem
                || AllItems.WRENCH.isIn(main) || AllItems.WRENCH.isIn(off)
                || main.getItem() instanceof WrenchItem || off.getItem() instanceof WrenchItem
                || (off.is(Items.NAME_TAG) && off.has(DataComponents.CUSTOM_NAME))) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            com.createairlogistics.client.ClientScreenHelper.openSkyDockScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        // Kırılan bir liman yeniden yerleştirildiğinde kırık kaydı temizlenir ve bağlı rotalar yeni yöne hizalanır
        // (yön değişmediyse onDockRotated ucuz bir no-op'tur).
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel && state.hasProperty(FACING)) {
            SkyNetworkSavedData.get(serverLevel).onDockRotated(serverLevel, pos, state.getValue(FACING));
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof SkyDockBlockEntity dockBe) {
                ItemHelper.dropContents(level, pos, dockBe.getItemBuffer());
            }
            if (level instanceof ServerLevel serverLevel) {
                SkyNetworkSavedData network = SkyNetworkSavedData.get(serverLevel);
                network.onDockRemoved(pos);
                network.getDockedAirshipAt(pos).ifPresent(ship ->
                        network.disassembleAirship(serverLevel, ship.getAirshipId(), null)
                );
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ---------------------------------------------------------------- Comparator: hava tankı doluluğu

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SkyDockBlockEntity be ? be.getComparatorOutput() : 0;
    }

    @Override
    public Class<SkyDockBlockEntity> getBlockEntityClass() {
        return SkyDockBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends SkyDockBlockEntity> getBlockEntityType() {
        return ModBlockEntities.SKY_DOCK.get();
    }
}
