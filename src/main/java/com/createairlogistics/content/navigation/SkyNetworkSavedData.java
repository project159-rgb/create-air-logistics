package com.createairlogistics.content.navigation;

import com.createairlogistics.CreateAirLogistics;
import com.createairlogistics.content.contraption.AirshipContraption;
import com.createairlogistics.content.contraption.AirshipEntity;
import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlockEntity;
import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlock;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlockEntity;
import com.createairlogistics.content.pressure.AirshipAirStorage;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sunucu dünyasında (ServerLevel) tüm görünmez hava koridorlarını (SkyRoute) ve
 * aktif uçan gemileri (TravellingAirship) kalıcı olarak saklayan, yüklü chunk'larda
 * gerçek Create Contraption tabanlı 3B AirshipEntity varlığını yöneten merkez.
 */
public class SkyNetworkSavedData extends SavedData {

    private static final String DATA_NAME = CreateAirLogistics.MOD_ID + "_sky_network";

    private final Map<UUID, SkyRoute> routes = new LinkedHashMap<>();
    private final Map<UUID, TravellingAirship> airships = new LinkedHashMap<>();
    /** Şaft soketinden dönüş alan limanlar (yüklü olmasalar bile kalkış hesabı için hatırlanır). */
    private final Set<BlockPos> poweredDocks = new HashSet<>();
    /** Bir rotanın ucu olup kırılmış (yerinde SkyDock bulunmayan) limanlar; yüklü olmasalar bile kalkış kararı için hatırlanır. */
    private final Set<BlockPos> brokenDocks = new HashSet<>();
    /** Bu tick içinde bekleme (HOLDING) durumundan serbest bırakılan gemilerin hedef limanları. Diske yazılmaz. */
    private transient final Set<BlockPos> claimedHoldDocksThisTick = new HashSet<>();

    /** Koltuk yolcularının yeniden oturtulması için oyuncu-gemi azami mesafesi (blok). */
    private static final double REMOUNT_RANGE = 48.0;

    private static final Factory<SkyNetworkSavedData> FACTORY = new Factory<>(SkyNetworkSavedData::new, SkyNetworkSavedData::load);

    /**
     * Bu SavedData'nın ait olduğu dünya. save() içinde canlı varlıkları senkronlamak için zayıf referansla tutulur
     * (dünya kapanınca GC'yi engellemez). Diske yazılmaz.
     */
    private transient WeakReference<ServerLevel> levelRef = new WeakReference<>(null);

    public static SkyNetworkSavedData get(ServerLevel level) {
        SkyNetworkSavedData data = level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
        data.bindLevel(level);
        return data;
    }

    private void bindLevel(ServerLevel level) {
        if (levelRef.get() != level) {
            levelRef = new WeakReference<>(level);
        }
    }

    public void tick(ServerLevel level) {
        bindLevel(level);
        claimedHoldDocksThisTick.clear();
        if (airships.isEmpty()) return;
        List<UUID> toDisassemble = null;
        boolean dirtyNeeded = false;
        for (TravellingAirship ship : airships.values()) {
            if (isShipDockDestroyed(level, ship)) {
                // Yalnızca gemi zaten yüklü bir bölgedeyse sök: uzaktaki bir liman kırıldı diye
                // tick ortasında yüklenmemiş chunk'ları asla zorla yükleme (gemi yüklenene kadar bekler).
                if (level.isLoaded(BlockPos.containing(ship.getCurrentPosition()))) {
                    if (toDisassemble == null) {
                        toDisassemble = new ArrayList<>();
                    }
                    toDisassemble.add(ship.getAirshipId());
                }
                continue;
            }
            boolean hadEntity = ship.getSpawnedEntityId() != null;
            if (upgradeLegacyRoute(level, ship)) {
                dirtyNeeded = true;
            }
            ship.tick(this);
            ensureEntitySpawnedIfChunkLoaded(level, ship);
            if (!ship.isDocked() || hadEntity || ship.getSpawnedEntityId() != null) {
                dirtyNeeded = true;
            }
        }
        if (toDisassemble != null) {
            dirtyNeeded = true;
            for (UUID id : toDisassemble) {
                disassembleAirship(level, id, null);
            }
        }
        if (dirtyNeeded) {
            setDirty();
        }
    }

    /**
     * Tüm canlı gemi varlıklarının contraption verisini (depolar, yapı) TravellingAirship'e yazar.
     * Otomatik kayıtta ve sunucu kapanırken çağrılır; hiçbir varlığı kaldırmaz.
     */
    public void syncAllLiveEntities(ServerLevel level) {
        for (TravellingAirship ship : airships.values()) {
            try {
                AirshipEntity live = ship.getLiveEntity(level);
                if (live != null && live.getContraption() != null) {
                    ship.syncFromContraption(level, live.getContraption());
                }
            } catch (Exception e) {
                CreateAirLogistics.LOGGER.warn("Failed to sync live airship {} before save", ship.getAirshipId(), e);
            }
        }
    }

