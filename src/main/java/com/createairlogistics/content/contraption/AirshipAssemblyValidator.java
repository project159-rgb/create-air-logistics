package com.createairlogistics.content.contraption;

import com.createairlogistics.content.contraption.balloon.CanvasBalloonBlock;
import com.createairlogistics.content.contraption.balloon.CanvasBalloonBlock.BalloonLiftAnalysis;
import com.createairlogistics.content.contraption.propulsion.BoostPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlockEntity;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlock;
import com.createairlogistics.content.dock.ShipPortBlock;
import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.content.dock.SkyDockBlockEntity;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlock;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlockEntity;
import com.createairlogistics.content.navigation.FlightChartItem;
import com.createairlogistics.content.navigation.SkyNetworkSavedData;
import com.createairlogistics.content.navigation.SkyRoute;
import com.createairlogistics.content.navigation.TravellingAirship;
import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.content.pressure.AirshipAirStorage;
import com.createairlogistics.registry.ModAdvancements;
import com.createairlogistics.registry.ModDataComponents;
import com.createairlogistics.registry.ModDataComponents.FlightRouteData;
import com.createairlogistics.registry.ModTags;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.chassis.AbstractChassisBlock;
import com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity;
import com.simibubi.create.content.contraptions.chassis.StickerBlock;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Hava Gemisi Kurulum ve Fizik Denetleyicisi (Airship Assembly Validator)
 *
 * - Create Super Glue kutularını ve birbirine bağlı gemi bloklarını algılar.
 * - Sıcak hava balonlarının kubbe geometrisini (zemine/alta balon döşenmemesini) ve dikey ısı akışını denetler.
 * - Yön Bulucu Pervanenin 3x3 kanat açıklığını ve gemi dışına açık hava koridorunu denetler.
 */
public final class AirshipAssemblyValidator {

    public static final int MAX_SHIP_BLOCKS = 2048;

    /** Basınç motoruyla seyir hızı (blok/tick, 7 blok/sn). */
    public static final double BASE_CRUISE_SPEED = 0.35;
    /** Pervane başına yavaşlamadan taşınabilen ağırlık; aşılırsa gemi yavaşlar (ek itki pervanesi hızı geri kazandırır). */
    public static final int WEIGHT_PER_THRUSTER = 360;
    /** Aşırı ağır gemide hızın düşebileceği en alt oran. */
    public static final double MIN_WEIGHT_SPEED_FACTOR = 0.45;

    /** Aynı Super Glue kutusu içindeki kavisli balon kubbelerini bağlamak için 20 çapraz komşu ofseti. */
    private static final int[][] DIAGONAL_OFFSETS = {
            // 12 kenar çapraz komşusu (manhattan == 2)
            { 1,  1,  0}, { 1, -1,  0}, {-1,  1,  0}, {-1, -1,  0},
            { 1,  0,  1}, { 1,  0, -1}, {-1,  0,  1}, {-1,  0, -1},
            { 0,  1,  1}, { 0,  1, -1}, { 0, -1,  1}, { 0, -1, -1},
            // 8 köşe çapraz komşusu (manhattan == 3)
            { 1,  1,  1}, { 1,  1, -1}, { 1, -1,  1}, { 1, -1, -1},
            {-1,  1,  1}, {-1,  1, -1}, {-1, -1,  1}, {-1, -1, -1}
    };

    public static int getEffectiveMaxShipBlocks() {
        try {
            int createLimit = AllConfigs.server().kinetics.maxBlocksMoved.get();
            return Math.min(MAX_SHIP_BLOCKS, createLimit);
        } catch (Exception e) {
            return MAX_SHIP_BLOCKS;
        }
    }

    public record DiagnosticReport(
            boolean hasShipPort,
            @Nullable BlockPos portPos,
            boolean hasSuperGlue,
            int blockCount,
            int pathfinderCount,
            boolean propellerClear,
            boolean hasValidRoute,
            boolean engineMounted,
            int totalLift,
            int totalWeight,
            boolean hasActiveHeat,
            boolean balloonsProperlyPlaced,
            int cargoHoldCount,
            int extraThrusterCount,
            Set<BlockPos> shipBlocks,
            Set<SuperGlueEntity> glueEntities,
            @Nullable BlockPos propellerPos,
            AirshipAirStorage.Scan air,
            double cruiseSpeed,
            double routeLength,
            @Nullable BlockPos destinationDock,
            @Nullable BlockPos routeObstacle,
            @Nullable BlockPos unmovableBlock
    ) {
        public DiagnosticReport(
                boolean hasShipPort,
                @Nullable BlockPos portPos,
                boolean hasSuperGlue,
                int blockCount,
                int pathfinderCount,
                boolean propellerClear,
                boolean hasValidRoute,
                boolean engineMounted,
                int totalLift,
                int totalWeight,
                boolean hasActiveHeat,
                boolean balloonsProperlyPlaced,
                int cargoHoldCount,
                int extraThrusterCount,
                Set<BlockPos> shipBlocks,
                Set<SuperGlueEntity> glueEntities,
                @Nullable BlockPos propellerPos,
                AirshipAirStorage.Scan air,
                double cruiseSpeed,
                double routeLength,
                @Nullable BlockPos destinationDock,
                @Nullable BlockPos routeObstacle
        ) {
            this(hasShipPort, portPos, hasSuperGlue, blockCount, pathfinderCount,
                    propellerClear, hasValidRoute, engineMounted, totalLift, totalWeight,
                    hasActiveHeat, balloonsProperlyPlaced, cargoHoldCount, extraThrusterCount,
                    shipBlocks, glueEntities, propellerPos, air, cruiseSpeed, routeLength,
                    destinationDock, routeObstacle, null);
        }

        public boolean hasEnoughLift() {
            return balloonsProperlyPlaced && hasActiveHeat && totalLift >= totalWeight && totalWeight > 0;
        }

        public boolean isReady() {
            return hasShipPort && hasSuperGlue && blockCount > 1
                    && blockCount <= getEffectiveMaxShipBlocks()
                    && unmovableBlock == null
                    && pathfinderCount == 1
                    && propellerClear && engineMounted && hasValidRoute && hasEnoughLift();
        }

        public boolean isHeavy() {
            return totalWeight > WEIGHT_PER_THRUSTER * (1 + extraThrusterCount);
        }

        public boolean isDestinationDockMissing(@Nullable Level level) {
            return destinationDock != null
                    && level != null
                    && level.isLoaded(destinationDock)
                    && !(level.getBlockState(destinationDock).getBlock() instanceof SkyDockBlock);
        }

        /** Bir bacağın hava maliyeti (rota bilinmiyorsa 0). */
        public int legAirCost() {
            return AirPressure.legCost(routeLength, cruiseSpeed, air.mountedEngines());
        }

        public int requiredAir(boolean destinationPowered) {
            return AirPressure.requiredForDeparture(legAirCost(), destinationPowered);
        }
    }

