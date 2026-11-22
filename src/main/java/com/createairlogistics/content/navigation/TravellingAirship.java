package com.createairlogistics.content.navigation;

import com.createairlogistics.content.contraption.AirshipEntity;
import com.createairlogistics.content.pressure.AirPressure;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SavedData içinde yaşayan sanal/kalıcı uçan gemi temsili.
 * Yüklü olmayan chunk'larda bile sıfır TPS yüküyle rotasında ilerler;
 * limana vardığında ShipPort bloğunu hedef SkyDock bloğunun 1 blok karşısına otomatik hizalar.
 */
public class TravellingAirship {

    public enum FlightState {
        DOCKED_AT_A,
        FLYING_A_TO_B,
        HOLDING_ABOVE_B,
        DOCKED_AT_B,
        FLYING_B_TO_A,
        HOLDING_ABOVE_A
    }

    private final UUID airshipId;
    private String name;
    private SkyRoute route;
    private FlightState state;
    private double progress;          // [0.0, 1.0] — yay uzunluğuna göre (gerçek mesafeyle doğrusal)
    private double blocksPerTick;     // Seyir hızı (blok/tick)
    /** Anlık hız oranı [0,1]: kalkışta yumuşak hızlanma, varışta sabit yavaşlama (görsel itki için de kullanılır). */
    private double speedFactor = 0.0;
    private int totalLift;
    private int totalWeight;
    private int blockCount = 0;
    private int maxCargoSlots;        // Her AirshipCargoHold bloğu başına 9 yuva
    private int occupiedCargoSlots = 0;
    private boolean cachedCargoEmpty = true;
    private boolean cachedCargoFull = false;
    private CompoundTag shipContraptionData;
    private UUID spawnedEntityId;

    // ---------------------------------------------------------------- Koltuk yolcusu koruması (C-5)
    /**
     * Gemi varlığı (AirshipEntity) boşaltılırken koltukta oturan bir canlının (oyuncu olmayan) tam NBT anlık görüntüsü.
     * Vanilla, araç kaldırıldığında yolcuyu yere bırakır; mob'u havada düşürmemek ve kopyalamamak için
     * serileştirip dünyadan kaldırırız, gemi yeniden doğduğunda aynı koltuğa geri oturturuz.
     */
    public record SeatedMobData(int seatIndex, UUID entityUuid, CompoundTag entityTag) {}

    /** Gemi varlığı yeniden doğduğunda koltuklarına geri oturtulacak canlılar. */
    private final List<SeatedMobData> pendingSeatedMobs = new ArrayList<>();
    /** Oyuncu UUID → koltuk indeksi. Oyuncular serileştirilmez; yeniden doğuşta/girişte tekrar oturtulur. */
    private final Map<UUID, Integer> pendingSeatedPlayers = new LinkedHashMap<>();

    // ---------------------------------------------------------------- Basınçlı hava havuzu
    /** Gemideki motorlar + backtank'lerin ortak havası. */
    private double air = 0.0;
    /** Havuz kapasitesi (motor iç depoları + backtank kapasiteleri). */
    private int airCapacity = 0;
    /** Pervanelere takılı, hava harcayan motor sayısı. */
    private int engineCount = 0;
    /** Mevcut bacağın kalkışta hesaplanan hava maliyeti ve kalkış anındaki hava (ilerlemeye bağlı tüketim için). */
    private double legAirCost = 0.0;
    private double legStartAir = 0.0;

    /** Kalkışta tam hıza ulaşma ivmesi (tick başına hız oranı artışı, ~1.5 sn). */
    private static final double ACCELERATION_PER_TICK = 0.035;
    /** Varışta durma noktasına çok yakınken bile ilerlemeyi garanti eden minimum hız oranı. */
    private static final double MIN_SPEED_FACTOR = 0.05;

    public TravellingAirship(UUID airshipId, String name, SkyRoute route, double blocksPerTick, int totalLift, int totalWeight, int maxCargoSlots) {
        this.airshipId = airshipId;
        this.name = name;
        this.route = route;
        this.state = FlightState.DOCKED_AT_A;
        this.progress = 0.0;
        this.blocksPerTick = Mth.clamp(blocksPerTick, 0.05, 1.0); // Maks 20 blok/sn
        this.totalLift = totalLift;
        this.totalWeight = totalWeight;
        this.maxCargoSlots = Math.max(9, maxCargoSlots);
        this.shipContraptionData = new CompoundTag();
    }