    /**
     * Eski kayıtlardaki rotalarda liman yönleri (FACING) bulunmaz; iki liman da yüklüyse yönleri okuyup
     * rotayı yanaşma hizalı geometriye yükseltir (ilerleme oranı korunur).
     */
    private boolean upgradeLegacyRoute(ServerLevel level, TravellingAirship ship) {
        SkyRoute route = ship.getRoute();
        if (route.hasDockFacings()) {
            return false;
        }
        Direction facingA = readDockFacing(level, route.getDockA());
        Direction facingB = readDockFacing(level, route.getDockB());
        if (facingA == null || facingB == null) {
            return false;
        }
        SkyRoute upgraded = route.withFacings(facingA, facingB);
        ship.setRoute(upgraded);
        routes.put(upgraded.getRouteId(), upgraded);
        pruneOrphanRoutes();
        return true;
    }

    @Nullable
    public static Direction readDockFacing(ServerLevel level, BlockPos dockPos) {
        if (!level.isLoaded(dockPos)) {
            return null;
        }
        BlockState state = level.getBlockState(dockPos);
        return state.hasProperty(SkyDockBlock.FACING) ? state.getValue(SkyDockBlock.FACING) : null;
    }

    /**
     * Otomatik sökme yalnızca gemi bir rıhtıma bağlıyken (DOCKED_*) ve o liman ile geminin konumu yüklüyken,
     * limanın yerinde SkyDock kalmadıysa tetiklenir. Uçuş/bekleme sırasında (iki liman da kırık olsa bile) gemi
     * havada asla sökülmez: rıhtım ankrajına kadar uçmayı tamamlar ve orada güvenle sökülür; böylece uçuş
     * ortasında bir limanı kırıp yeniden yerleştirmek uçan gemiyi yok etmez.
     */
    private boolean isShipDockDestroyed(ServerLevel level, TravellingAirship ship) {
        if (ship.getState() == TravellingAirship.FlightState.DOCKED_AT_A) {
            BlockPos dockA = ship.getRoute().getDockA();
            return level.isLoaded(dockA)
                    && level.isLoaded(BlockPos.containing(ship.getCurrentPosition()))
                    && !(level.getBlockState(dockA).getBlock() instanceof SkyDockBlock);
        }
        if (ship.getState() == TravellingAirship.FlightState.DOCKED_AT_B) {
            BlockPos dockB = ship.getRoute().getDockB();
            return level.isLoaded(dockB)
                    && level.isLoaded(BlockPos.containing(ship.getCurrentPosition()))
                    && !(level.getBlockState(dockB).getBlock() instanceof SkyDockBlock);
        }
        return false;
    }

    public void ensureEntitySpawnedIfChunkLoaded(ServerLevel level, TravellingAirship ship) {
        Vec3 pos = ship.getCurrentPosition();
        BlockPos blockPos = BlockPos.containing(pos);
        UUID existingId = ship.getSpawnedEntityId();
        Entity existing = existingId != null ? level.getEntity(existingId) : null;

        // C-5: Koltukta oyuncu varken gemi asla boşaltılmaz; oyuncu, gemiyle birlikte chunk'ları ve simülasyonu taşır.
        if (existing instanceof AirshipEntity ae && !ae.isRemoved() && ae.hasPlayerPassenger()) {
            return;
        }

        if (!level.isLoaded(blockPos) || !level.areEntitiesLoaded(ChunkPos.asLong(blockPos)) || !level.isPositionEntityTicking(blockPos)) {
            if (existing instanceof AirshipEntity ae && !ae.isRemoved()) {
                // Boşaltmadan önce koltuk yolcularını ve en güncel contraption depolarını yakala
                ship.capturePassengersBeforeEntityUnload(ae);
                if (ae.getContraption() != null) {
                    ship.syncFromContraption(level, ae.getContraption());
                }
                ae.markSuppressRemovalSync();
                ae.discard();
            }
            if (existingId != null) {
                ship.setSpawnedEntityId(null);
            }
            return;
        }

        if (existing instanceof AirshipEntity && !existing.isRemoved()) {
            return;
        }

        // Aynı airshipId ile dünyada zaten yüklü bir AirshipEntity varsa onu bağla, fazladan kopyaları sil
        List<AirshipEntity> matches = level.getEntitiesOfClass(
                AirshipEntity.class,
                new AABB(blockPos).inflate(64.0),
                e -> !e.isRemoved() && ship.getAirshipId().equals(e.getAirshipId().orElse(null))
        );
        if (!matches.isEmpty()) {
            AirshipEntity primary = matches.getFirst();
            ship.setSpawnedEntityId(primary.getUUID());
            for (int i = 1; i < matches.size(); i++) {
                matches.get(i).markSuppressRemovalSync();
                matches.get(i).discard();
            }
            return;
        }

        if (ship.getStructureTag().isEmpty()) {
            return;
        }

        Contraption loaded = Contraption.fromNBT(level, ship.getStructureTag(), false);
        if (!(loaded instanceof AirshipContraption contraption) || contraption.getBlocks().isEmpty()) {
            return;
        }

        spawnEntityForContraption(level, ship, contraption);
    }

