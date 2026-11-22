package com.createairlogistics.content.navigation;

import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.content.dock.SkyDockBlockEntity;
import com.createairlogistics.registry.ModAdvancements;
import com.createairlogistics.registry.ModDataComponents;
import com.createairlogistics.registry.ModDataComponents.FlightRouteData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Uçuş Haritası (Flight Chart):
 * 1. İki SkyDock bloğuna sağ tıklanarak limanları birbirine bağlar.
 * 2. Herhangi bir bloğa sağ tıklanarak (Liman A seçildikten sonra her zaman) 3B sanal geçiş noktaları (Waypoints) ekler.
 * 3. Havaya Sağ Tık yapılarak Uçuş Haritası Menüsü (FlightChartScreen) açılır; buradan hem tek tıkla mevcut konum
 *    veya X/Y/Z koordinatı ara nokta olarak eklenebilir, hem de mevcut ara noktalar [X] ile silinebilir.
 * 4. Tamamlanan harita Pathfinder Propeller üzerindeki filtre yuvasına sağ tıkla yerleştirilir.
 */
public class FlightChartItem extends Item {

    public FlightChartItem(Properties properties) {
        super(properties.stacksTo(1).component(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clickedPos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null || player.isSpectator() || !player.mayBuild()) return InteractionResult.PASS;

        BlockState state = level.getBlockState(clickedPos);
        FlightRouteData data = stack.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);

        // 1. Liman Bloğuna (SkyDockBlock) tıklandıysa (Normal Sağ Tık VEYA Shift + Sağ Tık fark etmeksizin):
        if (state.getBlock() instanceof SkyDockBlock) {
            GlobalPos globalPos = GlobalPos.of(level.dimension(), clickedPos.immutable());
            String customDockName = (level.getBlockEntity(clickedPos) instanceof SkyDockBlockEntity dockBe && dockBe.hasCustomName())
                    ? dockBe.getCustomName()
                    : "";

            // Henüz A limanı seçilmediyse VEYA daha önce A->B rotası tamamlanmışsa yeni A limanı olarak başlat
            if (data.dockA().isEmpty() || data.dockB().isPresent()) {
                FlightRouteData updatedA = data.withDockA(globalPos, customDockName);
                stack.set(ModDataComponents.FLIGHT_ROUTE.get(), updatedA);
                if (!level.isClientSide) {
                    player.displayClientMessage(
                            Component.translatable("message.create_air_logistics.chart.dock_a_set", updatedA.resolveDockADisplayName())
                                    .withStyle(ChatFormatting.GOLD),
                            true
                    );
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            } else {
                // A limanı seçili, şimdi 2. tıklanan limanı B limanı (Hedef) olarak kaydet!
                if (!data.dockA().get().dimension().equals(level.dimension())) {
                    if (!level.isClientSide) {
                        player.displayClientMessage(
                                Component.translatable("create_air_logistics.assembly.error.wrong_dimension")
                                        .withStyle(ChatFormatting.RED),
                                true
                        );
                    }
                    return InteractionResult.FAIL;
                }
                if (data.dockA().get().equals(globalPos)) {
                    if (!level.isClientSide) {
                        player.displayClientMessage(
                                Component.translatable("message.create_air_logistics.chart.same_dock_error")
                                        .withStyle(ChatFormatting.RED),
                                true
                        );
                    }
                    return InteractionResult.FAIL;
                }
                FlightRouteData completed = data.withDockB(globalPos, customDockName);
                stack.set(ModDataComponents.FLIGHT_ROUTE.get(), completed);
                if (!level.isClientSide) {
                    if (player instanceof ServerPlayer sp) {
                        ModAdvancements.award(sp, "chart_course");
                    }
                    player.displayClientMessage(
                            Component.translatable("message.create_air_logistics.chart.route_completed",
                                            completed.resolveDockADisplayName(), completed.resolveDockBDisplayName(), completed.waypoints().size())
                                    .withStyle(ChatFormatting.GREEN),
                            true
                    );
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }

        // 2. Liman A seçilmişse (Liman B seçilmiş olsa bile!) zemine/binaya sağ tıklamak o bloğun +irtifa üstüne Waypoint ekler!
        if (data.dockA().isPresent()) {
            if (player.isShiftKeyDown() && !data.waypoints().isEmpty()) {
                ArrayList<BlockPos> updatedList = new ArrayList<>(data.waypoints());
                updatedList.removeLast();
                FlightRouteData updated = new FlightRouteData(
                        data.dockA(), data.dockAName(), data.dockB(), data.dockBName(), updatedList, data.altitudeOffset()
                );
                stack.set(ModDataComponents.FLIGHT_ROUTE.get(), updated);
                if (!level.isClientSide) {
                    player.displayClientMessage(
                            Component.translatable("message.create_air_logistics.chart.waypoint_removed", updated.waypoints().size())
                                    .withStyle(ChatFormatting.YELLOW),
                            true
                    );
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }

            if (data.waypoints().size() >= FlightRouteData.MAX_WAYPOINTS) {
                if (!level.isClientSide) {
                    player.displayClientMessage(
                            Component.translatable("message.create_air_logistics.chart.waypoint_limit",
                                            FlightRouteData.MAX_WAYPOINTS)
                                    .withStyle(ChatFormatting.RED),
                            true
                    );
                }
                return InteractionResult.FAIL;
            }

            BlockPos rawWaypoint = clickedPos.above(data.altitudeOffset());
            int clampedY = Math.clamp(rawWaypoint.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() + 64);
            BlockPos waypointPos = new BlockPos(rawWaypoint.getX(), clampedY, rawWaypoint.getZ());
            FlightRouteData updated = data.withAddedWaypoint(waypointPos);
            stack.set(ModDataComponents.FLIGHT_ROUTE.get(), updated);
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("message.create_air_logistics.chart.waypoint_added",
                                        updated.waypoints().size(), waypointPos.getX(), waypointPos.getY(), waypointPos.getZ())
                                .withStyle(ChatFormatting.AQUA),
                        true
                );
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player.isSpectator()) return InteractionResultHolder.pass(player.getItemInHand(hand));
        ItemStack stack = player.getItemInHand(hand);
        FlightRouteData data = stack.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);

        // Havaya Shift + Sağ Tık -> Hızlı İrtifa Ofseti değiştirme (+10 -> +15 -> +25 -> +40)
        if (player.isShiftKeyDown()) {
            int nextOffset = switch (data.altitudeOffset()) {
                case 10 -> 15;
                case 15 -> 25;
                case 25 -> 40;
                default -> 10;
            };
            stack.set(ModDataComponents.FLIGHT_ROUTE.get(), data.withAltitudeOffset(nextOffset));
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("message.create_air_logistics.chart.altitude_changed", nextOffset)
                                .withStyle(ChatFormatting.YELLOW),
                        true
                );
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // Havaya Normal Sağ Tık -> Uçuş Haritası Açılır Menüsünü (FlightChartScreen) aç!
        if (level.isClientSide) {
            com.createairlogistics.client.ClientScreenHelper.openFlightChartScreen();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        FlightRouteData data = stack.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
        if (data.dockA().isEmpty()) {
            tooltip.add(Component.translatable("tooltip.create_air_logistics.chart.empty").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.create_air_logistics.chart.dock_a", data.resolveDockADisplayName()).withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("tooltip.create_air_logistics.chart.waypoints", data.waypoints().size(), data.altitudeOffset()).withStyle(ChatFormatting.AQUA));
            if (data.dockB().isPresent()) {
                tooltip.add(Component.translatable("tooltip.create_air_logistics.chart.dock_b", data.resolveDockBDisplayName()).withStyle(ChatFormatting.GREEN));
            } else {
                tooltip.add(Component.translatable("tooltip.create_air_logistics.chart.pending_b").withStyle(ChatFormatting.YELLOW));
            }
        }
    }
}