    private AirshipAssemblyValidator() {
    }

    /**
     * Liman Menüsü (SkyDockScreen) ve kurulum için liman çevresindeki gemiyi canlı tarar.
     */
    public static DiagnosticReport inspectAtDock(Level level, BlockPos dockPos) {
        BlockState dockState = level.getBlockState(dockPos);
        BlockPos portPos = findNearestShipPort(level, dockPos, dockState);
        if (portPos == null) {
            return new DiagnosticReport(
                    false, null, false, 0, 0, false, false, false,
                    0, 0, false, true, 0, 0, Set.of(), Set.of(), null,
                    AirshipAirStorage.Scan.EMPTY, BASE_CRUISE_SPEED, 0.0, null, null, null
            );
        }
        return scanShipFromPort(level, dockPos, portPos);
    }

    @Nullable
    public static BlockPos findNearestShipPort(Level level, BlockPos dockPos, BlockState dockState) {
        if (dockState.hasProperty(SkyDockBlock.FACING)) {
            Direction facing = dockState.getValue(SkyDockBlock.FACING);
            BlockPos candidate = dockPos.relative(facing, 2);
            BlockState candidateState = level.getBlockState(candidate);
            if (candidateState.getBlock() instanceof ShipPortBlock
                    && candidateState.hasProperty(ShipPortBlock.FACING)
                    && candidateState.getValue(ShipPortBlock.FACING) == facing.getOpposite()) {
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    public static BlockPos findNearestSkyDock(Level level, BlockPos portPos, BlockState portState) {
        if (portState.hasProperty(ShipPortBlock.FACING)) {
            Direction facing = portState.getValue(ShipPortBlock.FACING);
            for (int dist = 1; dist <= 4; dist++) {
                BlockPos candidate = portPos.relative(facing, dist);
                if (level.getBlockState(candidate).getBlock() instanceof SkyDockBlock) {
                    return candidate;
                }
            }
        }
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos candidate = portPos.offset(dx, dy, dz);
                    if (level.getBlockState(candidate).getBlock() instanceof SkyDockBlock) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    public static DiagnosticReport scanShipFromPort(Level level, BlockPos dockPos, BlockPos portPos) {
        Set<BlockPos> visited = new HashSet<>();
        Set<SuperGlueEntity> collectedGlue = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        BlockPos unmovableBlock = null;

        if (level.isOutsideBuildHeight(portPos) || !level.isLoaded(portPos)) {
            return buildReportFromBlocks(level, dockPos, portPos, Set.of(), collectedGlue, null);
        }

        visited.add(portPos);
        queue.add(portPos);

        int maxBlocks = getEffectiveMaxShipBlocks();

        while (!queue.isEmpty() && visited.size() <= maxBlocks) {
            BlockPos current = queue.poll();
            if (level.isOutsideBuildHeight(current) || !level.isLoaded(current)) {
                visited.remove(current);
                continue;
            }

            BlockState state = level.getBlockState(current);
            if (!BlockMovementChecks.isMovementNecessary(state, level, current)) {
                visited.remove(current);
                continue;
            }
            if (!BlockMovementChecks.isMovementAllowed(state, level, current) || state.getBlock() instanceof SkyDockBlock) {
                if (unmovableBlock == null) {
                    unmovableBlock = current.immutable();
                }
                visited.remove(current);
                continue;
            }

            enqueueImplicitGroup(level, dockPos, current, state, queue, visited);
            enqueueDiagonalGluedBalloons(level, current, state, queue, visited, dockPos, collectedGlue, true);

            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (visited.contains(next) || next.equals(dockPos) || level.isOutsideBuildHeight(next) || !level.isLoaded(next)) {
                    continue;
                }

                BlockState nextState = level.getBlockState(next);
                if (!BlockMovementChecks.isMovementNecessary(nextState, level, next)) {
                    continue;
                }

                boolean isGlued = SuperGlueEntity.isGlued(level, current, dir, collectedGlue);
                boolean isAttached = BlockMovementChecks.isBlockAttachedTowards(nextState, level, next, dir.getOpposite())
                        || BlockMovementChecks.isBlockAttachedTowards(state, level, current, dir);
                boolean isSlimy = !BlockMovementChecks.isBrittle(nextState)
                        && state.canStickTo(nextState)
                        && nextState.canStickTo(state)
                        && state.getPistonPushReaction() != PushReaction.PUSH_ONLY
                        && nextState.getPistonPushReaction() != PushReaction.PUSH_ONLY
                        && !BlockMovementChecks.isNotSupportive(state, dir)
                        && !BlockMovementChecks.isNotSupportive(nextState, dir.getOpposite());

                if (!isGlued && !isAttached && !isSlimy) {
                    continue;
                }

                if (!BlockMovementChecks.isMovementAllowed(nextState, level, next) || nextState.getBlock() instanceof SkyDockBlock) {
                    if (unmovableBlock == null) {
                        unmovableBlock = next.immutable();
                    }
                    continue;
                }

                visited.add(next);
                queue.add(next);
            }
        }

        return buildReportFromBlocks(level, dockPos, portPos, visited, collectedGlue, unmovableBlock);
    }

    private static void enqueueIfLoaded(Level level, BlockPos dockPos, BlockPos pos, Queue<BlockPos> queue, Set<BlockPos> visited) {
        if (pos == null || level.isOutsideBuildHeight(pos) || !level.isLoaded(pos) || pos.equals(dockPos)) {
            return;
        }
        if (visited.add(pos)) {
            queue.add(pos);
        }
    }

    /**
     * Aynı {@link SuperGlueEntity} kutusu içinde kalan, yalnızca kenar veya köşeden (çapraz) temaslı
     * Kanvas Balonları da kuyruğa ekler; böylece kavisli kubbeler gemi kurulumunda kopmaz.
     */
    static void enqueueDiagonalGluedBalloons(
            Level level,
            BlockPos current,
            BlockState currentState,
            Queue<BlockPos> frontier,
            Set<BlockPos> visited,
            @Nullable BlockPos excludedDockPos,
            @Nullable Set<SuperGlueEntity> collectedGlue,
            boolean markVisitedOnEnqueue
    ) {
        if (!CanvasBalloonBlock.isBalloonBlock(currentState)) {
            return;
        }
        List<SuperGlueEntity> touchingGlue = level.getEntitiesOfClass(
                SuperGlueEntity.class,
                new AABB(current),
                glue -> glue.isAlive() && glue.contains(current)
        );
        if (touchingGlue.isEmpty()) {
            return;
        }
        for (int[] offset : DIAGONAL_OFFSETS) {
            BlockPos next = current.offset(offset[0], offset[1], offset[2]);
            if (visited.contains(next)
                    || (excludedDockPos != null && next.equals(excludedDockPos))
                    || level.isOutsideBuildHeight(next)
                    || !level.isLoaded(next)) {
                continue;
            }
            BlockState nextState = level.getBlockState(next);
            if (!CanvasBalloonBlock.isBalloonBlock(nextState)
                    || !BlockMovementChecks.isMovementNecessary(nextState, level, next)
                    || !BlockMovementChecks.isMovementAllowed(nextState, level, next)) {
                continue;
            }
            SuperGlueEntity sharedGlue = null;
            for (SuperGlueEntity glue : touchingGlue) {
                if (glue.contains(next)) {
                    sharedGlue = glue;
                    break;
                }
            }
            if (sharedGlue == null) {
                continue;
            }
            if (collectedGlue != null) {
                collectedGlue.add(sharedGlue);
            }
            if (markVisitedOnEnqueue) {
                if (visited.add(next)) {
                    frontier.add(next);
                }
            } else {
                frontier.add(next);
            }
        }
    }

    private static void enqueueImplicitGroup(Level level, BlockPos dockPos, BlockPos current, BlockState state, Queue<BlockPos> queue, Set<BlockPos> visited) {
        if (state.getBlock() instanceof AbstractChassisBlock && level.getBlockEntity(current) instanceof ChassisBlockEntity chassisBe) {
            Queue<BlockPos> cq = new ArrayDeque<>();
            Set<BlockPos> cv = new HashSet<>(visited);
            chassisBe.addAttachedChasses(cq, cv);
            while (!cq.isEmpty()) {
                BlockPos cp = cq.poll();
                enqueueIfLoaded(level, dockPos, cp, queue, visited);
            }
            List<BlockPos> included = chassisBe.getIncludedBlockPositions(null, false);
            if (included != null) {
                for (BlockPos p : included) {
                    if (p != null && !level.isOutsideBuildHeight(p) && level.isLoaded(p) && !p.equals(dockPos)) {
                        if (BlockMovementChecks.isMovementNecessary(level.getBlockState(p), level, p)) {
                            if (visited.add(p)) {
                                queue.add(p);
                            }
                        }
                    }
                }
            }
        }

        if (level.getBlockEntity(current) instanceof IMultiBlockEntityContainer multi) {
            BlockPos ctrlPos = multi.getController();
            IMultiBlockEntityContainer ctrl = multi.getControllerBE();
            if (ctrlPos != null && ctrl != null) {
                Direction.Axis axis = ctrl.getMainConnectionAxis();
                int width = Math.max(1, ctrl.getWidth());
                int height = Math.max(1, ctrl.getHeight());
                if (axis == Direction.Axis.Y) {
                    for (int dh = 0; dh < height; dh++) {
                        for (int dx = 0; dx < width; dx++) {
                            for (int dz = 0; dz < width; dz++) {
                                enqueueIfLoaded(level, dockPos, ctrlPos.offset(dx, dh, dz), queue, visited);
                            }
                        }
                    }
                } else if (axis == Direction.Axis.X) {
                    for (int dh = 0; dh < height; dh++) {
                        for (int dy = 0; dy < width; dy++) {
                            for (int dz = 0; dz < width; dz++) {
                                enqueueIfLoaded(level, dockPos, ctrlPos.offset(dh, dy, dz), queue, visited);
                            }
                        }
                    }
                } else if (axis == Direction.Axis.Z) {
                    for (int dh = 0; dh < height; dh++) {
                        for (int dx = 0; dx < width; dx++) {
                            for (int dy = 0; dy < width; dy++) {
                                enqueueIfLoaded(level, dockPos, ctrlPos.offset(dx, dy, dh), queue, visited);
                            }
                        }
                    }
                }
            }
        }

        if (state.hasProperty(ChestBlock.TYPE) && state.hasProperty(ChestBlock.FACING) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            enqueueIfLoaded(level, dockPos, current.relative(ChestBlock.getConnectedDirection(state)), queue, visited);
        }

        if (AllBlocks.STICKER.has(state) && state.getValue(StickerBlock.EXTENDED)) {
            Direction sf = state.getValue(StickerBlock.FACING);
            BlockPos stuck = current.relative(sf);
            if (!level.isOutsideBuildHeight(stuck) && level.isLoaded(stuck)
                    && !BlockMovementChecks.isNotSupportive(level.getBlockState(stuck), sf.getOpposite())) {
                enqueueIfLoaded(level, dockPos, stuck, queue, visited);
            }
        }

        if (AllBlocks.BELT.has(state)) {
            for (boolean forward : new boolean[]{true, false}) {
                BlockPos beltPos = current;
                BlockState beltState = state;
                for (int step = 0; step < 32; step++) {
                    BlockPos nextBeltPos = BeltBlock.nextSegmentPosition(beltState, beltPos, forward);
                    if (nextBeltPos == null || level.isOutsideBuildHeight(nextBeltPos) || !level.isLoaded(nextBeltPos)) {
                        break;
                    }
                    BlockState nextBeltState = level.getBlockState(nextBeltPos);
                    if (!AllBlocks.BELT.has(nextBeltState)) {
                        break;
                    }
                    enqueueIfLoaded(level, dockPos, nextBeltPos, queue, visited);
                    if (nextBeltPos.equals(current)) {
                        break;
                    }
                    beltPos = nextBeltPos;
                    beltState = nextBeltState;
                }
            }
        }
    }

    public static DiagnosticReport buildReportFromBlocks(
            Level level,
            BlockPos dockPos,
            BlockPos portPos,
            Set<BlockPos> shipBlocks,
            Set<SuperGlueEntity> collectedGlue,
            @Nullable BlockPos unmovableBlock
    ) {
        List<BlockPos> pathfinderPropellers = new ArrayList<>();
        int totalWeight = 0;
        int cargoHoldCount = 0;
        int extraThrusterCount = 0;
        Direction pathfinderFacing = findUniquePathfinderFacing(level, shipBlocks);

        for (BlockPos pos : shipBlocks) {
            if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof PathfinderPropellerBlock) {
                pathfinderPropellers.add(pos);
            } else if (isActiveExtraThruster(level, pos, state, shipBlocks, pathfinderFacing)) {
                extraThrusterCount++;
            }

            if (state.getBlock() instanceof AirshipCargoHoldBlock) {
                cargoHoldCount++;
            }

            totalWeight += getBlockWeight(state);
        }

        BalloonLiftAnalysis balloonAnalysis = CanvasBalloonBlock.analyzeShipBalloons(level, shipBlocks);
        int totalLift = balloonAnalysis.totalLift();
        boolean hasActiveHeat = balloonAnalysis.hasActiveHeatSource() && balloonAnalysis.heatedBalloonCount() > 0;
        boolean balloonsProperlyPlaced = balloonAnalysis.balloonsProperlyPlaced();

        boolean hasSuperGlue = shipBlocks.size() > 1;
        BlockPos propPos = pathfinderPropellers.isEmpty() ? null : pathfinderPropellers.getFirst();
        boolean propellerClear = propPos != null && isPropellerClear(level, propPos, shipBlocks);
        boolean hasRoute = false;
        boolean engineMounted = false;
        double routeLength = 0.0;
        BlockPos destination = null;
        BlockPos routeObstacle = null;

        if (propPos != null && level.getBlockEntity(propPos) instanceof PathfinderPropellerBlockEntity propBe) {
            FlightRouteData routeData = propBe.getRouteData();
            BlockState propState = level.getBlockState(propPos);
            BlockPos enginePos = PathfinderPropellerBlock.enginePos(propPos, propState);
            engineMounted = shipBlocks.contains(enginePos)
                    && PathfinderPropellerBlock.hasMountedEngine(level, propPos, propState);

            BlockPos[] docks = resolveRouteDocks(level, dockPos, routeData);
            hasRoute = (docks != null);
            if (docks != null) {
                SkyRoute estimate = buildRoute(level, docks[0], docks[1], routeData.waypoints());
                routeLength = estimate.getTotalArcLength();
                boolean reverse = dockPos.equals(docks[1]);
                destination = reverse ? docks[0] : docks[1];
                // Geminin kendi blokları (henüz dünyada) koridor engeli sayılmaz
                routeObstacle = estimate.findCorridorObstruction(level, reverse, shipBlocks);
            }
        }

        AirshipAirStorage.Scan airScan = AirshipAirStorage.scan(level, shipBlocks);
        double cruiseSpeed = computeCruiseSpeed(totalWeight, extraThrusterCount);

        return new DiagnosticReport(
                true,
                portPos,
                hasSuperGlue,
                shipBlocks.size(),
                pathfinderPropellers.size(),
                propellerClear,
                hasRoute,
                engineMounted,
                totalLift,
                totalWeight,
                hasActiveHeat,
                balloonsProperlyPlaced,
                cargoHoldCount,
                extraThrusterCount,
                shipBlocks,
                collectedGlue,
                propPos,
                airScan,
                cruiseSpeed,
                routeLength,
                destination,
                routeObstacle,
                unmovableBlock
        );
    }

    /**
     * Seyir hızı: basınç motoru gemiyi sabit hızla iter. Gemi pervane başına {@link #WEIGHT_PER_THRUSTER}
     * ağırlığı aşarsa yavaşlar (durmaz); ek itki pervaneleri bu sınırı yükseltir ve küçük bir hız bonusu verir.
     */
    public static double computeCruiseSpeed(int totalWeight, int extraThrusters) {
        double thrustCapacity = WEIGHT_PER_THRUSTER * (1.0 + extraThrusters);
        double weightFactor = totalWeight > thrustCapacity
                ? Math.max(MIN_WEIGHT_SPEED_FACTOR, thrustCapacity / totalWeight)
                : 1.0;
        return Mth.clamp(BASE_CRUISE_SPEED * weightFactor + extraThrusters * 0.04, 0.12, 0.6);
    }

    public static boolean isChartValidForDock(Level level, BlockPos dockPos, FlightRouteData routeData) {
        return resolveRouteDocks(level, dockPos, routeData) != null;
    }

    @Nullable
    public static BlockPos findMissingDestinationDock(Level level, BlockPos dockPos, FlightRouteData routeData) {
        BlockPos[] docks = resolveRouteDocks(level, dockPos, routeData);
        if (docks == null) {
            return null;
        }
        BlockPos otherDock = dockPos.equals(docks[0]) ? docks[1] : docks[0];
        if (level.isLoaded(otherDock) && !(level.getBlockState(otherDock).getBlock() instanceof SkyDockBlock)) {
            return otherDock;
        }
        return null;
    }

    /**
     * Haritadaki limanlardan rotanın (A, B) uçlarını, kurulumun yapıldığı limana göre belirler.
     * Harita değiştirilmez (yalnızca tahmin); kurulumdaki gerçek mantıkla birebir aynıdır.
     */
    @Nullable
    private static BlockPos[] resolveRouteDocks(Level level, BlockPos dockPos, FlightRouteData routeData) {
        if (routeData == null || routeData.dockA().isEmpty()) {
            return null;
        }
        if (!routeData.dockA().get().dimension().equals(level.dimension())
                || (routeData.dockB().isPresent() && !routeData.dockB().get().dimension().equals(level.dimension()))) {
            return null;
        }
        BlockPos dockAPos = routeData.dockA().get().pos();
        if (routeData.dockB().isEmpty()) {
            if (dockAPos.equals(dockPos)) {
                return null;
            }
            return new BlockPos[]{dockAPos, dockPos};
        }
        BlockPos dockBPos = routeData.dockB().get().pos();
        if (dockAPos.equals(dockBPos)) {
            return null;
        }
        if (dockPos.equals(dockAPos)) {
            return new BlockPos[]{dockAPos, dockBPos};
        }
        if (dockPos.equals(dockBPos)) {
            return new BlockPos[]{dockAPos, dockBPos};
        }
        double distA = dockAPos.distSqr(dockPos);
        double distB = dockBPos.distSqr(dockPos);
        if (Math.min(distA, distB) > 16.0) {
            return null;
        }
        if (distA <= distB) {
            if (level.isLoaded(dockAPos) && level.getBlockState(dockAPos).getBlock() instanceof SkyDockBlock) {
                return null;
            }
            if (dockPos.equals(dockBPos)) {
                return null;
            }
            return new BlockPos[]{dockPos, dockBPos};
        } else {
            if (level.isLoaded(dockBPos) && level.getBlockState(dockBPos).getBlock() instanceof SkyDockBlock) {
                return null;
            }
            if (dockPos.equals(dockAPos)) {
                return null;
            }
            return new BlockPos[]{dockAPos, dockPos};
        }
    }

    @Nullable
    public static Direction readThrusterFacing(BlockState propState) {
        if (propState.hasProperty(BlockStateProperties.FACING)) {
            return propState.getValue(BlockStateProperties.FACING);
        }
        if (propState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return propState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    @Nullable
    public static Direction findUniquePathfinderFacing(Level level, Set<BlockPos> shipBlocks) {
        Direction found = null;
        for (BlockPos pos : shipBlocks) {
            if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof PathfinderPropellerBlock) {
                if (!state.hasProperty(PathfinderPropellerBlock.FACING)) {
                    return null;
                }
                Direction facing = state.getValue(PathfinderPropellerBlock.FACING);
                if (!facing.getAxis().isHorizontal() || found != null) {
                    return null;
                }
                found = facing;
            }
        }
        return found;
    }

    @Nullable
    public static Direction findUniquePathfinderFacing(Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks) {
        Direction found = null;
        for (StructureTemplate.StructureBlockInfo info : blocks.values()) {
            BlockState state = info.state();
            if (state.getBlock() instanceof PathfinderPropellerBlock) {
                if (!state.hasProperty(PathfinderPropellerBlock.FACING)) {
                    return null;
                }
                Direction facing = state.getValue(PathfinderPropellerBlock.FACING);
                if (!facing.getAxis().isHorizontal() || found != null) {
                    return null;
                }
                found = facing;
            }
        }
        return found;
    }

    public static boolean isActiveExtraThruster(
            Level level,
            BlockPos propPos,
            BlockState propState,
            Set<BlockPos> shipBlocks,
            @Nullable Direction pathfinderFacing
    ) {
        if (pathfinderFacing == null || !pathfinderFacing.getAxis().isHorizontal()) {
            return false;
        }
        if (!(propState.getBlock() instanceof BoostPropellerBlock) && !propState.is(ModTags.EXTRA_THRUSTERS)) {
            return false;
        }
        if (readThrusterFacing(propState) != pathfinderFacing) {
            return false;
        }
        BlockPos enginePos = propPos.relative(pathfinderFacing.getOpposite());
        if (!shipBlocks.contains(enginePos)
                || level.isOutsideBuildHeight(enginePos)
                || !level.isLoaded(enginePos)
                || !PressureEngineBlock.isMountedOnPropeller(level, enginePos, level.getBlockState(enginePos))) {
            return false;
        }
        return isThrusterClearOfShip(propState, propPos, shipBlocks);
    }

    public static boolean isActiveExtraThrusterInContraption(
            BlockPos localPropPos,
            BlockState propState,
            Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks,
            @Nullable Direction pathfinderFacing
    ) {
        if (pathfinderFacing == null || !pathfinderFacing.getAxis().isHorizontal()) {
            return false;
        }
        if (!(propState.getBlock() instanceof BoostPropellerBlock) && !propState.is(ModTags.EXTRA_THRUSTERS)) {
            return false;
        }
        if (readThrusterFacing(propState) != pathfinderFacing) {
            return false;
        }
        BlockPos localEnginePos = localPropPos.relative(pathfinderFacing.getOpposite());
        StructureTemplate.StructureBlockInfo engineInfo = blocks.get(localEnginePos);
        if (engineInfo == null
                || !(engineInfo.state().getBlock() instanceof PressureEngineBlock)
                || !engineInfo.state().hasProperty(PressureEngineBlock.FACING)
                || engineInfo.state().getValue(PressureEngineBlock.FACING) != pathfinderFacing) {
            return false;
        }
        return isThrusterClearOfShip(propState, localPropPos, blocks.keySet());
    }

    public static boolean isThrusterClearOfShip(BlockState propState, BlockPos propPos, Set<BlockPos> shipBlocks) {
        Direction facing = readThrusterFacing(propState);
        if (facing == null || !facing.getAxis().isHorizontal()) {
            return false;
        }
        Direction right = facing.getClockWise();
        for (int dist = 1; dist <= 4; dist++) {
            BlockPos sliceCenter = propPos.relative(facing, dist);
            for (int du = -1; du <= 1; du++) {
                for (int dv = -1; dv <= 1; dv++) {
                    BlockPos corridorPos = sliceCenter.relative(right, du).above(dv);
                    if (shipBlocks.contains(corridorPos)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String readDockDisplayName(Level level, BlockPos dockPos) {
        return (level.getBlockEntity(dockPos) instanceof SkyDockBlockEntity namedDock && namedDock.hasCustomName())
                ? namedDock.getCustomName()
                : "";
    }

    private static SkyRoute buildRoute(Level level, BlockPos dockA, BlockPos dockB, List<BlockPos> waypoints) {
        return new SkyRoute(UUID.randomUUID(), dockA, dockB, waypoints, readFacing(level, dockA), readFacing(level, dockB));
    }

    @Nullable
    private static Direction readFacing(Level level, BlockPos dockPos) {
        if (!level.isLoaded(dockPos)) {
            return null;
        }
        BlockState state = level.getBlockState(dockPos);
        return state.hasProperty(SkyDockBlock.FACING) ? state.getValue(SkyDockBlock.FACING) : null;
    }

    /**
     * Yön Bulucu Pervane Fiziksel Açıklık Kontrolü:
     * 1. Pervanenin önündeki 3x3 kanat dönüş düzleminde (1 blok önde) hiçbir katı blok olmamalıdır.
     * 2. Pervanenin baktığı yönde ileri doğru 4 blokluk hava koridorunda (3x3 kesit) geminin kendisine ait
     *    başka bir blok olmamalıdır (pervane gövdenin içindeki bir deliğe gömülemez!).
     */
    public static boolean isPropellerClear(Level level, BlockPos propPos, Set<BlockPos> shipBlocks) {
        BlockState propState = level.getBlockState(propPos);
        if (!propState.hasProperty(PathfinderPropellerBlock.FACING)) {
            return false;
        }
        Direction facing = propState.getValue(PathfinderPropellerBlock.FACING);
        if (!facing.getAxis().isHorizontal()) {
            return false;
        }
        Direction right = facing.getClockWise();

        // 1. Ön 3x3 kanat dönüş alanı: Hem gemi bloğu hem de dünyadaki katı bloklardan tamamen temiz olmalı
        BlockPos rotorCenter = propPos.relative(facing);
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                BlockPos bladePos = rotorCenter.relative(right, du).above(dv);
                if (shipBlocks.contains(bladePos)) {
                    return false;
                }
                BlockState worldState = level.getBlockState(bladePos);
                if (!worldState.isAir() && !worldState.getCollisionShape(level, bladePos).isEmpty()) {
                    return false;
                }
            }
        }

        // 2. Hava koridoru (Slipstream): 2..4 blok ileriye doğru geminin kendi gövde blokları pervanenin önünü kapatmamalı
        for (int dist = 2; dist <= 4; dist++) {
            BlockPos sliceCenter = propPos.relative(facing, dist);
            for (int du = -1; du <= 1; du++) {
                for (int dv = -1; dv <= 1; dv++) {
                    BlockPos corridorPos = sliceCenter.relative(right, du).above(dv);
                    if (shipBlocks.contains(corridorPos)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public static boolean tryAssembleFromDock(ServerLevel level, BlockPos dockPos, BlockState dockState, Player player) {
        SkyNetworkSavedData network = SkyNetworkSavedData.get(level);
        Optional<TravellingAirship> dockedOpt = network.getDockedAirshipAt(dockPos);
        if (dockedOpt.isPresent()) {
            boolean disassembled = network.disassembleAirship(level, dockedOpt.get().getAirshipId(), player);
            if (disassembled) {
                AllSoundEvents.CONTRAPTION_DISASSEMBLE.playOnServer(level, dockPos);
                return true;
            }
            return false;
        }

        BlockPos portPos = findNearestShipPort(level, dockPos, dockState);
        if (portPos == null) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.no_ship_port"));
            return false;
        }
        return validateAndAssemble(level, dockPos, portPos, player);
    }

    public static boolean tryAssembleFromPort(ServerLevel level, BlockPos portPos, BlockState portState, Player player) {
        BlockPos dockPos = findNearestSkyDock(level, portPos, portState);
        if (dockPos == null) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.no_sky_dock"));
            return false;
        }
        return validateAndAssemble(level, dockPos, portPos, player);
    }

    private static boolean validateAndAssemble(ServerLevel level, BlockPos dockPos, BlockPos portPos, Player player) {
        SkyNetworkSavedData network = SkyNetworkSavedData.get(level);
        if (network.isDockOccupied(dockPos, null)) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.dock_occupied"));
            return false;
        }

        BlockState dockState = level.getBlockState(dockPos);
        BlockState portState = level.getBlockState(portPos);
        if (!dockState.hasProperty(SkyDockBlock.FACING)
                || !portState.hasProperty(ShipPortBlock.FACING)
                || !dockPos.relative(dockState.getValue(SkyDockBlock.FACING), 2).equals(portPos)
                || portState.getValue(ShipPortBlock.FACING) != dockState.getValue(SkyDockBlock.FACING).getOpposite()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.port_not_aligned"));
            return false;
        }

        DiagnosticReport report = scanShipFromPort(level, dockPos, portPos);

        if (report.unmovableBlock() != null) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.unmovable_block",
                    report.unmovableBlock().getX(), report.unmovableBlock().getY(), report.unmovableBlock().getZ()));
            return false;
        }

        if (!report.hasSuperGlue()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.not_glued"));
            return false;
        }

        int maxBlocks = getEffectiveMaxShipBlocks();
        if (report.blockCount() > maxBlocks) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.too_large", maxBlocks));
            return false;
        }

        if (report.pathfinderCount() == 0 || report.propellerPos() == null) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.no_pathfinder"));
            return false;
        }
        if (report.pathfinderCount() > 1) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.multiple_pathfinders", report.pathfinderCount()));
            return false;
        }

        // KURAL: Pervane 3x3 kanat alanı ve hava koridoru açık olmalı (gövde içine gömülemez!)
        if (!report.propellerClear()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.propeller_blocked"));
            return false;
        }

        BlockEntity be = level.getBlockEntity(report.propellerPos());
        if (!(be instanceof PathfinderPropellerBlockEntity initialPropBe)) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.no_pathfinder"));
            return false;
        }
        PathfinderPropellerBlockEntity propellerBE = initialPropBe;

        if (!propellerBE.hasMountedEngine() || !report.engineMounted()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.no_engine"));
            return false;
        }

        ItemStack handChartStack = ItemStack.EMPTY;
        ItemStack chartToInsert = ItemStack.EMPTY;
        FlightRouteData routeData;
        if (propellerBE.hasChart()) {
            chartToInsert = propellerBE.getHeldChart().copy();
            routeData = propellerBE.getRouteData();
        } else {
            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();
            if (main.getItem() instanceof FlightChartItem) {
                handChartStack = main;
            } else if (off.getItem() instanceof FlightChartItem) {
                handChartStack = off;
            }
            if (!handChartStack.isEmpty()) {
                chartToInsert = handChartStack.copyWithCount(1);
                routeData = chartToInsert.getOrDefault(ModDataComponents.FLIGHT_ROUTE.get(), FlightRouteData.EMPTY);
            } else {
                routeData = FlightRouteData.EMPTY;
            }
        }

        if (routeData.dockA().isEmpty()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.no_chart"));
            return false;
        }

        if (!routeData.dockA().get().dimension().equals(level.dimension())
                || (routeData.dockB().isPresent() && !routeData.dockB().get().dimension().equals(level.dimension()))) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.wrong_dimension"));
            return false;
        }

        if (routeData.dockB().isEmpty() && routeData.dockA().get().pos().equals(dockPos)) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.same_dock"));
            return false;
        }

