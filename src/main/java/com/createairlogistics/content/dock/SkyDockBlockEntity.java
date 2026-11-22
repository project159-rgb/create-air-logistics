package com.createairlogistics.content.dock;

import com.createairlogistics.config.ModClientConfig;
import com.createairlogistics.config.ModServerConfig;
import com.createairlogistics.content.contraption.AirshipEntity;
import com.createairlogistics.content.navigation.SkyNetworkSavedData;
import com.createairlogistics.content.navigation.TravellingAirship;
import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.content.pressure.AirTooltip;
import com.createairlogistics.registry.ModAdvancements;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.foundation.blockEntity.ComparatorUtil;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.particle.AirParticleData;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Liman Vinci BlockEntity (Sky Dock BlockEntity)
 *
 * Liman üzerindeki 2 temel ayarı (Yükle/Boşalt ve Kalkış Şartı) barındırır.
 * Ayrıca huni, oluk (Chute), Funnel veya mekanik borularla gelen eşya/sıvıları
 * tampon envanterinde tutar ve yanaşan geminin Ambarlarına (veya gemideki Sandık/Varil/Vault'lara)
 * 1 blokluk boşluk üzerinden vinç kolu animasyonuyla aktarır.
 *
 * Basınçlı hava: wrench ile açılan şaft soketinden gelen dönüş, Copper Backtank'in birebir aynı
 * algoritmasıyla (aynı RPM → aynı dolum hızı, aynı 4.0 SU/RPM) limanın iç tankını doldurur. Tank gemi
 * uzaktayken de dolar; gemi kilitlenince hava anında gemi havuzuna aktarılır. Gemi, sefer için gereken
 * havaya sahip olmadan kalkmaz (varış limanı güçsüzse dönüş bacağı da hesaba katılır).
 */
public class SkyDockBlockEntity extends KineticBlockEntity {

    public enum DockMode {
        LOAD("create_air_logistics.sky_dock.mode.load"),
        UNLOAD("create_air_logistics.sky_dock.mode.unload");

        private final String translationKey;

        DockMode(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getTranslationKey() {
            return translationKey;
        }
    }

    public enum DepartureCondition {
        WAIT_UNTIL_FULL_OR_EMPTY(
                "create_air_logistics.sky_dock.departure.wait_until_full",
                "create_air_logistics.sky_dock.departure.wait_until_empty"
        ),
        DEPART_WHEN_DOCK_EMPTY(
                "create_air_logistics.sky_dock.departure.depart_when_dock_empty",
                "create_air_logistics.sky_dock.departure.depart_when_dock_full"
        ),
        REDSTONE_LOCK(
                "create_air_logistics.sky_dock.departure.redstone_lock",
                "create_air_logistics.sky_dock.departure.redstone_lock"
        );

        private final String loadKey;
        private final String unloadKey;

        DepartureCondition(String loadKey, String unloadKey) {
            this.loadKey = loadKey;
            this.unloadKey = unloadKey;
        }

        public String getTranslationKey() {
            return loadKey;
        }

        public String getTranslationKey(DockMode mode) {
            return mode == DockMode.UNLOAD ? unloadKey : loadKey;
        }
    }

    public static final int BUFFER_SLOTS = 18;
    public static final int FLUID_CAPACITY_MB = 16000;
    private static final int MIN_LOCK_TICKS = 12;

    private DockMode dockMode = DockMode.LOAD;
    private DepartureCondition departureCondition = DepartureCondition.WAIT_UNTIL_FULL_OR_EMPTY;
    private String customName = "";
    private String packageFilter = "";
    private boolean suppressBufferSync = false;

    private final ItemStackHandler itemBuffer = new ItemStackHandler(BUFFER_SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (!suppressBufferSync) {
                sendData();
            }
        }
    };

    private final FluidTank fluidBuffer = new FluidTank(FLUID_CAPACITY_MB) {
        @Override
        protected void onContentsChanged() {
            setChanged();
            if (!suppressBufferSync) {
                sendData();
            }
        }
    };

    /** Görsel bağlantı köprüsü uzama oranı (0.0 = kapalı, 1.0 = geminin ShipPort kapısına kilitlenmiş) */
    private float craneExtension = 0.0f;
    private float prevCraneExtension = 0.0f;
    private boolean craneExtending = false;
    private boolean readyToDepart = false;
    private int lockedTicks = 0;
    private int transferVisualTicks = 0;
    /** Köprü üzerinde görsel olarak kayan, en son aktarılan eşya (yalnızca istemciye senkronize edilir). */
    private ItemStack transferVisualStack = ItemStack.EMPTY;

    /** Mevcut yanaşma seansında en az 1 eşya/paket transfer edildi mi? (Sonsuz boş git-gel koruması) */
    private boolean transferredAtLeastOneItemThisSession = false;

    // ---------------------------------------------------------------- Basınçlı hava
    /** Limanın iç hava tankı (kapasite: {@link AirPressure#dockCapacity()}). */
    private int air = 0;
    private final AirPressure.Compressor compressor = new AirPressure.Compressor();
    private int lastSyncedAir = -1;
    private int airSyncCooldown = 0;
    private boolean poweredFlag = false;
    private int poweredCheckCooldown = 0;

    /** Yanaşık geminin basınç durumu (sunucu hesaplar, istemciye GUI/goggles için senkronlanır). */
    private boolean shipPressureKnown = false;
    private int shipAir = 0;
    private int shipAirCapacity = 0;
    private int shipAirRequired = 0;
    private int shipBlockCount = 0;
    private float shipCruiseSpeed = 0.35f;
    private int shipLift = 0;
    private int shipWeight = 0;

    // ---------------------------------------------------------------- Uçuş koridoru
    /** Yanaşık geminin bir sonraki bacağında koridoru kapatan blok (yoksa null); kalkış bu temizlenene dek tutulur. */
    @Nullable
    private BlockPos routeObstaclePos = null;
    @Nullable
    private BlockPos missingDestinationDockPos = null;
    private int obstacleCheckCooldown = 0;

    public SkyDockBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    @Override
    public void tick() {
        super.tick();
        prevCraneExtension = craneExtension;
        if (transferVisualTicks > 0) {
            transferVisualTicks--;
        }

        if (level == null) {
            return;
        }

        if (craneExtending) {
            craneExtension = Mth.clamp(craneExtension + 0.045f, 0.0f, 1.0f);
        } else {
            craneExtension = Mth.clamp(craneExtension - 0.055f, 0.0f, 1.0f);
        }

        if (level.isClientSide()) {
            spawnCompressorParticles();
            return;
        }

        tickCompressor();

        if (level instanceof ServerLevel serverLevel) {
            SkyNetworkSavedData network = SkyNetworkSavedData.get(serverLevel);
            updatePoweredFlag(network);
            Optional<TravellingAirship> dockedOpt = network.getDockedAirshipAt(worldPosition);

            if (dockedOpt.isPresent()) {
                handleDockedAirship(serverLevel, network, dockedOpt.get());
                updateShipPressureInfo(network, dockedOpt.get());
                if (--obstacleCheckCooldown <= 0) {
                    refreshRouteObstacle(serverLevel, network, dockedOpt.get());
                }
            } else {
                if (shipPressureKnown) {
                    shipPressureKnown = false;
                    sendData();
                }
                if (routeObstaclePos != null || missingDestinationDockPos != null) {
                    routeObstaclePos = null;
                    missingDestinationDockPos = null;
                    sendData();
                }
                obstacleCheckCooldown = 0;
                if (craneExtending || readyToDepart) {
                    craneExtending = false;
                    readyToDepart = false;
                    lockedTicks = 0;
                    transferredAtLeastOneItemThisSession = false;
                    sendData();
                }
            }
        }
    }

    private void handleDockedAirship(ServerLevel serverLevel, SkyNetworkSavedData network, TravellingAirship ship) {
        if (!serverLevel.isPositionEntityTicking(worldPosition)
                || !serverLevel.isPositionEntityTicking(BlockPos.containing(ship.getCurrentPosition()))) {
            return;
        }

        // H-3: Kargo aktarımı veya kalkış kararından önce canlı gemi varlığı ve contraption'ı hazır olmalı;
        // değilse doğurmayı dene, yine yoksa bu tick hiçbir şey yapma (sahte boş/dolu kararı verilmez).
        AirshipEntity liveEntity = ship.getLiveEntity(serverLevel);
        if (liveEntity == null || liveEntity.getContraption() == null) {
            network.ensureEntitySpawnedIfChunkLoaded(serverLevel, ship);
            liveEntity = ship.getLiveEntity(serverLevel);
            if (liveEntity == null || liveEntity.getContraption() == null) {
                return;
            }
        }

        boolean hasRedstoneSignal = serverLevel.hasNeighborSignal(worldPosition);
        // Kızıltaş kilidi aktifken kalkış başlatıldıysa iptal edip köprüyü geri uzat; ancak hava/kargo transferi kesilmez.
        if (readyToDepart && departureCondition == DepartureCondition.REDSTONE_LOCK && hasRedstoneSignal) {
            readyToDepart = false;
            if (!craneExtending) {
                craneExtending = true;
                sendData();
            }
        }

        // AŞAMA 3: Kalkış kararı verildiyse önce bağlantı köprüsünü geri çek, tam çekilince gemiyi kaldır!
        if (readyToDepart) {
            if (craneExtending) {
                craneExtending = false;
                serverLevel.playSound(null, worldPosition, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.45f, 1.15f);
                sendData();
            }
            if (craneExtension <= 0.02f) {
                // H-2: Varış limanı kırıksa kalkma; köprü geri uzanır, gemi liman yeniden kurulana dek bekler
                BlockPos nextDest = getNextLegDestination(ship);
                if (nextDest != null && network.isDockMissing(serverLevel, nextDest)) {
                    readyToDepart = false;
                    boolean changed = !Objects.equals(missingDestinationDockPos, nextDest);
                    missingDestinationDockPos = nextDest.immutable();
                    if (!craneExtending) {
                        craneExtending = true;
                        changed = true;
                    }
                    if (changed) {
                        sendData();
                    }
                    return;
                }
                // Son kapı: köprü çekildikten sonra koridor yeniden taranır; engel çıktıysa köprü geri uzanır
                if (refreshRouteObstacle(serverLevel, network, ship) != null) {
                    readyToDepart = false;
                    if (!craneExtending) {
                        craneExtending = true;
                        sendData();
                    }
                    return;
                }
                readyToDepart = false;
                lockedTicks = 0;
                transferredAtLeastOneItemThisSession = false;
                liveEntity = ship.getLiveEntity(serverLevel);
                if (liveEntity != null && liveEntity.getContraption() != null) {
                    ship.syncFromContraption(serverLevel, liveEntity.getContraption());
                }
                ship.departFromDock();
                network.setDirty();
                sendData();
            }
            return;
        }

        // AŞAMA 1: Gemi yeni yanaştıysa bağlantı köprüsünü ShipPort'a doğru uzat
        if (!craneExtending) {
            craneExtending = true;
            serverLevel.playSound(null, worldPosition, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.45f, 1.05f);
            sendData();
        }

        // Köprü henüz ShipPort'a tam kilitlenmediyse (%100 uzamadıysa) eşya transferine başlama
        if (craneExtension < 0.99f) {
            return;
        }

        if (lockedTicks == 0) {
            serverLevel.playSound(null, worldPosition, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.6f, 0.9f);
        }
        lockedTicks++;

        // Basınçlı hava: köprü kilitliyken limanın tankından gemi havuzuna anında aktar
        transferAirToShip(serverLevel, ship);

        // AŞAMA 2: Kilitlendikten sonra yüksek hızlı (varsayılan her 4 tickte 64 eşya + 4000 mB sıvı) transfer yap
        int transferInterval = ModServerConfig.dockTransferIntervalTicks();
        if (lockedTicks % transferInterval == 0) {
            int itemBatch = ModServerConfig.dockItemTransferBatch();
            int fluidBatch = ModServerConfig.dockFluidTransferBatchMb();
            boolean itemMoved;
            boolean fluidMoved = false;
            suppressBufferSync = true;
            try {
                if (dockMode == DockMode.LOAD) {
                    itemMoved = transferBatchToShip(serverLevel, ship, itemBatch);
                    if (itemMoved) {
                        transferredAtLeastOneItemThisSession = true;
                        transferVisualTicks = transferInterval + 2;
                    }
                    if (!fluidBuffer.isEmpty()) {
                        IFluidHandler liveFluids = ship.getLiveContraptionFluids(serverLevel);
                        if (liveFluids != null && liveFluids.getTanks() > 0) {
                            FluidStack moved = FluidUtil.tryFluidTransfer(liveFluids, fluidBuffer, fluidBatch, true);
                            if (!moved.isEmpty()) {
                                fluidMoved = true;
                                transferredAtLeastOneItemThisSession = true;
                            }
                        }
                    }
                } else {
                    itemMoved = transferBatchFromShip(serverLevel, ship, itemBatch);
                    if (itemMoved) {
                        transferredAtLeastOneItemThisSession = true;
                        transferVisualTicks = transferInterval + 2;
                    }
                    IFluidHandler liveFluids = ship.getLiveContraptionFluids(serverLevel);
                    if (liveFluids != null && liveFluids.getTanks() > 0) {
                        FluidStack moved = FluidUtil.tryFluidTransfer(fluidBuffer, liveFluids, fluidBatch, true);
                        if (!moved.isEmpty()) {
                            fluidMoved = true;
                            transferredAtLeastOneItemThisSession = true;
                        }
                    }
                }
            } finally {
                suppressBufferSync = false;
            }
            if (itemMoved || fluidMoved) {
                liveEntity = ship.getLiveEntity(serverLevel);
                if (liveEntity != null && liveEntity.getContraption() != null) {
                    ship.syncFromContraption(serverLevel, liveEntity.getContraption());
                }
                network.setDirty();
                ModAdvancements.awardNearby(serverLevel, worldPosition, "air_freight");
                sendData();
            }
        }

        if (lockedTicks < MIN_LOCK_TICKS) {
            return;
        }

        boolean shouldDepart = switch (departureCondition) {
            case WAIT_UNTIL_FULL_OR_EMPTY ->
                    dockMode == DockMode.LOAD
                            ? isShipLoadFull(serverLevel, ship)
                            : isShipUnloadEmpty(serverLevel, ship);
            case DEPART_WHEN_DOCK_EMPTY ->
                    dockMode == DockMode.LOAD
                            ? (isShipLoadFull(serverLevel, ship)
                                || (transferredAtLeastOneItemThisSession && !canTransferAnyLoadableToShip(serverLevel, ship)))
                            : (isShipUnloadEmpty(serverLevel, ship)
                                || !canAcceptAnyUnloadableFromShip(serverLevel, ship));
            case REDSTONE_LOCK -> !hasRedstoneSignal;
        };

        if (shouldDepart) {
            // Sefer için yeterli basınç yoksa kalkma: liman tankı doldukça aktarılır, gemi burada güvenle bekler
            if (ship.getAir() + 1e-6 < ship.getRequiredAirForDeparture(network)) {
                return;
            }
            // H-2: Varış limanı kırıksa kalkma; gemi burada güvenle bekler (liman yeniden kurulunca devam eder)
            BlockPos nextDest = getNextLegDestination(ship);
            if (nextDest != null && network.isDockMissing(serverLevel, nextDest)) {
                readyToDepart = false;
                boolean changed = !Objects.equals(missingDestinationDockPos, nextDest);
                missingDestinationDockPos = nextDest.immutable();
                if (!craneExtending) {
                    craneExtending = true;
                    changed = true;
                }
                if (changed) {
                    sendData();
                }
                return;
            }
            // Uçuş koridoru kapalıysa kalkma: gemi limanda güvenle bekler (engel periyodik taramayla temizlenir).
            // Engel bilinmiyorsa kalkış kararından önce taze bir tarama yapılır.
            BlockPos obstacle = routeObstaclePos != null ? routeObstaclePos : refreshRouteObstacle(serverLevel, network, ship);
            if (obstacle != null) {
                readyToDepart = false;
                if (!craneExtending) {
                    craneExtending = true;
                    sendData();
                }
                return;
            }
            readyToDepart = true;
            craneExtending = false;
            serverLevel.playSound(null, worldPosition, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 0.45f, 1.15f);
            sendData();
        }
    }

    /**
     * Yanaşık geminin bir sonraki bacağının varış limanı (refreshRouteObstacle ile aynı kural).
     * Not: TravellingAirship#getDestinationDock yanaşıkken mevcut (bulunulan) limanı döndürür.
     */
    private static BlockPos getNextLegDestination(TravellingAirship ship) {
        return ship.getState() == TravellingAirship.FlightState.DOCKED_AT_B
                ? ship.getRoute().getDockA()
                : ship.getRoute().getDockB();
    }

    private boolean matchesDockFilter(ItemStack stack) {
        if (packageFilter.isBlank()) {
            return true;
        }
        if (PackageItem.isPackage(stack)) {
            return PackageItem.matchAddress(stack, packageFilter);
        }
        return true;
    }

    private boolean isShipLoadFull(ServerLevel serverLevel, TravellingAirship ship) {
        boolean hasItems = ship.hasItemStorage(serverLevel);
        boolean hasFluids = ship.hasFluidStorage(serverLevel);
        return (!hasItems || ship.isCargoFull(serverLevel))
                && (!hasFluids || ship.isFluidFull(serverLevel));
    }

    private boolean isShipUnloadEmpty(ServerLevel serverLevel, TravellingAirship ship) {
        boolean unloadableItemsEmpty = ship.getLiveContraptionStorage(serverLevel) != null
                ? !hasUnloadableItemsOnShip(serverLevel, ship)
                : ship.isCargoEmpty(serverLevel);
        if (!unloadableItemsEmpty) {
            return false;
        }
        boolean hasFluids = ship.hasFluidStorage(serverLevel);
        return !hasFluids || ship.isFluidEmpty(serverLevel);
    }

    private boolean canTransferAnyLoadableToShip(ServerLevel level, TravellingAirship ship) {
        IItemHandlerModifiable liveStorage = ship.getLiveContraptionStorage(level);
        if (liveStorage != null && liveStorage.getSlots() > 0) {
            for (int i = 0; i < itemBuffer.getSlots(); i++) {
                ItemStack inSlot = itemBuffer.getStackInSlot(i);
                if (!inSlot.isEmpty() && matchesDockFilter(inSlot)) {
                    ItemStack extractedOne = itemBuffer.extractItem(i, 1, true);
                    if (!extractedOne.isEmpty()
                            && ItemHandlerHelper.insertItemStacked(liveStorage, extractedOne, true).isEmpty()) {
                        return true;
                    }
                }
            }
        }
        if (!fluidBuffer.isEmpty()) {
            IFluidHandler liveFluids = ship.getLiveContraptionFluids(level);
            if (liveFluids != null && liveFluids.getTanks() > 0
                    && !FluidUtil.tryFluidTransfer(liveFluids, fluidBuffer, ModServerConfig.dockFluidTransferBatchMb(), false).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUnloadableItemsOnShip(ServerLevel level, TravellingAirship ship) {
        IItemHandlerModifiable liveStorage = ship.getLiveContraptionStorage(level);
        if (liveStorage == null) {
            return !ship.isCargoEmpty(level);
        }
        for (int slot = 0; slot < liveStorage.getSlots(); slot++) {
            ItemStack stack = liveStorage.getStackInSlot(slot);
            if (!stack.isEmpty() && matchesDockFilter(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean canInsertIntoDockBuffer(ItemStack probe) {
        if (probe.isEmpty()) {
            return false;
        }
        ItemStack rem = probe;
        for (int i = 0; i < itemBuffer.getSlots(); i++) {
            rem = itemBuffer.insertItem(i, rem, true);
            if (rem.isEmpty()) {
                return true;
            }
        }
        return rem.getCount() < probe.getCount();
    }

    private boolean canAcceptAnyUnloadableFromShip(ServerLevel level, TravellingAirship ship) {
        IItemHandlerModifiable liveStorage = ship.getLiveContraptionStorage(level);
        if (liveStorage != null) {
            for (int slot = 0; slot < liveStorage.getSlots(); slot++) {
                ItemStack inShip = liveStorage.getStackInSlot(slot);
                if (!inShip.isEmpty() && matchesDockFilter(inShip)) {
                    ItemStack extractedOne = liveStorage.extractItem(slot, 1, true);
                    if (!extractedOne.isEmpty() && canInsertIntoDockBuffer(extractedOne)) {
                        return true;
                    }
                }
            }
        }
        IFluidHandler liveFluids = ship.getLiveContraptionFluids(level);
        if (liveFluids != null && liveFluids.getTanks() > 0
                && !FluidUtil.tryFluidTransfer(fluidBuffer, liveFluids, ModServerConfig.dockFluidTransferBatchMb(), false).isEmpty()) {
            return true;
        }
        return false;
    }

    private boolean transferBatchToShip(ServerLevel serverLevel, TravellingAirship ship, int maxItems) {
        IItemHandlerModifiable liveStorage = ship.getLiveContraptionStorage(serverLevel);
        if (liveStorage == null || liveStorage.getSlots() == 0 || maxItems <= 0) {
            return false;
        }
        int remaining = maxItems;
        boolean movedAny = false;
        for (int i = 0; i < itemBuffer.getSlots() && remaining > 0; i++) {
            ItemStack inSlot = itemBuffer.getStackInSlot(i);
            if (inSlot.isEmpty() || !matchesDockFilter(inSlot)) {
                continue;
            }
            int toExtract = Math.min(remaining, inSlot.getCount());
            ItemStack simulated = itemBuffer.extractItem(i, toExtract, true);
            if (simulated.isEmpty()) {
                continue;
            }
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(liveStorage, simulated, false);
            int accepted = simulated.getCount() - remainder.getCount();
            if (accepted > 0) {
                itemBuffer.extractItem(i, accepted, false);
                transferVisualStack = simulated.copyWithCount(1);
                remaining -= accepted;
                movedAny = true;
            }
        }
        return movedAny;
    }

    private boolean transferBatchFromShip(ServerLevel serverLevel, TravellingAirship ship, int maxItems) {
        IItemHandlerModifiable liveStorage = ship.getLiveContraptionStorage(serverLevel);
        if (liveStorage == null || liveStorage.getSlots() == 0 || maxItems <= 0) {
            return false;
        }
        int remaining = maxItems;
        boolean movedAny = false;
        for (int slot = 0; slot < liveStorage.getSlots() && remaining > 0; slot++) {
            ItemStack inShip = liveStorage.getStackInSlot(slot);
            if (inShip.isEmpty() || !matchesDockFilter(inShip)) {
                continue;
            }
            int toExtract = Math.min(remaining, inShip.getCount());
            ItemStack simulatedExtract = liveStorage.extractItem(slot, toExtract, true);
            if (simulatedExtract.isEmpty()) {
                continue;
            }
            ItemStack leftover = insertIntoDockBuffer(simulatedExtract);
            int insertedCount = simulatedExtract.getCount() - leftover.getCount();
            if (insertedCount > 0) {
                liveStorage.extractItem(slot, insertedCount, false);
                transferVisualStack = simulatedExtract.copyWithCount(1);
                remaining -= insertedCount;
                movedAny = true;
            }
        }
        return movedAny;
    }

    private ItemStack insertIntoDockBuffer(ItemStack stack) {
        ItemStack current = stack;
        for (int i = 0; i < itemBuffer.getSlots() && !current.isEmpty(); i++) {
            current = itemBuffer.insertItem(i, current, false);
        }
        return current;
    }

    public boolean isDockBufferEmpty() {
        for (int i = 0; i < itemBuffer.getSlots(); i++) {
            if (!itemBuffer.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean isDockFluidEmpty() {
        return fluidBuffer.isEmpty();
    }

    public boolean isDockBufferFull() {
        for (int i = 0; i < itemBuffer.getSlots(); i++) {
            ItemStack stack = itemBuffer.getStackInSlot(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Basınçlı hava

    /** Copper Backtank ile aynı dolum algoritması; şaft dönerken (gemi uzaktayken de) tankı doldurur. */
    private void tickCompressor() {
        int capacity = AirPressure.dockCapacity();
        if (getSpeed() != 0.0f && air < capacity) {
            int increment = compressor.tick(getSpeed());
            if (increment > 0) {
                int prevComparator = getComparatorOutput();
                air = Math.min(capacity, air + increment);
                setChanged();
                if (getComparatorOutput() != prevComparator && level != null) {
                    level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
                }
            }
        }
        if (airSyncCooldown > 0) {
            airSyncCooldown--;
        } else if (air != lastSyncedAir) {
            lastSyncedAir = air;
            airSyncCooldown = 10;
            sendData();
        }
    }

    /** Create backtank'i gibi dolarken içe doğru çekilen hava parçacıkları. */
    private void spawnCompressorParticles() {
        if (level == null || !level.isClientSide || getSpeed() == 0.0f || air >= AirPressure.dockCapacity()) {
            return;
        }
        if (!ModClientConfig.shouldSpawnSkyDockParticle(level.random)) {
            return;
        }
        if (level.random.nextInt(3) != 0) {
            return;
        }
        Vec3 center = VecHelper.getCenterOf(worldPosition);
        Vec3 v = VecHelper.offsetRandomly(center, level.random, 0.65f);
        Vec3 m = center.subtract(v);
        level.addParticle(new AirParticleData(1.0f, 0.05f), v.x, v.y, v.z, m.x, m.y, m.z);
    }

    /** "Güçlü liman" bayrağını ağda günceller: varış limanı güçlüyse gemi dönüş havasını taşımak zorunda değildir. */
    private void updatePoweredFlag(SkyNetworkSavedData network) {
        boolean powered = getSpeed() != 0.0f;
        if (poweredCheckCooldown > 0 && powered == poweredFlag) {
            poweredCheckCooldown--;
            return;
        }
        poweredCheckCooldown = 40;
        if (powered != poweredFlag || network.isDockPowered(worldPosition) != powered) {
            poweredFlag = powered;
            network.setDockPowered(worldPosition, powered);
        }
    }

    private void transferAirToShip(ServerLevel serverLevel, TravellingAirship ship) {
        if (air <= 0) {
            return;
        }
        int space = (int) Math.floor(ship.getAirCapacity() - ship.getAir());
        int moved = Math.min(Math.min(space, air), AirPressure.DOCK_TRANSFER_PER_TICK);
        if (moved <= 0) {
            return;
        }
        int prevComparator = getComparatorOutput();
        air -= moved;
        ship.addAir(moved);
        setChanged();
        SkyNetworkSavedData.get(serverLevel).setDirty();
        if (getComparatorOutput() != prevComparator) {
            serverLevel.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
        if (lockedTicks % 6 == 0) {
            serverLevel.playSound(null, worldPosition, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.BLOCKS, 0.25f, 1.6f);
        }
    }

    private void updateShipPressureInfo(SkyNetworkSavedData network, TravellingAirship ship) {
        int newAir = (int) Math.floor(ship.getAir());
        int newCapacity = ship.getAirCapacity();
        int newRequired = ship.getRequiredAirForDeparture(network);
        int newBlockCount = ship.getBlockCount();
        float newCruiseSpeed = (float) ship.getBlocksPerTick();
        int newLift = ship.getTotalLift();
        int newWeight = ship.getTotalWeight();
        boolean airChangedSignificantly = newAir != shipAir
                && (Math.abs(newAir - shipAir) >= 20 || newAir >= newRequired || newAir == 0 || newAir == newCapacity);
        if (!shipPressureKnown
                || airChangedSignificantly
                || newCapacity != shipAirCapacity
                || newRequired != shipAirRequired
                || newBlockCount != shipBlockCount
                || Float.compare(newCruiseSpeed, shipCruiseSpeed) != 0
                || newLift != shipLift
                || newWeight != shipWeight) {
            shipPressureKnown = true;
            shipAir = newAir;
            shipAirCapacity = newCapacity;
            shipAirRequired = newRequired;
            shipBlockCount = newBlockCount;
            shipCruiseSpeed = newCruiseSpeed;
            shipLift = newLift;
            shipWeight = newWeight;
            sendData();
        }
    }

    /**
     * Yanaşık geminin bir sonraki bacağı için uçuş koridorunu tarar (ucuz: ~40-60 örnek, yüklenmemiş chunk'lar atlanır),
     * sonucu önbelleğe alır ve değiştiyse istemciye (Sky Dock menüsü / goggles) senkronlar.
     */
    @Nullable
    private BlockPos refreshRouteObstacle(ServerLevel serverLevel, SkyNetworkSavedData network, TravellingAirship ship) {
        obstacleCheckCooldown = 20;
        BlockPos obstacle = null;
        BlockPos missingDest = null;
        if (ship.getRoute() != null) {
            boolean nextReverse = ship.getState() == TravellingAirship.FlightState.DOCKED_AT_B;
            obstacle = ship.getRoute().findCorridorObstruction(serverLevel, nextReverse, Set.of());
            BlockPos nextDest = getNextLegDestination(ship);
            missingDest = (nextDest != null && network.isDockMissing(serverLevel, nextDest))
                    ? nextDest.immutable()
                    : null;
        }
        if (!Objects.equals(obstacle, routeObstaclePos) || !Objects.equals(missingDest, missingDestinationDockPos)) {
            routeObstaclePos = obstacle;
            missingDestinationDockPos = missingDest;
            sendData();
        }
        return obstacle;
    }

    @Nullable
    public BlockPos getRouteObstaclePos() {
        return routeObstaclePos;
    }

    @Nullable
    public BlockPos getMissingDestinationDockPos() {
        return missingDestinationDockPos;
    }

    /** Montaj sırasında gemiye ilk havayı vermek için tanktan çeker. */
    public int drainAir(int maxAmount, boolean simulate) {
        int drained = Math.max(0, Math.min(maxAmount, air));
        if (!simulate && drained > 0) {
            air -= drained;
            setChanged();
            sendData();
            if (level != null) {
                level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            }
        }
        return drained;
    }

    public int getAir() {
        return air;
    }

    public int getComparatorOutput() {
        return ComparatorUtil.fractionToRedstoneLevel((double) air / AirPressure.dockCapacity());
    }

    public boolean hasShaftSocket() {
        return getBlockState().hasProperty(SkyDockBlock.SHAFT) && getBlockState().getValue(SkyDockBlock.SHAFT).isOpen();
    }

    public boolean isShipPressureKnown() {
        return shipPressureKnown;
    }

    public int getShipAir() {
        return shipAir;
    }

    public int getShipAirCapacity() {
        return shipAirCapacity;
    }

    public int getShipAirRequired() {
        return shipAirRequired;
    }

    public int getShipBlockCount() {
        return shipBlockCount;
    }

    public float getShipCruiseSpeed() {
        return shipCruiseSpeed;
    }

    public int getShipLift() {
        return shipLift;
    }

    public int getShipWeight() {
        return shipWeight;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!tooltip.isEmpty()) {
            tooltip.add(Component.empty());
        }
        AirTooltip.header(tooltip, Component.translatable("block.create_air_logistics.sky_dock"));
        if (hasCustomName()) {
            tooltip.add(Component.literal("    ").append(
                    Component.translatable("tooltip.create_air_logistics.dock.station_name", customName)
                            .withStyle(ChatFormatting.GOLD)));
        }
        if (hasPackageFilter()) {
            tooltip.add(Component.literal("    ").append(
                    Component.translatable("tooltip.create_air_logistics.dock.package_filter", packageFilter)
                            .withStyle(ChatFormatting.AQUA)));
        }
        AirTooltip.airBar(tooltip, Component.translatable("tooltip.create_air_logistics.dock_tank"), air, AirPressure.dockCapacity());
        if (!fluidBuffer.isEmpty()) {
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.dock_fluid",
                    fluidBuffer.getFluid().getHoverName(), fluidBuffer.getFluidAmount(), FLUID_CAPACITY_MB), true);
        }
        if (!hasShaftSocket()) {
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.dock.no_socket"), false);
        } else if (getSpeed() == 0.0f) {
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.dock.no_rotation"), false);
        }
        if (shipPressureKnown) {
            AirTooltip.airBar(tooltip, Component.translatable("tooltip.create_air_logistics.ship_air"), shipAir, shipAirCapacity);
            boolean ok = shipAir >= shipAirRequired;
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.ship_required", shipAirRequired), ok);
        }
        if (missingDestinationDockPos != null) {
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.dock.dest_missing",
                    missingDestinationDockPos.getX(), missingDestinationDockPos.getY(), missingDestinationDockPos.getZ()), false);
        }
        if (routeObstaclePos != null) {
            AirTooltip.status(tooltip, Component.translatable("tooltip.create_air_logistics.dock.route_blocked",
                    routeObstaclePos.getX(), routeObstaclePos.getY(), routeObstaclePos.getZ()), false);
        }
        return true;
    }

    private static String sanitizeConfigText(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32).trim() : trimmed;
    }

    public String getCustomName() {
        return customName;
    }

    public boolean hasCustomName() {
        return !customName.isBlank();
    }

    public Component getStationDisplayComponent() {
        return hasCustomName()
                ? Component.literal(customName)
                : Component.translatable("create_air_logistics.chart.default_dock_name", worldPosition.toShortString());
    }

    public String getStationDisplayName() {
        return getStationDisplayComponent().getString();
    }

    public String getPackageFilter() {
        return packageFilter;
    }

    public boolean hasPackageFilter() {
        return !packageFilter.isBlank();
    }

    private void cancelPendingDeparture() {
        if (readyToDepart) {
            readyToDepart = false;
            craneExtending = true;
        }
    }

    public void setConfiguration(String customName, String packageFilter) {
        String nextName = sanitizeConfigText(customName);
        String nextFilter = sanitizeConfigText(packageFilter);
        if (!Objects.equals(this.customName, nextName) || !Objects.equals(this.packageFilter, nextFilter)) {
            if (!Objects.equals(this.packageFilter, nextFilter)) {
                cancelPendingDeparture();
            }
            this.customName = nextName;
            this.packageFilter = nextFilter;
            setChanged();
            sendData();
        }
    }

    public void toggleDockMode() {
        dockMode = (dockMode == DockMode.LOAD) ? DockMode.UNLOAD : DockMode.LOAD;
        cancelPendingDeparture();
        setChanged();
        sendData();
    }

    public void cycleDepartureCondition() {
        DepartureCondition[] values = DepartureCondition.values();
        departureCondition = values[(departureCondition.ordinal() + 1) % values.length];
        cancelPendingDeparture();
        setChanged();
        sendData();
    }

    public DockMode getDockMode() {
        return dockMode;
    }

    public DepartureCondition getDepartureCondition() {
        return departureCondition;
    }

    public ItemStackHandler getItemBuffer() {
        return itemBuffer;
    }

    public FluidTank getFluidBuffer() {
        return fluidBuffer;
    }

    public float getCraneExtension(float partialTicks) {
        return Mth.lerp(partialTicks, prevCraneExtension, craneExtension);
    }

    public boolean isTransferringVisual() {
        return transferVisualTicks > 0;
    }

    public ItemStack getTransferVisualStack() {
        return transferVisualStack;
    }

    public float getTransferVisualProgress(float partialTicks) {
        if (transferVisualTicks <= 0) {
            return 0.0f;
        }
        float duration = ModServerConfig.dockTransferIntervalTicks() + 2;
        return Mth.clamp((duration - (transferVisualTicks - partialTicks)) / duration, 0.0f, 1.0f);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("DockMode", dockMode.name());
        tag.putString("DepartureCondition", departureCondition.name());
        tag.putString("CustomName", customName);
        tag.putString("PackageFilter", packageFilter);
        tag.put("Items", itemBuffer.serializeNBT(registries));
        CompoundTag fluidTag = new CompoundTag();
        fluidBuffer.writeToNBT(registries, fluidTag);
        tag.put("Fluid", fluidTag);
        tag.putFloat("CraneExtension", craneExtension);
        tag.putBoolean("CraneExtending", craneExtending);
        tag.putBoolean("ReadyToDepart", readyToDepart);
        tag.putBoolean("TransferredThisSession", transferredAtLeastOneItemThisSession);
        tag.putInt("TransferVisualTicks", transferVisualTicks);
        if (clientPacket && !transferVisualStack.isEmpty()) {
            tag.put("TransferStack", transferVisualStack.save(registries));
        }
        tag.putInt("Air", air);
        tag.putInt("CompressorTimer", compressor.getTimer());
        if (clientPacket && shipPressureKnown) {
            tag.putInt("ShipAir", shipAir);
            tag.putInt("ShipAirCapacity", shipAirCapacity);
            tag.putInt("ShipAirRequired", shipAirRequired);
            tag.putInt("ShipBlockCount", shipBlockCount);
            tag.putFloat("ShipCruiseSpeed", shipCruiseSpeed);
            tag.putInt("ShipLift", shipLift);
            tag.putInt("ShipWeight", shipWeight);
        }
        if (routeObstaclePos != null) {
            tag.put("RouteObstacle", NbtUtils.writeBlockPos(routeObstaclePos));
        }
        if (missingDestinationDockPos != null) {
            tag.put("MissingDestinationDock", NbtUtils.writeBlockPos(missingDestinationDockPos));
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("DockMode")) {
            try {
                dockMode = DockMode.valueOf(tag.getString("DockMode"));
            } catch (IllegalArgumentException ignored) {
                dockMode = DockMode.LOAD;
            }
        }
        if (tag.contains("DepartureCondition")) {
            try {
                departureCondition = DepartureCondition.valueOf(tag.getString("DepartureCondition"));
            } catch (IllegalArgumentException ignored) {
                departureCondition = DepartureCondition.WAIT_UNTIL_FULL_OR_EMPTY;
            }
        }
        customName = sanitizeConfigText(tag.getString("CustomName"));
        packageFilter = sanitizeConfigText(tag.getString("PackageFilter"));
        if (tag.contains("Items")) {
            itemBuffer.deserializeNBT(registries, tag.getCompound("Items"));
        }
        if (tag.contains("Fluid")) {
            fluidBuffer.readFromNBT(registries, tag.getCompound("Fluid"));
        }
        craneExtending = tag.getBoolean("CraneExtending");
        readyToDepart = tag.getBoolean("ReadyToDepart");
        transferredAtLeastOneItemThisSession = tag.getBoolean("TransferredThisSession");
        int incomingTransferTicks = tag.getInt("TransferVisualTicks");
        if (incomingTransferTicks > 0) {
            transferVisualTicks = incomingTransferTicks;
        }
        if (clientPacket && tag.contains("TransferStack")) {
            transferVisualStack = ItemStack.parseOptional(registries, tag.getCompound("TransferStack"));
        }
        air = Mth.clamp(tag.getInt("Air"), 0, AirPressure.dockCapacity());
        compressor.setTimer(tag.getInt("CompressorTimer"));
        if (clientPacket) {
            shipPressureKnown = tag.contains("ShipAir");
            shipAir = tag.getInt("ShipAir");
            shipAirCapacity = tag.getInt("ShipAirCapacity");
            shipAirRequired = tag.getInt("ShipAirRequired");
            shipBlockCount = tag.getInt("ShipBlockCount");
            shipCruiseSpeed = tag.contains("ShipCruiseSpeed") ? tag.getFloat("ShipCruiseSpeed") : 0.35f;
            shipLift = tag.getInt("ShipLift");
            shipWeight = tag.getInt("ShipWeight");
        }
        routeObstaclePos = NbtUtils.readBlockPos(tag, "RouteObstacle").orElse(null);
        missingDestinationDockPos = NbtUtils.readBlockPos(tag, "MissingDestinationDock").orElse(null);
        if (!clientPacket) {
            craneExtension = tag.getFloat("CraneExtension");
        }
    }
}