    public void tick(SkyNetworkSavedData network) {
        switch (state) {
            case FLYING_A_TO_B -> advance(network, route.getDockB(), false,
                    FlightState.HOLDING_ABOVE_B, FlightState.DOCKED_AT_B);
            case FLYING_B_TO_A -> advance(network, route.getDockA(), true,
                    FlightState.HOLDING_ABOVE_A, FlightState.DOCKED_AT_A);
            case HOLDING_ABOVE_B -> {
                speedFactor = 0.0;
                // Aynı tick'te tek bir bekleyen gemi serbest bırakılır; serbest kalan gemi hemen ilerler
                // (iniş bacağına girer) ki diğer bekleyenler onu isDescendingTo ile dolu görsün.
                if (network.tryResumeFromHold(route.getDockB(), airshipId)) {
                    state = FlightState.FLYING_A_TO_B;
                    advance(network, route.getDockB(), false,
                            FlightState.HOLDING_ABOVE_B, FlightState.DOCKED_AT_B);
                }
            }
            case HOLDING_ABOVE_A -> {
                speedFactor = 0.0;
                if (network.tryResumeFromHold(route.getDockA(), airshipId)) {
                    state = FlightState.FLYING_B_TO_A;
                    advance(network, route.getDockA(), true,
                            FlightState.HOLDING_ABOVE_A, FlightState.DOCKED_AT_A);
                }
            }
            case DOCKED_AT_A, DOCKED_AT_B -> {
                // Liman (SkyDockBlockEntity) yükleme/boşaltma şartı tamamlandığında departFromDock() çağrılır
                speedFactor = 0.0;
            }
        }
    }

    /**
     * Rotada ilerletir. Hız profili:
     * - Kalkış: hız oranı tick başına {@link #ACCELERATION_PER_TICK} kadar artar (ani sıçrama yok).
     * - Varış: v = vmax * sqrt(kalan / frenMesafesi) — sabit yavaşlama, durma noktasına tam oturur.
     * - Hedef liman doluysa durma noktası, iniş bacağının başıdır; gemi orada süzülerek bekler (ışınlanma yok).
     */
    private void advance(SkyNetworkSavedData network, BlockPos destinationDock, boolean reverse,
                         FlightState holdState, FlightState dockedState) {
        double total = route.getTotalArcLength();
        boolean occupied = network.isDockOccupied(destinationDock, airshipId);
        double stopProgress = occupied ? Math.max(progress, route.getApproachProgress(reverse)) : 1.0;

        double brakingDistance = Mth.clamp(blocksPerTick * 40.0, 6.0, 16.0);
        double remaining = Math.max(0.0, (stopProgress - progress) * total);
        double brakeLimit = Math.sqrt(Mth.clamp(remaining / brakingDistance, 0.0, 1.0));

        double target = Math.max(MIN_SPEED_FACTOR, brakeLimit);
        speedFactor = Math.min(target, speedFactor + ACCELERATION_PER_TICK);
        speedFactor = Math.max(speedFactor, MIN_SPEED_FACTOR);

        progress = Math.min(stopProgress, progress + blocksPerTick * speedFactor / total);
        // Hava, katedilen mesafeyle orantılı harcanır: bacak bittiğinde tam olarak kalkışta ayrılan miktar düşmüş olur.
        // Süzülerek bekleme (ilerleme yok) hava harcamaz; gemi havada asla basınçsız kalmaz.
        if (legAirCost > 0.0) {
            air = Math.max(0.0, legStartAir - legAirCost * progress);
        }
        if (progress >= stopProgress - 1e-9) {
            if (stopProgress >= 1.0) {
                progress = 1.0;
                state = dockedState;
            } else {
                state = holdState;
            }
            speedFactor = 0.0;
        }
    }