    private void spawnEntityForContraption(ServerLevel level, TravellingAirship ship, AirshipContraption contraption) {
        Vec3 pos = ship.getCurrentPosition();
        Direction forward = contraption.getShipForwardFacing();
        AirshipEntity entity = AirshipEntity.create(level, contraption, ship.getAirshipId(), forward, forward.toYRot());
        entity.setPos(pos.x, pos.y, pos.z);
        entity.syncWithTravellingAirship(level, ship);

        if (level.addFreshEntity(entity)) {
            ship.setSpawnedEntityId(entity.getUUID());
            remountPendingPassengers(level, ship, entity, pos);
        }
    }

    /**
     * C-5: Gemi varlığı yeniden doğduğunda, boşaltma sırasında saklanan canlıları ve oyuncuları koltuklarına geri oturtur.
     */
    private void remountPendingPassengers(ServerLevel level, TravellingAirship ship, AirshipEntity entity, Vec3 pos) {
        Contraption contraption = entity.getContraption();
        int seatCount = contraption != null ? contraption.getSeats().size() : 0;
        boolean changed = false;

        // --- Canlılar (oyuncu olmayan)
        for (TravellingAirship.SeatedMobData mobData : ship.consumePendingSeatedMobs()) {
            changed = true;
            Entity mob = level.getEntity(mobData.entityUuid());
            if (mob instanceof Player || (mob != null && mob.isRemoved())) {
                mob = null;
            }
            if (mob == null) {
                mob = EntityType.loadEntityRecursive(mobData.entityTag(), level, e -> {
                    e.moveTo(pos.x, pos.y, pos.z, e.getYRot(), e.getXRot());
                    return e;
                });
                // Kendi yolcularıyla birlikte ekle; UUID zaten dünyadaysa false döner (kopya oluşmaz)
                if (mob == null || !level.tryAddFreshEntityWithPassengers(mob)) {
                    CreateAirLogistics.LOGGER.warn("Could not respawn seated passenger {} of airship {}",
                            mobData.entityUuid(), ship.getAirshipId());
                    continue;
                }
            }
            mob.fallDistance = 0.0f;
            int seatIdx = mobData.seatIndex();
            if (seatIdx >= 0 && seatIdx < seatCount && !mob.isPassenger()) {
                entity.addSittingPassenger(mob, seatIdx);
            }
        }

        // --- Oyuncular
        Map<UUID, Integer> pendingPlayers = ship.getPendingSeatedPlayers();
        if (!pendingPlayers.isEmpty()) {
            double rangeSq = REMOUNT_RANGE * REMOUNT_RANGE;
            var it = pendingPlayers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Integer> entry = it.next();
                Player p = level.getPlayerByUUID(entry.getKey());
                if (p == null || p.isRemoved()) {
                    continue; // Çevrimdışı: girişte (tryRemountLoggingInPlayer) geri oturtulur
                }
                int seatIdx = entry.getValue();
                if (!p.isPassenger() && p.position().distanceToSqr(pos) <= rangeSq && seatIdx >= 0 && seatIdx < seatCount) {
                    p.fallDistance = 0.0f;
                    entity.addSittingPassenger(p, seatIdx);
                }
                // Çevrimiçi oyuncu için kayıt her durumda tüketilir (uzaklaşmışsa ileride beklenmedik ışınlanma olmaz)
                it.remove();
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
    }

    /**
     * C-5: Sunucuya giren oyuncu, çıkış/kapanış sırasında bir gemi koltuğundaysa onu aynı koltuğa geri oturtur.
     * Gemi varlığı henüz doğmamışsa (chunk'lar yükleniyor) kayıt korunur ve varlık doğduğunda
     * {@link #remountPendingPassengers} oyuncuyu oturtur.
     */
    public void tryRemountLoggingInPlayer(ServerLevel level, ServerPlayer serverPlayer) {
        bindLevel(level);
        UUID playerId = serverPlayer.getUUID();
        for (TravellingAirship ship : airships.values()) {
            Integer seatIdx = ship.getPendingSeatedPlayers().get(playerId);
            if (seatIdx == null) {
                continue;
            }
            serverPlayer.fallDistance = 0.0f;
            Vec3 pos = ship.getCurrentPosition();
            if (serverPlayer.position().distanceToSqr(pos) > REMOUNT_RANGE * REMOUNT_RANGE) {
                ship.getPendingSeatedPlayers().remove(playerId);
                setDirty();
                return;
            }

            ensureEntitySpawnedIfChunkLoaded(level, ship);
            AirshipEntity live = ship.getLiveEntity(level);
            if (live != null && live.getContraption() != null) {
                ship.getPendingSeatedPlayers().remove(playerId);
                if (!serverPlayer.isPassenger() && seatIdx >= 0 && seatIdx < live.getContraption().getSeats().size()) {
                    dropStalePlayerPassenger(live, playerId);
                    live.addSittingPassenger(serverPlayer, seatIdx);
                }
            }
            setDirty();
            return;
        }
    }

    /**
     * Çıkış yapan oyuncunun eski (kaldırılmış) ServerPlayer nesnesi hâlâ yolcu listesindeyse onu çıkarır;
     * aksi halde Create'in addSittingPassenger'ı aynı UUID'yi dolu koltuk sanıp yeni oyuncuyu oturtmaz.
     */
    private static void dropStalePlayerPassenger(AirshipEntity live, UUID playerId) {
        for (Entity passenger : new ArrayList<>(live.getPassengers())) {
            if (passenger instanceof Player stale && stale.isRemoved() && stale.getUUID().equals(playerId)) {
                stale.stopRiding();
            }
        }
    }

    /**
     * C-5: Oturumu kapatan oyuncu bir gemi koltuğundaysa koltuk indeksini kaydeder
     * (Vanilla çıkışta oyuncuyu araçtan indirir; girişte {@link #tryRemountLoggingInPlayer} geri oturtur).
     */
    public void recordLoggingOutPlayer(ServerPlayer serverPlayer) {
        if (!(serverPlayer.getVehicle() instanceof AirshipEntity airship) || airship.getContraption() == null) {
            return;
        }
        Optional<UUID> idOpt = airship.getAirshipId();
        if (idOpt.isEmpty()) {
            return;
        }
        TravellingAirship ship = airships.get(idOpt.get());
        if (ship == null) {
            return;
        }
        int seatIdx = TravellingAirship.resolveSeatIndex(airship.getContraption(), serverPlayer);
        if (seatIdx >= 0) {
            ship.getPendingSeatedPlayers().put(serverPlayer.getUUID(), seatIdx);
            setDirty();
        }
    }

    public void registerRoute(SkyRoute route) {
        routes.put(route.getRouteId(), route);
        setDirty();
    }

    public void registerAirship(ServerLevel level, TravellingAirship airship) {
        routes.put(airship.getRoute().getRouteId(), airship.getRoute());
        airships.put(airship.getAirshipId(), airship);
        ensureEntitySpawnedIfChunkLoaded(level, airship);
        setDirty();
    }

    public void registerAirshipWithContraption(ServerLevel level, TravellingAirship airship, AirshipContraption contraption) {
        routes.put(airship.getRoute().getRouteId(), airship.getRoute());
        airships.put(airship.getAirshipId(), airship);
        spawnEntityForContraption(level, airship, contraption);
        setDirty();
    }

    public Optional<TravellingAirship> getAirship(UUID airshipId) {
        return Optional.ofNullable(airships.get(airshipId));
    }

    /**
     * Uçan gemiyi (Contraption) söküp dünyaya gerçek bloklar halinde geri yerleştirir.
     */
    public boolean disassembleAirship(ServerLevel level, UUID airshipId, @Nullable Player player) {
        return !disassembleAirshipAndGetBlocks(level, airshipId, player).isEmpty();
    }

    public Set<BlockPos> disassembleAirshipAndGetBlocks(ServerLevel level, UUID airshipId, @Nullable Player player) {
        // Gemi henüz kaldırılmaz: elle sökme engellenirse gemi, rota ve varlık tamamen bozulmadan kalmalı
        TravellingAirship ship = airships.get(airshipId);
        if (ship == null) {
            return Collections.emptySet();
        }
        if (player != null
                && ship.getState() != TravellingAirship.FlightState.DOCKED_AT_A
                && ship.getState() != TravellingAirship.FlightState.DOCKED_AT_B) {
            return Collections.emptySet();
        }

        AirshipEntity liveEntity = ship.getLiveEntity(level);
        AirshipContraption contraption = null;
        if (liveEntity != null && liveEntity.getContraption() instanceof AirshipContraption liveContraption) {
            contraption = liveContraption;
        } else if (!ship.getStructureTag().isEmpty()) {
            Contraption loaded = Contraption.fromNBT(level, ship.getStructureTag(), false);
            if (loaded instanceof AirshipContraption loadedAirship) {
                contraption = loadedAirship;
            }
        }

        if (contraption == null || contraption.getBlocks().isEmpty()) {
            removeShipAndRoute(airshipId, ship);
            cleanupEntities(level, ship, BlockPos.containing(ship.getCurrentPosition()), airshipId);
            setDirty();
            return Collections.emptySet();
        }

        BlockPos targetPortPos;
        float rotationAngleY;

        if (ship.getState() == TravellingAirship.FlightState.DOCKED_AT_A
                || ship.getState() == TravellingAirship.FlightState.DOCKED_AT_B) {
            BlockPos dockPos = (ship.getState() == TravellingAirship.FlightState.DOCKED_AT_A)
                    ? ship.getRoute().getDockA()
                    : ship.getRoute().getDockB();
            BlockState dockState = level.getBlockState(dockPos);
            Direction dockFacing = dockState.hasProperty(SkyDockBlock.FACING)
                    ? dockState.getValue(SkyDockBlock.FACING)
                    : ((ship.getState() == TravellingAirship.FlightState.DOCKED_AT_A)
                            ? ship.getRoute().getFacingA()
                            : ship.getRoute().getFacingB());
            if (dockFacing != null) {
                targetPortPos = dockPos.relative(dockFacing, 2);
                Direction targetPortFacing = dockFacing.getOpposite();
                rotationAngleY = contraption.getInitialPortFacing().toYRot() - targetPortFacing.toYRot();
            } else {
                targetPortPos = BlockPos.containing(ship.getCurrentPosition());
                rotationAngleY = 0.0f;
            }
        } else {
            targetPortPos = BlockPos.containing(ship.getCurrentPosition());
            if (liveEntity != null) {
                rotationAngleY = -liveEntity.yaw + liveEntity.getInitialYaw();
            } else {
                boolean reverse = ship.isReverseLeg();
                Vec3 tangent = ship.getRoute().getTangent(ship.getProgress(), reverse);
                float flightYaw = (float) Math.toDegrees(Math.atan2(-tangent.x, tangent.z));
                rotationAngleY = -flightYaw + contraption.getShipForwardFacing().toYRot();
            }
        }

        StructureTransform transform = new StructureTransform(targetPortPos, 0.0f, rotationAngleY, 0.0f);

        // C-4: Elle sökmede (Sky Dock GUI) hedef alan tamamen boş değilse sökmeyi reddet — hiçbir şey değiştirilmez.
        if (player != null) {
            for (BlockPos localPos : contraption.getBlocks().keySet()) {
                BlockPos worldPos = transform.apply(localPos);
                boolean blocked = level.isOutsideBuildHeight(worldPos) || !level.isLoaded(worldPos);
                if (!blocked) {
                    BlockState worldState = level.getBlockState(worldPos);
                    blocked = !worldState.isAir()
                            && (!worldState.canBeReplaced()
                                || level.getBlockEntity(worldPos) != null
                                || worldState.getDestroySpeed(level, worldPos) < 0
                                || worldState.getBlock() instanceof SkyDockBlock);
                }
                if (blocked) {
                    player.displayClientMessage(
                            Component.translatable("create_air_logistics.assembly.error.disassemble_blocked")
                                    .withStyle(ChatFormatting.RED),
                            true
                    );
                    return Collections.emptySet();
                }
            }
        }

        // Kontrol geçti (veya patlama / yıkılmış liman kaynaklı zorunlu sökme): artık gemiyi ağdan çıkar
        removeShipAndRoute(airshipId, ship);

        // Dünyaya yerleştirirken ambar kapaklarının kapalı (OPEN=false) olmasını sağla
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : new ArrayList<>(contraption.getBlocks().entrySet())) {
            StructureTemplate.StructureBlockInfo info = entry.getValue();
            BlockState state = info.state();
            if (state.getBlock() instanceof AirshipCargoHoldBlock && state.hasProperty(AirshipCargoHoldBlock.OPEN)) {
                BlockState closed = state.setValue(AirshipCargoHoldBlock.OPEN, false);
                contraption.getBlocks().put(entry.getKey(), new StructureTemplate.StructureBlockInfo(info.pos(), closed, info.nbt()));
            }
        }

        // Gemi havuzunda kalan havayı önce motorlara, sonra backtank'lere geri yaz (hava kaybolmaz)
        AirshipAirStorage.writeBack(contraption, ship.getAir());
        contraption.stop(level);

        // C-4: Sıfır-kayıp engel kurtarma ön geçişi. Create'in addBlocksToWorld'ü engellenen blokları içerikleri olmadan
        // düşürür (veya mevcut blok varlıklarını yok eder); bunun yerine engellenen her bloğu önce içeriğiyle birlikte
        // güvenle düşürür, contraption'dan çıkarırız.
        Set<BlockPos> placedBlocks = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : new ArrayList<>(contraption.getBlocks().entrySet())) {
            BlockPos localPos = entry.getKey();
            StructureTemplate.StructureBlockInfo info = entry.getValue();
            BlockPos worldPos = transform.apply(localPos);
            BlockState placedState = transform.apply(info.state());

            boolean outOfBoundsOrUnloaded = level.isOutsideBuildHeight(worldPos) || !level.isLoaded(worldPos);
            BlockState worldState = outOfBoundsOrUnloaded ? Blocks.BEDROCK.defaultBlockState() : level.getBlockState(worldPos);
            boolean blocked = outOfBoundsOrUnloaded
                    || worldState.getDestroySpeed(level, worldPos) < 0
                    || level.getBlockEntity(worldPos) != null
                    || worldState.getBlock() instanceof SkyDockBlock
                    || (placedState.getCollisionShape(level, worldPos).isEmpty()
                        && !worldState.getCollisionShape(level, worldPos).isEmpty());

            if (!blocked) {
                placedBlocks.add(worldPos);
                continue;
            }

            contraption.getBlocks().remove(localPos);
            salvageBlockedBlock(level, contraption, localPos, info, placedState, worldPos, targetPortPos);
        }

