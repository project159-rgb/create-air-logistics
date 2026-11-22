package com.createairlogistics.registry;

import com.createairlogistics.CreateAirLogistics;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.component.DataComponentType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CreateAirLogistics.MOD_ID);

    public record FlightRouteData(
            Optional<GlobalPos> dockA,
            String dockAName,
            Optional<GlobalPos> dockB,
            String dockBName,
            List<BlockPos> waypoints,
            int altitudeOffset
    ) {
        public static final int MAX_DOCK_NAME_LENGTH = 64;
        public static final int MAX_WAYPOINTS = 16;
        public static final int MAX_WAYPOINT_RANGE = 4096;
        public static final int MIN_ALTITUDE_OFFSET = 5;
        public static final int MAX_ALTITUDE_OFFSET = 60;

        public static final FlightRouteData EMPTY = new FlightRouteData(
                Optional.empty(), "", Optional.empty(), "", List.of(), 15
        );

        public FlightRouteData {
            dockA = dockA != null ? dockA : Optional.empty();
            dockB = dockB != null ? dockB : Optional.empty();
            dockAName = sanitizeName(dockAName);
            dockBName = sanitizeName(dockBName);
            if (waypoints == null || waypoints.isEmpty()) {
                waypoints = List.of();
            } else if (waypoints.size() > MAX_WAYPOINTS) {
                waypoints = List.copyOf(waypoints.subList(0, MAX_WAYPOINTS));
            } else {
                waypoints = List.copyOf(waypoints);
            }
            altitudeOffset = Math.clamp(altitudeOffset, MIN_ALTITUDE_OFFSET, MAX_ALTITUDE_OFFSET);
        }

        private static String sanitizeName(String name) {
            if (name == null || name.isEmpty()) {
                return "";
            }
            return name.length() > MAX_DOCK_NAME_LENGTH ? name.substring(0, MAX_DOCK_NAME_LENGTH) : name;
        }

        public static final Codec<FlightRouteData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                GlobalPos.CODEC.optionalFieldOf("dock_a").forGetter(FlightRouteData::dockA),
                Codec.STRING.optionalFieldOf("dock_a_name", "").forGetter(FlightRouteData::dockAName),
                GlobalPos.CODEC.optionalFieldOf("dock_b").forGetter(FlightRouteData::dockB),
                Codec.STRING.optionalFieldOf("dock_b_name", "").forGetter(FlightRouteData::dockBName),
                BlockPos.CODEC.listOf().optionalFieldOf("waypoints", List.of()).forGetter(FlightRouteData::waypoints),
                Codec.INT.optionalFieldOf("altitude_offset", 15).forGetter(FlightRouteData::altitudeOffset)
        ).apply(instance, FlightRouteData::new));

        public static final StreamCodec<ByteBuf, FlightRouteData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(GlobalPos.STREAM_CODEC), FlightRouteData::dockA,
                ByteBufCodecs.stringUtf8(MAX_DOCK_NAME_LENGTH), FlightRouteData::dockAName,
                ByteBufCodecs.optional(GlobalPos.STREAM_CODEC), FlightRouteData::dockB,
                ByteBufCodecs.stringUtf8(MAX_DOCK_NAME_LENGTH), FlightRouteData::dockBName,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_WAYPOINTS)), FlightRouteData::waypoints,
                ByteBufCodecs.VAR_INT, FlightRouteData::altitudeOffset,
                FlightRouteData::new
        );

        public boolean isComplete() {
            return dockA.isPresent() && dockB.isPresent()
                    && dockA.get().dimension().equals(dockB.get().dimension())
                    && !dockA.get().pos().equals(dockB.get().pos());
        }

        public boolean connectsDock(BlockPos dockPos) {
            return (dockA.isPresent() && dockA.get().pos().equals(dockPos))
                    || (dockB.isPresent() && dockB.get().pos().equals(dockPos));
        }

        public Component resolveDockADisplayName() {
            if (!dockAName.isBlank()) {
                return Component.literal(dockAName);
            }
            return dockA.map(gp -> (Component) Component.translatable(
                    "create_air_logistics.chart.default_dock_name", gp.pos().toShortString()))
                    .orElse(Component.empty());
        }

        public Component resolveDockBDisplayName() {
            if (!dockBName.isBlank()) {
                return Component.literal(dockBName);
            }
            return dockB.map(gp -> (Component) Component.translatable(
                    "create_air_logistics.chart.default_dock_name", gp.pos().toShortString()))
                    .orElse(Component.empty());
        }

        public FlightRouteData withDockA(GlobalPos pos, String name) {
            return new FlightRouteData(Optional.of(pos), name, Optional.empty(), "", new ArrayList<>(), altitudeOffset);
        }

        public FlightRouteData withDockB(GlobalPos pos, String name) {
            return new FlightRouteData(dockA, dockAName, Optional.of(pos), name, waypoints, altitudeOffset);
        }

        public FlightRouteData withAddedWaypoint(BlockPos waypoint) {
            List<BlockPos> updated = new ArrayList<>(waypoints);
            if (updated.size() < MAX_WAYPOINTS) {
                updated.add(waypoint);
            }
            return new FlightRouteData(dockA, dockAName, dockB, dockBName, updated, altitudeOffset);
        }

        public FlightRouteData withAltitudeOffset(int newOffset) {
            int clamped = Math.clamp(newOffset, MIN_ALTITUDE_OFFSET, MAX_ALTITUDE_OFFSET);
            return new FlightRouteData(dockA, dockAName, dockB, dockBName, waypoints, clamped);
        }

        public boolean isWaypointInRange(BlockPos rawTarget, BlockPos fallbackRef) {
            if (rawTarget == null || fallbackRef == null) {
                return false;
            }
            BlockPos ref = dockA.map(GlobalPos::pos).orElse(fallbackRef);
            long dx = (long) rawTarget.getX() - ref.getX();
            long dz = (long) rawTarget.getZ() - ref.getZ();
            return Math.abs(dx) <= MAX_WAYPOINT_RANGE && Math.abs(dz) <= MAX_WAYPOINT_RANGE;
        }

        @Nullable
        public BlockPos sanitizeWaypoint(BlockPos rawTarget, BlockPos fallbackRef, int minBuildHeight, int maxBuildHeight) {
            if (rawTarget == null || waypoints.size() >= MAX_WAYPOINTS || !isWaypointInRange(rawTarget, fallbackRef)) {
                return null;
            }
            int clampedY = Math.clamp(rawTarget.getY(), minBuildHeight, maxBuildHeight + 64);
            return new BlockPos(rawTarget.getX(), clampedY, rawTarget.getZ());
        }
    }

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FlightRouteData>> FLIGHT_ROUTE =
            DATA_COMPONENTS.register("flight_route", () -> DataComponentType.<FlightRouteData>builder()
                    .persistent(FlightRouteData.CODEC)
                    .networkSynchronized(FlightRouteData.STREAM_CODEC)
                    .build());

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
    }
}