    public void departFromDock() {
        if (state == FlightState.DOCKED_AT_A) {
            progress = 0.0;
            speedFactor = 0.0;
            state = FlightState.FLYING_A_TO_B;
            beginLegAirAccounting();
        } else if (state == FlightState.DOCKED_AT_B) {
            progress = 0.0;
            speedFactor = 0.0;
            state = FlightState.FLYING_B_TO_A;
            beginLegAirAccounting();
        }
    }

    private void beginLegAirAccounting() {
        legAirCost = Math.min(air, getLegAirCost());
        legStartAir = air;
    }

    /** Bir bacağın (A→B veya B→A, uzunlukları eşittir) hava maliyeti. */
    public int getLegAirCost() {
        return AirPressure.legCost(route.getTotalArcLength(), blocksPerTick, engineCount);
    }

    /** Bu limandan kalkış için gereken hava (varış limanı güçsüzse dönüş bacağı dahil). */
    public int getRequiredAirForDeparture(SkyNetworkSavedData network) {
        BlockPos destination = (state == FlightState.DOCKED_AT_B) ? route.getDockA() : route.getDockB();
        return AirPressure.requiredForDeparture(getLegAirCost(), network.isDockPowered(destination));
    }

    public double getAir() {
        return air;
    }

    public int getAirCapacity() {
        return airCapacity;
    }

    public int getEngineCount() {
        return engineCount;
    }

    public double getBlocksPerTick() {
        return blocksPerTick;
    }

    public void configureAir(int engineCount, int airCapacity, double initialAir) {
        this.engineCount = Math.max(0, engineCount);
        this.airCapacity = Math.max(0, airCapacity);
        this.air = Math.max(0.0, Math.min(initialAir, this.airCapacity));
    }

    /** @return havuza gerçekten eklenen miktar */
    public int addAir(int amount) {
        int accepted = (int) Math.max(0, Math.min(amount, Math.floor(airCapacity - air)));
        air += accepted;
        return accepted;
    }

    public void setState(FlightState state) {
        this.state = state;
    }

    public boolean isReverseLeg() {
        return state == FlightState.FLYING_B_TO_A || state == FlightState.HOLDING_ABOVE_A || state == FlightState.DOCKED_AT_A;
    }

    public boolean isDocked() {
        return state == FlightState.DOCKED_AT_A || state == FlightState.DOCKED_AT_B;
    }

    /** AirshipEntity konum uzayında (ShipPort bloğunun alt-orta noktası) mevcut konum. */
    public Vec3 getCurrentPosition() {
        if (state == FlightState.DOCKED_AT_A) {
            return route.getAnchorA();
        }
        if (state == FlightState.DOCKED_AT_B) {
            return route.getAnchorB();
        }
        return route.getPosition(progress, isReverseLeg());
    }

    public float getCurrentSteerYaw() {
        return route.getSteerYawDegrees(progress, isReverseLeg());
    }

    public float getCurrentSteerPitch() {
        return route.getSteerPitchDegrees(progress, isReverseLeg());
    }

    /** Mevcut bacağın başından itibaren katedilen mesafe (blok). */
    public double getDistanceFromLegStart() {
        return progress * route.getTotalArcLength();
    }

    /** Mevcut bacağın hedef limanına kalan mesafe (blok). */
    public double getDistanceToLegEnd() {
        return (1.0 - progress) * route.getTotalArcLength();
    }

    /** Mevcut bacağın hedef limanı. */
    public BlockPos getDestinationDock() {
        return isReverseLeg() ? route.getDockA() : route.getDockB();
    }

    public double getSpeedFactor() {
        return speedFactor;
    }

    public void setRoute(SkyRoute route) {
        this.route = route;
    }

    public boolean isDockedAt(BlockPos dockPos) {
        if (state == FlightState.DOCKED_AT_A && route.getDockA().equals(dockPos)) return true;
        return state == FlightState.DOCKED_AT_B && route.getDockB().equals(dockPos);
    }