        contraption.addBlocksToWorld(level, transform);
        if (liveEntity != null) {
            liveEntity.onDisassembled(transform);
        }

        // Gemi yüklü değilken saklanan koltuk canlıları: koltuklarının dönüştürülmüş konumunun üstüne güvenle bırak
        if (!ship.getPendingSeatedMobsView().isEmpty()) {
            spawnPendingMobsAfterDisassembly(level, ship, contraption, transform, targetPortPos);
        }

        cleanupEntities(level, ship, targetPortPos, airshipId);
        setDirty();
        return placedBlocks;
    }

    private boolean pruneOrphanRoutes() {
        Set<UUID> activeRouteIds = new HashSet<>(airships.size());
        for (TravellingAirship ship : airships.values()) {
            if (ship.getRoute() != null) {
                activeRouteIds.add(ship.getRoute().getRouteId());
                routes.putIfAbsent(ship.getRoute().getRouteId(), ship.getRoute());
            }
        }
        boolean changed = routes.keySet().retainAll(activeRouteIds);
        changed |= brokenDocks.removeIf(pos -> !isRouteReferencingDock(pos));
        return changed;
    }

    private void removeShipAndRoute(UUID airshipId, TravellingAirship ship) {
        boolean changed = airships.remove(airshipId) != null;
        // Artık hiçbir rotanın ucu olmayan kırık liman kayıtlarını ve sahipsiz rotaları buda
        changed |= pruneOrphanRoutes();
        if (changed) {
            setDirty();
        }
    }

    /**
     * Yerleştirilemeyen bir gemi bloğunu, içeriği (depolar, harita, ambar, hava) dahil olmak üzere item olarak düşürür.
     */
    private static void salvageBlockedBlock(ServerLevel level, AirshipContraption contraption, BlockPos localPos,
                                            StructureTemplate.StructureBlockInfo info, BlockState placedState,
                                            BlockPos worldPos, BlockPos fallbackPos) {
        BlockPos dropPos = clampToBuildHeight(level, worldPos);
        if (!level.isLoaded(dropPos)) {
            dropPos = clampToBuildHeight(level, fallbackPos);
            if (!level.isLoaded(dropPos)) {
                CreateAirLogistics.LOGGER.warn("Airship salvage: no loaded position to drop {} (local {}); skipped",
                        placedState, localPos);
                return;
            }
        }

        // Geçici blok varlığını contraption NBT'sinden yeniden kur (dünyaya EKLENMEZ, level atanmaz → yan etki yok)
        BlockEntity tempBe = null;
        if (placedState.getBlock() instanceof EntityBlock eb) {
            try {
                tempBe = eb.newBlockEntity(dropPos, placedState);
                if (tempBe != null && info.nbt() != null) {
                    CompoundTag cleaned = info.nbt().copy();
                    cleaned.remove("Controller");
                    cleaned.remove("LastKnownPos");
                    tempBe.loadWithComponents(cleaned, level.registryAccess());
                }
            } catch (Exception e) {
                CreateAirLogistics.LOGGER.warn("Airship salvage: failed to rebuild block entity for {}", placedState, e);
                tempBe = null;
            }
        }

        try {
            // Mounted (contraption) depo içeriğini düşürmeden önce blok varlığına geri yaz
            if (tempBe != null) {
                contraption.getStorage().unmount(level, info, dropPos, tempBe);
            }

            if (tempBe instanceof PathfinderPropellerBlockEntity propBe && propBe.hasChart()) {
                Containers.dropItemStack(level, dropPos.getX(), dropPos.getY(), dropPos.getZ(), propBe.removeHeldChart());
            }

            if (tempBe instanceof AirshipCargoHoldBlockEntity holdBe) {
                holdBe.dropContents(level, dropPos);
            } else if (tempBe instanceof Container container) {
                // dropItemStack yığınları böler → kaplar boşalır; getDrops (ör. shulker) içeriği tekrar kopyalamaz
                Containers.dropContents(level, dropPos, container);
            } else {
                MountedItemStorage storage = contraption.getStorage().getAllItemStorages().get(localPos);
                if (storage != null) {
                    for (int slot = 0; slot < storage.getSlots(); slot++) {
                        ItemStack stack = storage.getStackInSlot(slot);
                        if (!stack.isEmpty()) {
                            Containers.dropItemStack(level, dropPos.getX(), dropPos.getY(), dropPos.getZ(), stack.copy());
                            storage.setStackInSlot(slot, ItemStack.EMPTY);
                        }
                    }
                }
            }
        } catch (Exception e) {
            CreateAirLogistics.LOGGER.warn("Airship salvage: failed to drop contents of {}", placedState, e);
        }

        level.levelEvent(2001, dropPos, Block.getId(placedState));
        // getDrops blok varlığını kullanır → PressureEngine/PressureTank BACKTANK_AIR değeri korunur
        Block.dropResources(placedState, level, dropPos, tempBe);
    }

    private static BlockPos clampToBuildHeight(ServerLevel level, BlockPos pos) {
        int y = Mth.clamp(pos.getY(), level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1);
        return y == pos.getY() ? pos : new BlockPos(pos.getX(), y, pos.getZ());
    }

    /**
     * Yüklü olmayan gemiden saklanan koltuk canlılarını, sökülen geminin koltuk konumlarının üstünde
     * düşme hasarı almadan yeniden doğurur.
     */
    private void spawnPendingMobsAfterDisassembly(ServerLevel level, TravellingAirship ship, Contraption contraption,
                                                  StructureTransform transform, BlockPos fallbackPos) {
        List<BlockPos> seats = contraption.getSeats();
        for (TravellingAirship.SeatedMobData mobData : ship.consumePendingSeatedMobs()) {
            int seatIdx = mobData.seatIndex();
            BlockPos base = (seatIdx >= 0 && seatIdx < seats.size())
                    ? transform.apply(seats.get(seatIdx))
                    : fallbackPos;
            BlockPos safe = clampToBuildHeight(level, base.above());
            Entity mob = EntityType.loadEntityRecursive(mobData.entityTag(), level, e -> {
                e.moveTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, e.getYRot(), e.getXRot());
                return e;
            });
            if (mob == null) {
                CreateAirLogistics.LOGGER.warn("Could not restore seated passenger {} of disassembled airship {}",
                        mobData.entityUuid(), ship.getAirshipId());
                continue;
            }
            mob.fallDistance = 0.0f;
            if (!level.tryAddFreshEntityWithPassengers(mob)) {
                CreateAirLogistics.LOGGER.warn("Seated passenger {} already exists in level; skipped respawn",
                        mobData.entityUuid());
            }
        }
    }

    private void cleanupEntities(ServerLevel level, TravellingAirship ship, BlockPos centerPos, UUID airshipId) {
        if (ship.getSpawnedEntityId() != null) {
            Entity entity = level.getEntity(ship.getSpawnedEntityId());
            if (entity != null) {
                if (entity instanceof AirshipEntity airship) {
                    airship.markSuppressRemovalSync();
                }
                entity.discard();
            }
        }
        List<AirshipEntity> duplicates = level.getEntitiesOfClass(
                AirshipEntity.class,
                new AABB(centerPos).inflate(64.0),
                e -> !e.isRemoved() && airshipId.equals(e.getAirshipId().orElse(null))
        );
        for (AirshipEntity duplicate : duplicates) {
            duplicate.markSuppressRemovalSync();
            duplicate.discard();
        }
    }

    public void removeAirship(UUID airshipId) {
        boolean changed = airships.remove(airshipId) != null;
        changed |= pruneOrphanRoutes();
        if (changed) {
            setDirty();
        }
    }

    /**
     * Bir limanın dolu sayılıp sayılmadığı: orada yanaşık bir gemi ya da o limana son iniş bacağında olan bir gemi varsa.
     * İstekte bulunan gemi de aynı limana iniyorsa, hedefe daha yakın olan (eşitlikte küçük UUID) önceliklidir.
     */
    public boolean isDockOccupied(BlockPos dockPos, @Nullable UUID ignoreAirshipId) {
        TravellingAirship requester = ignoreAirshipId != null ? airships.get(ignoreAirshipId) : null;
        boolean requesterDescending = requester != null && requester.isDescendingTo(dockPos);
        for (TravellingAirship other : airships.values()) {
            if (other.getAirshipId().equals(ignoreAirshipId)) continue;
            if (other.isDockedAt(dockPos)) return true;
            if (other.isDescendingTo(dockPos)) {
                if (!requesterDescending) {
                    return true;
                }
                double otherRem = other.getDistanceToLegEnd();
                double reqRem = requester.getDistanceToLegEnd();
                if (otherRem < reqRem - 1e-6
                        || (Math.abs(otherRem - reqRem) <= 1e-6
                                && other.getAirshipId().compareTo(requester.getAirshipId()) < 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Bekleme (HOLDING) durumundaki bir geminin iniş için serbest bırakılıp bırakılamayacağı.
     * Aynı tick'te aynı liman için yalnızca tek bir gemi serbest bırakılır.
     */
    public boolean tryResumeFromHold(BlockPos dockPos, UUID airshipId) {
        if (claimedHoldDocksThisTick.contains(dockPos)) {
            return false;
        }
        if (isDockOccupied(dockPos, airshipId)) {
            return false;
        }
        claimedHoldDocksThisTick.add(dockPos.immutable());
        return true;
    }

    private boolean isRouteReferencingDock(BlockPos dockPos) {
        for (SkyRoute route : routes.values()) {
            if (route.getDockA().equals(dockPos) || route.getDockB().equals(dockPos)) {
                return true;
            }
        }
        return false;
    }

    /** Liman bloğu kırıldığında çağrılır: güç kaydını siler, bir rotanın ucuysa kırık olarak işaretler. */
    public void onDockRemoved(BlockPos dockPos) {
        setDockPowered(dockPos, false);
        if (isRouteReferencingDock(dockPos) && brokenDocks.add(dockPos.immutable())) {
            setDirty();
        }
    }

    /**
     * Liman eksik mi? Yüklüyse dünyadaki blok esas alınır (ve kırık kaydı güncellenir);
     * yüklü değilse son bilinen kırık kaydı kullanılır.
     */
    public boolean isDockMissing(ServerLevel level, BlockPos dockPos) {
        if (level.isLoaded(dockPos)) {
            boolean present = level.getBlockState(dockPos).getBlock() instanceof SkyDockBlock;
            if (present) {
                if (brokenDocks.remove(dockPos)) {
                    setDirty();
                }
                return false;
            }
            if (isRouteReferencingDock(dockPos) && brokenDocks.add(dockPos.immutable())) {
                setDirty();
            }
            return true;
        }
        return brokenDocks.contains(dockPos);
    }

    public Optional<TravellingAirship> getDockedAirshipAt(BlockPos dockPos) {
        for (TravellingAirship ship : airships.values()) {
            if (ship.isDockedAt(dockPos)) {
                return Optional.of(ship);
            }
        }
        return Optional.empty();
    }

    public Collection<TravellingAirship> getAirships() {
        return airships.values();
    }

    public boolean isDockPowered(BlockPos dockPos) {
        return poweredDocks.contains(dockPos);
    }

    public void setDockPowered(BlockPos dockPos, boolean powered) {
        boolean changed = powered ? poweredDocks.add(dockPos.immutable()) : poweredDocks.remove(dockPos);
        if (changed) {
            setDirty();
        }
    }

    /**
     * Liman wrench ile döndürüldüğünde ona bağlı rotaların yanaşma uçlarını yeni yöne göre yeniden kurar;
     * ilerleme oranı korunur, gemi ışınlanmaz.
     */
    public void onDockRotated(ServerLevel level, BlockPos dockPos, Direction newFacing) {
        // Liman (yeniden) yerleştirildi veya döndürüldü: artık kırık değil
        if (brokenDocks.remove(dockPos)) {
            setDirty();
        }
        Map<UUID, SkyRoute> updated = new LinkedHashMap<>();
        for (SkyRoute route : routes.values()) {
            boolean isA = route.getDockA().equals(dockPos);
            boolean isB = route.getDockB().equals(dockPos);
            if (!isA && !isB) {
                continue;
            }
            Direction facingA = isA ? newFacing : route.getFacingA();
            Direction facingB = isB ? newFacing : route.getFacingB();
            if (facingA == null) {
                facingA = readDockFacing(level, route.getDockA());
            }
            if (facingB == null) {
                facingB = readDockFacing(level, route.getDockB());
            }
            if (facingA == route.getFacingA() && facingB == route.getFacingB()) {
                continue; // Yön değişmedi (ör. aynı yönle yeniden yerleştirme): geometri aynı kalır
            }
            updated.put(route.getRouteId(), route.withFacings(facingA, facingB));
        }
        if (updated.isEmpty()) {
            return;
        }
        routes.putAll(updated);
        for (TravellingAirship ship : airships.values()) {
            SkyRoute replacement = updated.get(ship.getRoute().getRouteId());
            if (replacement != null) {
                ship.setRoute(replacement);
            }
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // Ara kayıtlar (/save-all, 5 dk otomatik kayıt, Çıkış) her zaman güncel contraption depolarını ve
        // koltuktaki yolcuların anlık görüntüsünü yazar — hiçbir varlık kaldırılmadan.
        ServerLevel liveLevel = levelRef.get();
        if (liveLevel != null) {
            syncAllLiveEntities(liveLevel);
        }

        ListTag routeList = new ListTag();
        for (SkyRoute route : routes.values()) {
            routeList.add(route.save());
        }
        tag.put("Routes", routeList);

        ListTag shipList = new ListTag();
        for (TravellingAirship ship : airships.values()) {
            shipList.add(ship.save(registries, liveLevel));
        }
        tag.put("Airships", shipList);

        ListTag poweredList = new ListTag();
        for (BlockPos pos : poweredDocks) {
            poweredList.add(new IntArrayTag(new int[]{pos.getX(), pos.getY(), pos.getZ()}));
        }
        tag.put("PoweredDocks", poweredList);

        ListTag brokenList = new ListTag();
        for (BlockPos pos : brokenDocks) {
            brokenList.add(new IntArrayTag(new int[]{pos.getX(), pos.getY(), pos.getZ()}));
        }
        tag.put("BrokenDocks", brokenList);
        return tag;
    }

    public static SkyNetworkSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SkyNetworkSavedData data = new SkyNetworkSavedData();
        ListTag routeList = tag.getList("Routes", Tag.TAG_COMPOUND);
        for (int i = 0; i < routeList.size(); i++) {
            try {
                SkyRoute route = SkyRoute.load(routeList.getCompound(i));
                data.routes.put(route.getRouteId(), route);
            } catch (Exception e) {
                CreateAirLogistics.LOGGER.warn("Skipping corrupted airship/route entry", e);
            }
        }

        ListTag shipList = tag.getList("Airships", Tag.TAG_COMPOUND);
        for (int i = 0; i < shipList.size(); i++) {
            try {
                TravellingAirship ship = TravellingAirship.load(shipList.getCompound(i), registries);
                data.airships.put(ship.getAirshipId(), ship);
            } catch (Exception e) {
                CreateAirLogistics.LOGGER.warn("Skipping corrupted airship/route entry", e);
            }
        }

        ListTag poweredList = tag.getList("PoweredDocks", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < poweredList.size(); i++) {
            int[] xyz = poweredList.getIntArray(i);
            if (xyz.length == 3) {
                data.poweredDocks.add(new BlockPos(xyz[0], xyz[1], xyz[2]));
            }
        }

        if (tag.contains("BrokenDocks", Tag.TAG_LIST)) {
            ListTag brokenList = tag.getList("BrokenDocks", Tag.TAG_INT_ARRAY);
            for (int i = 0; i < brokenList.size(); i++) {
                int[] xyz = brokenList.getIntArray(i);
                if (xyz.length == 3) {
                    data.brokenDocks.add(new BlockPos(xyz[0], xyz[1], xyz[2]));
                }
            }
        }
        data.pruneOrphanRoutes();
        return data;
    }
}
