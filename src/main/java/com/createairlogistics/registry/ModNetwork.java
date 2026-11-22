package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.contraption.AirshipAssemblyValidator;
import com.createairlogistics.content.dock.SkyDockBlockEntity;
import com.createairlogistics.content.navigation.FlightChartItem;
import com.createairlogistics.registry.ModDataComponents.FlightRouteData;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * İstemci (Client GUI) -> Sunucu (Server) Ağ Paketleri:
 * 1. FlightChartActionPayload: Uçuş Haritası menüsünden waypoint ekleme (action=3), silme (action=1), irtifa seçme (action=0) ve sıfırlama (action=2).
 * 2. SkyDockActionPayload: Liman Vinci menüsünden Yükle/Boşalt modu, Kalkış Şartı ve Gemi Birleştir/Sök işlemleri.
 */
public final class ModNetwork {

    public record FlightChartActionPayload(int action, int value, BlockPos targetPos) implements CustomPacketPayload {
        public static final Type<FlightChartActionPayload> TYPE =
                new Type<>(CreateAirLogistics.asResource("flight_chart_action"));

        private static final StreamCodec<ByteBuf, BlockPos> UNPACKED_BLOCK_POS_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BlockPos::getX,
                ByteBufCodecs.VAR_INT, BlockPos::getY,
                ByteBufCodecs.VAR_INT, BlockPos::getZ,
                BlockPos::new
        );

        public static final StreamCodec<ByteBuf, FlightChartActionPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FlightChartActionPayload::action,
                ByteBufCodecs.VAR_INT, FlightChartActionPayload::value,
                UNPACKED_BLOCK_POS_CODEC, FlightChartActionPayload::targetPos,
                FlightChartActionPayload::new
        );

        public FlightChartActionPayload(int action, int value) {
            this(action, value, BlockPos.ZERO);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SkyDockActionPayload(BlockPos dockPos, int action) implements CustomPacketPayload {
        public static final Type<SkyDockActionPayload> TYPE =
                new Type<>(CreateAirLogistics.asResource("sky_dock_action"));

        public static final StreamCodec<ByteBuf, SkyDockActionPayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SkyDockActionPayload::dockPos,
                ByteBufCodecs.VAR_INT, SkyDockActionPayload::action,
                SkyDockActionPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SkyDockConfigurePayload(BlockPos dockPos, String customName, String packageFilter) implements CustomPacketPayload {
        public static final Type<SkyDockConfigurePayload> TYPE =
                new Type<>(CreateAirLogistics.asResource("sky_dock_configure"));

        public static final StreamCodec<ByteBuf, SkyDockConfigurePayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SkyDockConfigurePayload::dockPos,
                ByteBufCodecs.stringUtf8(32), SkyDockConfigurePayload::customName,
                ByteBufCodecs.stringUtf8(32), SkyDockConfigurePayload::packageFilter,
                SkyDockConfigurePayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                FlightChartActionPayload.TYPE,
                FlightChartActionPayload.STREAM_CODEC,
                ModNetwork::handleFlightChartAction
        );
        registrar.playToServer(
                SkyDockActionPayload.TYPE,
                SkyDockActionPayload.STREAM_CODEC,
                ModNetwork::handleSkyDockAction
        );
        registrar.playToServer(
                SkyDockConfigurePayload.TYPE,
                SkyDockConfigurePayload.STREAM_CODEC,
                ModNetwork::handleSkyDockConfigure
        );
    }

    private static void handleFlightChartAction(FlightChartActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.isSpectator() || !player.mayBuild()) {
                return;
            }
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof FlightChartItem)) {
                stack = player.getOffhandItem();
            }
            if (!(stack.getItem() instanceof FlightChartItem)) {
                return;
            }

            FlightRouteData data = stack.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
            switch (payload.action()) {
                case 0 -> {
                    // İrtifa ofseti değiştir (value = 10, 15, 25, 40)
                    stack.set(ModDataComponents.FLIGHT_ROUTE.get(), data.withAltitudeOffset(payload.value()));
                }
                case 1 -> {
                    // Belirli indeksli Waypoint'i sil
                    List<BlockPos> updated = new ArrayList<>(data.waypoints());
                    if (payload.value() >= 0 && payload.value() < updated.size()) {
                        updated.remove(payload.value());
                        stack.set(ModDataComponents.FLIGHT_ROUTE.get(), new FlightRouteData(
                                data.dockA(), data.dockAName(), data.dockB(), data.dockBName(), updated, data.altitudeOffset()
                        ));
                    }
                }
                case 2 -> {
                    // Tüm haritayı sıfırla
                    stack.set(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
                }
                case 3 -> {
                    // Menüden yeni Waypoint (Ara Nokta) ekle!
                    ServerLevel level = player.serverLevel();
                    BlockPos sanitized = data.sanitizeWaypoint(payload.targetPos(), player.blockPosition(), level.getMinBuildHeight(), level.getMaxBuildHeight());
                    if (sanitized == null) {
                        return;
                    }
                    stack.set(ModDataComponents.FLIGHT_ROUTE.get(), data.withAddedWaypoint(sanitized));
                }
            }
        });
    }

    private static void handleSkyDockAction(SkyDockActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockPos pos = payload.dockPos();
            if (player.isSpectator() || !player.mayBuild()) {
                return;
            }
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
                return;
            }
            if (!level.isLoaded(pos) || !level.mayInteract(player, pos)) {
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof SkyDockBlockEntity be)) {
                return;
            }

            switch (payload.action()) {
                case 0 -> be.toggleDockMode();
                case 1 -> be.cycleDepartureCondition();
                case 2 -> {
                    BlockState state = level.getBlockState(pos);
                    AirshipAssemblyValidator.tryAssembleFromDock(level, pos, state, player);
                }
            }
        });
    }

    private static void handleSkyDockConfigure(SkyDockConfigurePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockPos pos = payload.dockPos();
            if (player.isSpectator() || !player.mayBuild()) {
                return;
            }
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
                return;
            }
            if (!level.isLoaded(pos) || !level.mayInteract(player, pos)) {
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof SkyDockBlockEntity be)) {
                return;
            }
            be.setConfiguration(payload.customName(), payload.packageFilter());
        });
    }
}