    /** Gemi bu limana son iniş bacağında mı (yanaşma ilerlemesini geçmiş, uçuyor)? */
    public boolean isDescendingTo(BlockPos dockPos) {
        if (state == FlightState.FLYING_A_TO_B && route.getDockB().equals(dockPos)) {
            return progress >= route.getApproachProgress(false) - 1e-9;
        }
        if (state == FlightState.FLYING_B_TO_A && route.getDockA().equals(dockPos)) {
            return progress >= route.getApproachProgress(true) - 1e-9;
        }
        return false;
    }

    @Nullable
    public AirshipEntity getLiveEntity(ServerLevel level) {
        if (spawnedEntityId != null) {
            Entity e = level.getEntity(spawnedEntityId);
            if (e instanceof AirshipEntity airship && !airship.isRemoved()) {
                return airship;
            }
        }
        return null;
    }

    @Nullable
    public IItemHandlerModifiable getLiveContraptionStorage(ServerLevel level) {
        AirshipEntity entity = getLiveEntity(level);
        if (entity != null && entity.getContraption() != null) {
            return entity.getContraption().getStorage().getAllItems();
        }
        return null;
    }

    @Nullable
    public IFluidHandler getLiveContraptionFluids(ServerLevel level) {
        AirshipEntity entity = getLiveEntity(level);
        if (entity != null && entity.getContraption() != null) {
            return entity.getContraption().getStorage().getFluids();
        }
        return null;
    }

    public boolean hasItemStorage(ServerLevel level) {
        IItemHandlerModifiable live = getLiveContraptionStorage(level);
        return live != null && live.getSlots() > 0;
    }

    public boolean hasFluidStorage(ServerLevel level) {
        IFluidHandler fluids = getLiveContraptionFluids(level);
        return fluids != null && fluids.getTanks() > 0;
    }