        if (routeData.dockB().isPresent() && routeData.dockA().get().pos().equals(routeData.dockB().get().pos())) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.same_dock"));
            return false;
        }

        BlockPos[] resolvedDocks = resolveRouteDocks(level, dockPos, routeData);
        if (resolvedDocks == null) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.dock_mismatch"));
            return false;
        }

        BlockPos dockAPos = resolvedDocks[0];
        BlockPos dockBPos = resolvedDocks[1];
        if (routeData.dockB().isEmpty()) {
            GlobalPos gp = GlobalPos.of(level.dimension(), dockPos.immutable());
            routeData = routeData.withDockB(gp, readDockDisplayName(level, dockPos));
            chartToInsert.set(ModDataComponents.FLIGHT_ROUTE.get(), routeData);
        } else if (!dockAPos.equals(routeData.dockA().get().pos())) {
            GlobalPos gp = GlobalPos.of(level.dimension(), dockPos.immutable());
            routeData = new FlightRouteData(
                    Optional.of(gp),
                    readDockDisplayName(level, dockPos),
                    routeData.dockB(),
                    routeData.dockBName(),
                    routeData.waypoints(),
                    routeData.altitudeOffset()
            );
            chartToInsert.set(ModDataComponents.FLIGHT_ROUTE.get(), routeData);
        } else if (!dockBPos.equals(routeData.dockB().get().pos())) {
            GlobalPos gp = GlobalPos.of(level.dimension(), dockPos.immutable());
            routeData = routeData.withDockB(gp, readDockDisplayName(level, dockPos));
            chartToInsert.set(ModDataComponents.FLIGHT_ROUTE.get(), routeData);
        }

        BlockPos otherDock = dockPos.equals(dockAPos) ? dockBPos : dockAPos;
        if (level.isLoaded(otherDock) && !(level.getBlockState(otherDock).getBlock() instanceof SkyDockBlock)) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.missing_target_dock", otherDock.toShortString()));
            return false;
        }

        // KURAL: Balon kubbesi geminin altında/tabanında olamaz!
        if (!report.balloonsProperlyPlaced()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.balloons_below_hull"));
            return false;
        }

        // KURAL: Balonların altında doğrudan balon kubbesine sıcak hava veren aktif bir ateş/ocak olmalı!
        if (!report.hasActiveHeat()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.no_balloon_heat"));
            return false;
        }

        // KURAL: Balon Kaldırma Kuvveti >= Tam Yüklü Gemi Ağırlığı (Lift >= Weight)
        if (report.totalLift() < report.totalWeight()) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.insufficient_lift", report.totalWeight(), report.totalLift()));
            return false;
        }

        // Liman yönleri: rota uç noktaları yanaşma (ShipPort) hizasından başlar/biter → kalkış/varışta ışınlanma olmaz.
        Direction dockAFacing = readFacing(level, dockAPos);
        Direction dockBFacing = readFacing(level, dockBPos);

        SkyRoute route = new SkyRoute(
                UUID.randomUUID(),
                dockAPos,
                dockBPos,
                routeData.waypoints(),
                dockAFacing,
                dockBFacing
        );

        // Uçuş koridoru kontrolü: engel kurulumu ENGELLEMEZ — gemi limanda birleşip bekler,
        // liman kalkışı tutar ve uyarı Sky Dock menüsü / goggles üzerinde gösterilir.
        double cruiseSpeed = computeCruiseSpeed(report.totalWeight(), report.extraThrusterCount());

        // KURAL: Basınçlı hava — sefer için gereken hava, motor + backtank havası ile liman tankından karşılanmalı
        AirshipAirStorage.Scan airScan = report.air();
        BlockPos destinationDock = dockPos.equals(route.getDockB()) ? route.getDockA() : route.getDockB();
        int legCost = AirPressure.legCost(route.getTotalArcLength(), cruiseSpeed, airScan.mountedEngines());
        int requiredAir = AirPressure.requiredForDeparture(legCost, network.isDockPowered(destinationDock));
        if (airScan.capacity() < requiredAir) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.range", airScan.capacity(), requiredAir));
            return false;
        }
        SkyDockBlockEntity dockBe = level.getBlockEntity(dockPos) instanceof SkyDockBlockEntity dockEntity ? dockEntity : null;
        int fromDock = dockBe != null ? dockBe.drainAir(airScan.capacity() - airScan.storedAir(), true) : 0;
        if (airScan.storedAir() + fromDock < requiredAir) {
            sendError(player, Component.translatable("create_air_logistics.assembly.error.pressure",
                    airScan.storedAir() + fromDock, requiredAir));
            return false;
        }

        int maxCargoSlots = Math.max(9, report.cargoHoldCount() * AirshipCargoHoldBlockEntity.SLOT_COUNT);

        AirshipContraption contraption = new AirshipContraption();
        try {
            if (!contraption.assemble(level, portPos)) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.not_glued"));
                return false;
            }
        } catch (AssemblyException e) {
            if (e.component != null) {
                sendError(player, e.component);
            } else {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.not_glued"));
            }
            return false;
        }

        // Post-assemble verification & atomic gate
        Set<BlockPos> actualShipBlocks = new HashSet<>(contraption.getBlocks().size());
        for (BlockPos local : contraption.getBlocks().keySet()) {
            actualShipBlocks.add(local.offset(portPos));
        }

        if (!actualShipBlocks.equals(report.shipBlocks())) {
            report = buildReportFromBlocks(level, dockPos, portPos, actualShipBlocks, report.glueEntities(), null);
            if (actualShipBlocks.size() <= 1) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.not_glued"));
                return false;
            }
            if (report.blockCount() > maxBlocks) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.too_large", maxBlocks));
                return false;
            }
            if (report.pathfinderCount() == 0 || report.propellerPos() == null) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.no_pathfinder"));
                return false;
            }
            if (report.pathfinderCount() > 1) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.multiple_pathfinders", report.pathfinderCount()));
                return false;
            }
            if (!report.propellerClear()) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.propeller_blocked"));
                return false;
            }
            BlockEntity actualBe = level.getBlockEntity(report.propellerPos());
            if (!(actualBe instanceof PathfinderPropellerBlockEntity actualPropBe)) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.no_pathfinder"));
                return false;
            }
            if (!actualPropBe.hasMountedEngine() || !report.engineMounted()) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.no_engine"));
                return false;
            }
            if (!report.balloonsProperlyPlaced()) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.balloons_below_hull"));
                return false;
            }
            if (!report.hasActiveHeat()) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.no_balloon_heat"));
                return false;
            }
            if (report.totalLift() < report.totalWeight()) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.insufficient_lift", report.totalWeight(), report.totalLift()));
                return false;
            }

            propellerBE = actualPropBe;
            cruiseSpeed = computeCruiseSpeed(report.totalWeight(), report.extraThrusterCount());
            airScan = report.air();
            legCost = AirPressure.legCost(route.getTotalArcLength(), cruiseSpeed, airScan.mountedEngines());
            requiredAir = AirPressure.requiredForDeparture(legCost, network.isDockPowered(destinationDock));
            if (airScan.capacity() < requiredAir) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.range", airScan.capacity(), requiredAir));
                return false;
            }
            fromDock = dockBe != null ? dockBe.drainAir(airScan.capacity() - airScan.storedAir(), true) : 0;
            if (airScan.storedAir() + fromDock < requiredAir) {
                sendError(player, Component.translatable("create_air_logistics.assembly.error.pressure",
                        airScan.storedAir() + fromDock, requiredAir));
                return false;
            }
            maxCargoSlots = Math.max(9, report.cargoHoldCount() * AirshipCargoHoldBlockEntity.SLOT_COUNT);
        }

        // ONLY AFTER ALL post-assemble() checks pass:
        boolean hadChartBefore = propellerBE.hasChart();
        propellerBE.setHeldChart(chartToInsert);

        BlockPos localPropPos = report.propellerPos().subtract(portPos);
        StructureTemplate.StructureBlockInfo oldPropInfo = contraption.getBlocks().get(localPropPos);
        if (oldPropInfo != null) {
            BlockState updatedPropState = oldPropInfo.state().hasProperty(PathfinderPropellerBlock.HAS_CHART)
                    ? oldPropInfo.state().setValue(PathfinderPropellerBlock.HAS_CHART, true)
                    : oldPropInfo.state();
            CompoundTag updatedPropNbt = propellerBE.saveWithoutMetadata(level.registryAccess());
            contraption.getBlocks().put(localPropPos, new StructureTemplate.StructureBlockInfo(localPropPos, updatedPropState, updatedPropNbt));
            contraption.invalidateRenderCache();
        }

        if (!hadChartBefore && !player.isCreative()) {
            handChartStack.shrink(1);
        }

        UUID airshipId = UUID.randomUUID();
        String shipName = "Airship-" + airshipId.toString().substring(0, 4).toUpperCase();

        TravellingAirship airship = new TravellingAirship(
                airshipId,
                shipName,
                route,
                cruiseSpeed,
                report.totalLift(),
                report.totalWeight(),
                maxCargoSlots
        );

        if (dockPos.equals(route.getDockB())) {
            airship.setState(TravellingAirship.FlightState.DOCKED_AT_B);
        } else {
            airship.setState(TravellingAirship.FlightState.DOCKED_AT_A);
        }
        airship.setBlockCount(actualShipBlocks.size());

        contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
        contraption.getStorage().initialize();
        airship.syncFromContraption(level, contraption);

        if (dockBe != null && fromDock > 0) {
            dockBe.drainAir(fromDock, false);
        }
        airship.configureAir(airScan.mountedEngines(), airScan.capacity(), airScan.storedAir() + fromDock);

        network.registerAirshipWithContraption(level, airship, contraption);

        if (player instanceof ServerPlayer sp) {
            ModAdvancements.award(sp, "first_flight");
            if (airship.getAirCapacity() >= 1800) {
                ModAdvancements.award(sp, "long_haul");
            }
        }

        return true;
    }

    /**
     * Malzeme bazlı blok ağırlığı hesabı:
     * - Balon Bloğu ve Aktif Isı Kaynakları (Kamp Ateşi / Airship Super Heater): 0 Ağırlık
     * - Gemi Ambarı (Airship Cargo Hold): 30 Ağırlık
     * - Sıvı Tankı (Fluid Tank): 10 Ağırlık
     * - Hafif Bloklar (Ahşap, Yün, Yelken, Cam): 1 Ağırlık
     * - Ağır Bloklar (Demir, Altın, Obsidyen, Örs): 3 Ağırlık
     * - Orta / Varsayılan Bloklar (Bakır, Casing, Makineler): 2 Ağırlık
     */
    public static int getBlockWeight(BlockState state) {
        if (CanvasBalloonBlock.isBalloonBlock(state) || CanvasBalloonBlock.isHeatSourceBlock(state)) {
            return 0;
        }
        if (state.getBlock() instanceof AirshipCargoHoldBlock) {
            return AirshipCargoHoldBlock.WEIGHT;
        }
        if (state.getBlock() instanceof FluidTankBlock) {
            return 10;
        }
        if (state.is(ModTags.MEDIUM_HULL_BLOCKS)) {
            return 2;
        }
        if (state.is(ModTags.LIGHT_HULL_BLOCKS)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.WOOL)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.IMPERMEABLE)) {
            return 1;
        }
        if (state.is(ModTags.HEAVY_HULL_BLOCKS)
                || state.is(Blocks.IRON_BLOCK)
                || state.is(Blocks.GOLD_BLOCK)
                || state.is(Blocks.OBSIDIAN)
                || state.is(BlockTags.ANVIL)) {
            return 3;
        }
        return 2;
    }

    private static void sendError(Player player, Component ignoredDetail) {
        player.displayClientMessage(
                Component.translatable("create_air_logistics.assembly.error.short").withStyle(ChatFormatting.RED),
                true
        );
    }
}