    public boolean isFluidEmpty(ServerLevel level) {
        IFluidHandler fluids = getLiveContraptionFluids(level);
        if (fluids == null) {
            return true;
        }
        for (int t = 0; t < fluids.getTanks(); t++) {
            if (!fluids.getFluidInTank(t).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean isFluidFull(ServerLevel level) {
        IFluidHandler fluids = getLiveContraptionFluids(level);
        if (fluids == null || fluids.getTanks() <= 0) {
            return false;
        }
        for (int t = 0; t < fluids.getTanks(); t++) {
            if (fluids.getFluidInTank(t).getAmount() < fluids.getTankCapacity(t)) {
                return false;
            }
        }
        return true;
    }

    public void syncFromContraption(ServerLevel level, Contraption contraption) {
        this.shipContraptionData = contraption.writeNBT(level.registryAccess(), false);
        this.blockCount = contraption.getBlocks().size();
        IItemHandlerModifiable items = contraption.getStorage().getAllItems();
        IFluidHandler fluids = contraption.getStorage().getFluids();

        int occupied = 0;
        boolean hasItems = items != null && items.getSlots() > 0;
        boolean itemsEmpty = true;
        boolean itemsFull = hasItems;
        if (hasItems) {
            for (int i = 0; i < items.getSlots(); i++) {
                ItemStack s = items.getStackInSlot(i);
                if (!s.isEmpty()) {
                    occupied++;
                    itemsEmpty = false;
                    if (s.getCount() < Math.min(s.getMaxStackSize(), items.getSlotLimit(i))) {
                        itemsFull = false;
                    }
                } else {
                    itemsFull = false;
                }
            }
        }

        boolean hasFluids = fluids != null && fluids.getTanks() > 0;
        boolean fluidsEmpty = true;
        boolean fluidsFull = hasFluids;
        if (hasFluids) {
            for (int t = 0; t < fluids.getTanks(); t++) {
                if (!fluids.getFluidInTank(t).isEmpty()) {
                    occupied++;
                    fluidsEmpty = false;
                }
                if (fluids.getFluidInTank(t).getAmount() < fluids.getTankCapacity(t)) {
                    fluidsFull = false;
                }
            }
        }

        this.occupiedCargoSlots = occupied;
        this.cachedCargoEmpty = itemsEmpty && fluidsEmpty;
        this.cachedCargoFull = (hasItems || hasFluids)
                && (!hasItems || itemsFull)
                && (!hasFluids || fluidsFull);
    }

    // ---------------------------------------------------------------- Koltuk yolcusu koruması (C-5)

    /**
     * Yolcunun contraption üzerindeki koltuk indeksini çözer.
     *
     * @return geçerli koltuk indeksi veya koltukta değilse -1
     */
    public static int resolveSeatIndex(Contraption contraption, Entity passenger) {
        if (contraption == null || passenger == null) {
            return -1;
        }
        List<BlockPos> seats = contraption.getSeats();
        Integer mapped = contraption.getSeatMapping().get(passenger.getUUID());
        int idx = mapped != null ? mapped : -1;
        if (idx < 0) {
            BlockPos seatPos = contraption.getSeatOf(passenger.getUUID());
            idx = seatPos != null ? seats.indexOf(seatPos) : -1;
        }
        return (idx >= 0 && idx < seats.size()) ? idx : -1;
    }

    /**
     * Gemi varlığı kaldırılmadan (chunk boşaltma / simülasyon dışı kalma) hemen önce çağrılır.
     * - Oyuncular: koltuk indeksi hatırlanır (yeniden doğuşta veya girişte geri oturtulur), düşme mesafesi sıfırlanır.
     * - Diğer canlılar: tam NBT'leri saklanıp dünyadan kaldırılır; böylece Vanilla onları havada bırakıp
     *   düşürmez ve gemi yeniden doğduğunda kopyalanmazlar.
     */
    public void capturePassengersBeforeEntityUnload(AirshipEntity entity) {
        Contraption contraption = entity.getContraption();
        if (contraption == null) {
            return;
        }
        for (Entity passenger : new ArrayList<>(entity.getPassengers())) {
            int seatIndex = resolveSeatIndex(contraption, passenger);
            if (seatIndex < 0) {
                continue;
            }
            if (passenger instanceof Player player) {
                pendingSeatedPlayers.put(player.getUUID(), seatIndex);
                player.fallDistance = 0.0f;
            } else if (!passenger.isRemoved()) {
                CompoundTag mobTag = new CompoundTag();
                if (passenger.saveAsPassenger(mobTag)) {
                    UUID mobUuid = passenger.getUUID();
                    pendingSeatedMobs.removeIf(data -> data.entityUuid().equals(mobUuid));
                    pendingSeatedMobs.add(new SeatedMobData(seatIndex, mobUuid, mobTag));
                    passenger.discard();
                }
            }
        }
    }

    /** Bekleyen koltuk canlılarını döndürür ve listeyi temizler (yeniden oturtma / sökme sırasında). */
    public List<SeatedMobData> consumePendingSeatedMobs() {
        if (pendingSeatedMobs.isEmpty()) {
            return List.of();
        }
        List<SeatedMobData> consumed = new ArrayList<>(pendingSeatedMobs);
        pendingSeatedMobs.clear();
        return consumed;
    }

    /** Bekleyen koltuk canlılarının salt-okunur görünümü. */
    public List<SeatedMobData> getPendingSeatedMobsView() {
        return Collections.unmodifiableList(pendingSeatedMobs);
    }

    /**
     * Bekleyen koltuk oyuncuları (UUID → koltuk indeksi). Canlı (değiştirilebilir) haritadır;
     * {@link SkyNetworkSavedData} başarıyla geri oturtulan oyuncuları buradan kaldırır.
     */
    public Map<UUID, Integer> getPendingSeatedPlayers() {
        return pendingSeatedPlayers;
    }

    public List<ItemStack> getCarriedCargo() {
        return List.of();
    }

    public int getOccupiedCargoSlots() {
        return occupiedCargoSlots;
    }

    public boolean isCargoEmpty() {
        return cachedCargoEmpty;
    }

    public boolean isCargoEmpty(ServerLevel level) {
        IItemHandlerModifiable live = getLiveContraptionStorage(level);
        if (live != null) {
            for (int i = 0; i < live.getSlots(); i++) {
                if (!live.getStackInSlot(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }
        return cachedCargoEmpty;
    }

    public boolean isCargoFull() {
        return cachedCargoFull;
    }

    public boolean isCargoFull(ServerLevel level) {
        IItemHandlerModifiable live = getLiveContraptionStorage(level);
        if (live == null) {
            return false;
        }
        if (live.getSlots() == 0) {
            return true;
        }
        for (int i = 0; i < live.getSlots(); i++) {
            ItemStack stack = live.getStackInSlot(i);
            if (stack.isEmpty() || stack.getCount() < Math.min(stack.getMaxStackSize(), live.getSlotLimit(i))) {
                return false;
            }
        }
        return true;
    }

    public int getMaxCargoSlots() {
        return maxCargoSlots;
    }

    public UUID getAirshipId() {
        return airshipId;
    }

    public String getName() {
        return name;
    }

    public SkyRoute getRoute() {
        return route;
    }

    public FlightState getState() {
        return state;
    }

    public double getProgress() {
        return progress;
    }

    public CompoundTag getStructureTag() {
        return shipContraptionData;
    }

    public void setStructureTag(CompoundTag tag) {
        this.shipContraptionData = tag != null ? tag : new CompoundTag();
    }

    public UUID getSpawnedEntityId() {
        return spawnedEntityId;
    }

    public void setSpawnedEntityId(UUID spawnedEntityId) {
        this.spawnedEntityId = spawnedEntityId;
    }

    public int getTotalLift() {
        return totalLift;
    }

    public int getTotalWeight() {
        return totalWeight;
    }

    public int getBlockCount() {
        return blockCount;
    }

    public void setBlockCount(int blockCount) {
        this.blockCount = Math.max(0, blockCount);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        return save(registries, null);
    }

    /**
     * @param liveLevel null değilse ve canlı varlık yüklüyse, koltukta oturan yolcuların anlık görüntüsü de
     *                  kaydedilir (yolcular dünyadan KALDIRILMAZ — yalnızca otomatik kayıt/çıkış için snapshot).
     */
    public CompoundTag save(HolderLookup.Provider registries, @Nullable ServerLevel liveLevel) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", airshipId);
        tag.putString("Name", name);
        tag.put("Route", route.save());
        tag.putString("State", state.name());
        tag.putDouble("Progress", progress);
        tag.putDouble("SpeedFactor", speedFactor);
        tag.putDouble("Speed", blocksPerTick);
        tag.putInt("Lift", totalLift);
        tag.putInt("Weight", totalWeight);
        tag.putInt("BlockCount", blockCount);
        tag.putInt("MaxCargoSlots", maxCargoSlots);

        tag.putInt("OccupiedSlots", occupiedCargoSlots);
        tag.putBoolean("CargoEmpty", cachedCargoEmpty);
        tag.putBoolean("CargoFull", cachedCargoFull);
        tag.put("Contraption", shipContraptionData);
        if (spawnedEntityId != null) {
            tag.putUUID("SpawnedEntityId", spawnedEntityId);
        }
        tag.putDouble("Air", air);
        tag.putInt("AirCapacity", airCapacity);
        tag.putInt("Engines", engineCount);
        tag.putDouble("LegAirCost", legAirCost);
        tag.putDouble("LegStartAir", legStartAir);

        // Koltuk yolcuları: bekleyenler + (varsa) canlı varlıktaki anlık yolcular
        List<SeatedMobData> mobsToSave = new ArrayList<>(pendingSeatedMobs);
        Map<UUID, Integer> playersToSave = new LinkedHashMap<>(pendingSeatedPlayers);
        if (liveLevel != null) {
            AirshipEntity live = getLiveEntity(liveLevel);
            if (live != null && live.getContraption() != null) {
                Contraption contraption = live.getContraption();
                for (Entity passenger : new ArrayList<>(live.getPassengers())) {
                    int seatIndex = resolveSeatIndex(contraption, passenger);
                    if (seatIndex < 0) {
                        continue;
                    }
                    if (passenger instanceof Player player) {
                        playersToSave.put(player.getUUID(), seatIndex);
                    } else if (!passenger.isRemoved()) {
                        CompoundTag mobTag = new CompoundTag();
                        if (passenger.saveAsPassenger(mobTag)) {
                            UUID mobUuid = passenger.getUUID();
                            mobsToSave.removeIf(data -> data.entityUuid().equals(mobUuid));
                            mobsToSave.add(new SeatedMobData(seatIndex, mobUuid, mobTag));
                        }
                    }
                }
            }
        }

        ListTag mobList = new ListTag();
        for (SeatedMobData mob : mobsToSave) {
            CompoundTag mobEntry = new CompoundTag();
            mobEntry.putInt("SeatIndex", mob.seatIndex());
            mobEntry.putUUID("EntityUuid", mob.entityUuid());
            mobEntry.put("EntityData", mob.entityTag());
            mobList.add(mobEntry);
        }
        tag.put("SeatedMobs", mobList);

        ListTag playerList = new ListTag();
        for (Map.Entry<UUID, Integer> entry : playersToSave.entrySet()) {
            CompoundTag playerEntry = new CompoundTag();
            playerEntry.putUUID("PlayerUuid", entry.getKey());
            playerEntry.putInt("SeatIndex", entry.getValue());
            playerList.add(playerEntry);
        }
        tag.put("SeatedPlayers", playerList);
        return tag;
    }

    public static TravellingAirship load(CompoundTag tag, HolderLookup.Provider registries) {
        UUID id = tag.getUUID("Id");
        String name = tag.getString("Name");
        SkyRoute route = SkyRoute.load(tag.getCompound("Route"));
        double speed = tag.getDouble("Speed");
        int lift = tag.getInt("Lift");
        int weight = tag.getInt("Weight");
        int maxCargoSlots = tag.contains("MaxCargoSlots") ? tag.getInt("MaxCargoSlots") : 9;
        TravellingAirship ship = new TravellingAirship(id, name, route, speed, lift, weight, maxCargoSlots);
        ship.blockCount = tag.getInt("BlockCount");
        try {
            ship.state = FlightState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            ship.state = FlightState.DOCKED_AT_A;
        }
        ship.progress = tag.getDouble("Progress");
        ship.speedFactor = tag.getDouble("SpeedFactor");
        if (tag.contains("OccupiedSlots") || tag.contains("CargoEmpty")) {
            ship.occupiedCargoSlots = tag.getInt("OccupiedSlots");
            ship.cachedCargoEmpty = !tag.contains("CargoEmpty") || tag.getBoolean("CargoEmpty");
            ship.cachedCargoFull = tag.getBoolean("CargoFull");
        } else if (tag.contains("Cargo", Tag.TAG_LIST)) {
            ListTag cargoList = tag.getList("Cargo", Tag.TAG_COMPOUND);
            ship.occupiedCargoSlots = cargoList.size();
            ship.cachedCargoEmpty = cargoList.isEmpty();
            ship.cachedCargoFull = cargoList.size() >= ship.maxCargoSlots;
        }
        ship.shipContraptionData = tag.getCompound("Contraption");
        if (tag.hasUUID("SpawnedEntityId")) {
            ship.spawnedEntityId = tag.getUUID("SpawnedEntityId");
        }
        // Basınç sisteminden önceki kayıtlarda motor yoktur (engineCount = 0) → hava harcamadan uçmaya devam eder
        ship.airCapacity = tag.getInt("AirCapacity");
        ship.engineCount = tag.getInt("Engines");
        ship.air = tag.getDouble("Air");
        ship.legAirCost = tag.getDouble("LegAirCost");
        ship.legStartAir = tag.getDouble("LegStartAir");

        ListTag mobList = tag.getList("SeatedMobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < mobList.size(); i++) {
            CompoundTag mobEntry = mobList.getCompound(i);
            if (mobEntry.hasUUID("EntityUuid") && mobEntry.contains("EntityData", Tag.TAG_COMPOUND)) {
                ship.pendingSeatedMobs.add(new SeatedMobData(
                        mobEntry.getInt("SeatIndex"),
                        mobEntry.getUUID("EntityUuid"),
                        mobEntry.getCompound("EntityData")));
            }
        }
        ListTag playerList = tag.getList("SeatedPlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerList.size(); i++) {
            CompoundTag playerEntry = playerList.getCompound(i);
            if (playerEntry.hasUUID("PlayerUuid")) {
                ship.pendingSeatedPlayers.put(playerEntry.getUUID("PlayerUuid"), playerEntry.getInt("SeatIndex"));
            }
        }
        return ship;
    }
}
