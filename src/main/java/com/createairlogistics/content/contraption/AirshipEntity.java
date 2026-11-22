package com.createairlogistics.content.contraption;

import com.createairlogistics.config.ModClientConfig;
import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlock;
import com.createairlogistics.content.navigation.SkyNetworkSavedData;
import com.createairlogistics.content.navigation.TravellingAirship;
import com.createairlogistics.registry.ModEntityTypes;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gerçek Create Contraption Tabanlı 3B Uçan Gemi Varlığı (AirshipEntity extends OrientedContraptionEntity)
 *
 * - Create'in kendi ContraptionCollider, VirtualRenderWorld, ContraptionVisual ve MountedStorageManager
 *   altyapısını %100 doğal olarak kullanır.
 * - Sandıklar, Variller, Kasalar (Item Vault), Gemi Ambarları, Koltuklar (Seats), Kapılar ve Super Glue
 *   ile tam uyumludur.
 * - 144+ FPS pürüzsüz sub-tick konum ve açı interpolasyonu ile sanal hava koridorlarında (Bezier) uçar.
 */
public class AirshipEntity extends OrientedContraptionEntity {

    private static final EntityDataAccessor<Optional<UUID>> DATA_AIRSHIP_ID =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> DATA_SHIP_YAW =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_STEER_YAW =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_STEER_PITCH =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_DOCKED =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_THRUST =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AIR_RATIO =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.FLOAT);

    /** Hedef limana bu mesafeden (blok) itibaren gövde, yanaşma açısına hizalanmaya başlar. */
    private static final double ARRIVAL_ALIGN_DISTANCE = 14.0;
    /** Kalkıştan sonra bu mesafeye (blok) kadar gövde yanaşma açısını korur (dikey kalkış). */
    private static final double DEPARTURE_HOLD_DISTANCE = 5.0;
    /** Gövdenin tick başına en fazla dönebileceği açı (derece). */
    private static final float MAX_TURN_PER_TICK = 4.0f;
    /** Havada süzülürken bile pervanenin dönmeye devam ettiği rölanti itkisi. */
    private static final float IDLE_THRUST = 0.25f;

    private float prevVisualSteerYaw = 0.0f;
    private float visualSteerYaw = 0.0f;
    private float prevVisualSteerPitch = 0.0f;
    private float visualSteerPitch = 0.0f;
    private float prevBladeAngle = 0.0f;
    private float bladeAngle = 0.0f;
    private float visualThrust = 0.0f;
    private float prevVisualAirRatio = 0.0f;
    private float visualAirRatio = 0.0f;
    private boolean airRatioInitialized = false;

    // 144+ FPS Akıcı Uçuş için İstemci Konum İnterpolasyonu (Sub-Tick Lerp)
    private int lerpSteps = 0;
    private double lerpTargetX = 0.0;
    private double lerpTargetY = 0.0;
    private double lerpTargetZ = 0.0;
    private boolean yawInitialized = false;
    private Boolean lastSyncedDockedCargoState = null;

    // İstemci tarafı uçuş sesleri (yalnızca görsel/işitsel, sunucu mantığını etkilemez)
    private Boolean clientLastDocked = null;
    private int propellerSoundTimer = 0;

    /**
     * true ise remove() sırasında contraption → TravellingAirship senkronu atlanır.
     * Yalnızca yetim/kopya varlıklar veya contraption'ı zaten sökülmüş/tüketilmiş varlıklar için işaretlenir.
     */
    private boolean suppressRemovalSync = false;

    public AirshipEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    public static AirshipEntity create(Level level, AirshipContraption contraption, UUID airshipId, Direction initialOrientation, float initialYaw) {
        AirshipEntity entity = new AirshipEntity(ModEntityTypes.AIRSHIP.get(), level);
        entity.setAirshipId(airshipId);
        entity.setContraption(contraption);
        entity.setInitialOrientation(initialOrientation);
        entity.startAtYaw(initialYaw);
        entity.entityData.set(DATA_SHIP_YAW, initialYaw);
        entity.yawInitialized = true;
        return entity;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_AIRSHIP_ID, Optional.empty());
        builder.define(DATA_SHIP_YAW, 0.0f);
        builder.define(DATA_STEER_YAW, 0.0f);
        builder.define(DATA_STEER_PITCH, 0.0f);
        builder.define(DATA_DOCKED, true);
        builder.define(DATA_THRUST, 0.0f);
        builder.define(DATA_AIR_RATIO, 0.0f);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpTargetX = x;
        this.lerpTargetY = y;
        this.lerpTargetZ = z;
        this.lerpSteps = Math.max(steps, 3);
    }

    @Override
    protected void tickContraption() {
        if (this.nonDamageTicks > 0) {
            this.nonDamageTicks--;
        }

        this.prevYaw = this.yaw;
        this.prevPitch = this.pitch;

        if (level().isClientSide()) {
            if (this.lerpSteps > 0) {
                double nx = this.getX() + (this.lerpTargetX - this.getX()) / (double) this.lerpSteps;
                double ny = this.getY() + (this.lerpTargetY - this.getY()) / (double) this.lerpSteps;
                double nz = this.getZ() + (this.lerpTargetZ - this.getZ()) / (double) this.lerpSteps;
                this.lerpSteps--;
                this.setDeltaMovement(nx - this.xo, ny - this.yo, nz - this.zo);
                this.setPos(nx, ny, nz);
            } else {
                this.setDeltaMovement(Vec3.ZERO);
            }

            prevVisualSteerYaw = visualSteerYaw;
            prevVisualSteerPitch = visualSteerPitch;
            prevBladeAngle = bladeAngle;

            float targetYaw = entityData.get(DATA_SHIP_YAW);
            if (!yawInitialized) {
                this.yaw = targetYaw;
                this.prevYaw = targetYaw;
                yawInitialized = true;
            } else {
                // Sunucu açısı zaten yumuşatılmış geliyor; istemci yalnızca paket titremesini süzer
                float diff = Mth.wrapDegrees(targetYaw - this.yaw);
                this.yaw = this.yaw + Mth.clamp(diff * 0.5f, -10.0f, 10.0f);
            }
            this.pitch = 0.0f;

            visualSteerYaw = Mth.lerp(0.18f, visualSteerYaw, entityData.get(DATA_STEER_YAW));
            visualSteerPitch = Mth.lerp(0.18f, visualSteerPitch, entityData.get(DATA_STEER_PITCH));

            float targetAirRatio = entityData.get(DATA_AIR_RATIO);
            if (!airRatioInitialized) {
                prevVisualAirRatio = targetAirRatio;
                visualAirRatio = targetAirRatio;
                airRatioInitialized = true;
            } else {
                prevVisualAirRatio = visualAirRatio;
                visualAirRatio = Mth.lerp(0.2f, visualAirRatio, targetAirRatio);
            }

            // Pervane: kalkışta hızlanır, varışta yavaşlar, yanaşınca yavaşça durur
            float thrustTarget = isDocked() ? 0.0f : Math.max(IDLE_THRUST, entityData.get(DATA_THRUST));
            visualThrust = Mth.lerp(0.06f, visualThrust, thrustTarget);
            if (visualThrust > 0.002f) {
                bladeAngle += 34.0f * visualThrust;
                if (bladeAngle > 36000.0f) {
                    bladeAngle %= 360.0f;
                    prevBladeAngle = bladeAngle;
                }
            }

            tickFlightSounds();
            disableVanillaCampfireActorSmoke();
            tickActors();
            if (FMLEnvironment.dist == Dist.CLIENT) {
                com.createairlogistics.client.particle.AirshipSmokeClientHandler.tickAirshipBlockAnimations(this);
            }
            return;
        }

        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        Optional<UUID> idOpt = getAirshipId();
        if (idOpt.isEmpty()) {
            // Yetim varlık: senkronlanacak bir TravellingAirship yok
            markSuppressRemovalSync();
            discard();
            return;
        }

        SkyNetworkSavedData network = SkyNetworkSavedData.get(serverLevel);
        Optional<TravellingAirship> shipOpt = network.getAirship(idOpt.get());
        if (shipOpt.isEmpty()) {
            markSuppressRemovalSync();
            discard();
            return;
        }

        TravellingAirship ship = shipOpt.get();
        UUID canonicalId = ship.getSpawnedEntityId();
        if (canonicalId == null) {
            ship.setSpawnedEntityId(this.getUUID());
        } else if (!canonicalId.equals(this.getUUID())) {
            Entity canonical = serverLevel.getEntity(canonicalId);
            if (canonical instanceof AirshipEntity && !canonical.isRemoved()) {
                // Kopya varlık: kanonik canlı varlığın verisini eski bir kopyayla ezme
                markSuppressRemovalSync();
                discard();
                return;
            }
            ship.setSpawnedEntityId(this.getUUID());
        }

        syncWithTravellingAirship(serverLevel, ship);
        disableVanillaCampfireActorSmoke();
        tickActors();
    }

    /**
     * Create'in yerleşik CampfireMovementBehaviour'ı dünya uzayında 280 tick yükselen ve balon kubbesini
     * delen CAMPFIRE_COSY_SMOKE / CAMPFIRE_SIGNAL_SMOKE üretir. Bunu devre dışı bırakarak yalnızca
     * contraption-local AirshipDomeSmokeParticle dumanının çalışmasını sağlarız.
     */
    private void disableVanillaCampfireActorSmoke() {
        if (this.contraption == null) {
            return;
        }
        for (var actor : this.contraption.getActors()) {
            if (actor.getLeft() != null
                    && actor.getLeft().state().getBlock() instanceof CampfireBlock
                    && actor.getRight() != null) {
                actor.getRight().disabled = true;
            }
        }
    }

    public void syncWithTravellingAirship(ServerLevel level, TravellingAirship ship) {
        if (this.contraption instanceof AirshipContraption airshipContraption) {
            Direction forward = airshipContraption.getShipForwardFacing();
            if (forward != null && forward.getAxis().isHorizontal() && getInitialOrientation() != forward) {
                setInitialOrientation(forward);
            }
        }

        TravellingAirship.FlightState state = ship.getState();
        boolean docked = (state == TravellingAirship.FlightState.DOCKED_AT_A
                || state == TravellingAirship.FlightState.DOCKED_AT_B);
        entityData.set(DATA_DOCKED, docked);

        float liveAirRatio = ship.getAirCapacity() > 0
                ? Mth.clamp((float) (ship.getAir() / (double) ship.getAirCapacity()), 0.0f, 1.0f)
                : 0.0f;
        entityData.set(DATA_AIR_RATIO, liveAirRatio);

        updateCargoHoldVisualState(docked);

        if (docked) {
            // Yanaşma konumu = rota uç noktası (SkyRoute.dockAnchor) → kalkış/varışta ışınlanma yok
            Vec3 anchor = ship.getCurrentPosition();
            setDeltaMovement(Vec3.ZERO);
            setPos(anchor.x, anchor.y, anchor.z);

            BlockPos dockPos = (state == TravellingAirship.FlightState.DOCKED_AT_A)
                    ? ship.getRoute().getDockA()
                    : ship.getRoute().getDockB();
            Float dockedYaw = computeDockedYaw(level, dockPos);
            if (dockedYaw != null) {
                this.yaw = dockedYaw;
                if (!yawInitialized) {
                    this.prevYaw = dockedYaw;
                    yawInitialized = true;
                }
            }
            this.pitch = 0.0f;
            entityData.set(DATA_SHIP_YAW, this.yaw);
            entityData.set(DATA_STEER_YAW, 0.0f);
            entityData.set(DATA_STEER_PITCH, 0.0f);
            entityData.set(DATA_THRUST, 0.0f);
        } else {
            Vec3 targetPos = ship.getCurrentPosition();
            setDeltaMovement(targetPos.subtract(position()));
            setPos(targetPos.x, targetPos.y, targetPos.z);

            boolean reverse = ship.isReverseLeg();
            Vec3 tangent = ship.getRoute().getTangent(ship.getProgress(), reverse);
            double horizontal = Math.hypot(tangent.x, tangent.z);

            // Gövde yönü: kalkışta yanaşma açısını korur, seyirde rotaya döner, varışta yanaşma açısına hizalanır
            Float desiredYaw = null;
            if (ship.getDistanceToLegEnd() < ARRIVAL_ALIGN_DISTANCE) {
                desiredYaw = computeDockedYaw(level, ship.getDestinationDock());
            }
            if (desiredYaw == null && ship.getDistanceFromLegStart() > DEPARTURE_HOLD_DISTANCE && horizontal > 0.3) {
                desiredYaw = (float) Math.toDegrees(Math.atan2(-tangent.x, tangent.z));
            }

            if (!yawInitialized) {
                float initial = desiredYaw != null ? desiredYaw : entityData.get(DATA_SHIP_YAW);
                this.yaw = initial;
                this.prevYaw = initial;
                yawInitialized = true;
            } else if (desiredYaw != null) {
                float diff = Mth.wrapDegrees(desiredYaw - this.yaw);
                this.yaw = this.yaw + Mth.clamp(diff * 0.15f, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            }
            this.pitch = 0.0f;

            entityData.set(DATA_SHIP_YAW, this.yaw);
            entityData.set(DATA_STEER_YAW, ship.getCurrentSteerYaw());
            entityData.set(DATA_STEER_PITCH, ship.getCurrentSteerPitch());
            entityData.set(DATA_THRUST, (float) ship.getSpeedFactor());
        }

        // Periyodik olarak contraption envanterini/yapısını TravellingAirship ile senkronize tut
        if (this.tickCount % 100 == 0 && this.contraption != null) {
            ship.syncFromContraption(level, this.contraption);
        }
    }

    /**
     * Gemi verilen limana yanaşıkken sahip olacağı gövde açısı (ShipPort limana bakacak şekilde).
     * Liman yüklü değilse veya yoksa null döner.
     */
    private Float computeDockedYaw(ServerLevel level, BlockPos dockPos) {
        if (!level.isLoaded(dockPos)) {
            return null;
        }
        BlockState dockState = level.getBlockState(dockPos);
        if (!dockState.hasProperty(SkyDockBlock.FACING)) {
            return null;
        }
        Direction targetPortFacing = dockState.getValue(SkyDockBlock.FACING).getOpposite();
        float dockedYaw = targetPortFacing.toYRot();
        if (this.contraption instanceof AirshipContraption airshipContraption) {
            float forwardOffset = airshipContraption.getShipForwardFacing().toYRot()
                    - airshipContraption.getInitialPortFacing().toYRot();
            dockedYaw = Mth.wrapDegrees(targetPortFacing.toYRot() + forwardOffset);
        }
        return dockedYaw;
    }

    private void updateCargoHoldVisualState(boolean docked) {
        if (this.contraption == null) {
            return;
        }
        if (lastSyncedDockedCargoState != null && lastSyncedDockedCargoState == docked) {
            return;
        }
        lastSyncedDockedCargoState = docked;

        List<Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo>> updates = new ArrayList<>();
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : this.contraption.getBlocks().entrySet()) {
            StructureTemplate.StructureBlockInfo info = entry.getValue();
            BlockState state = info.state();
            if (state.getBlock() instanceof AirshipCargoHoldBlock
                    && state.hasProperty(AirshipCargoHoldBlock.OPEN)
                    && state.getValue(AirshipCargoHoldBlock.OPEN) != docked) {
                BlockState newState = state.setValue(AirshipCargoHoldBlock.OPEN, docked);
                updates.add(Map.entry(entry.getKey(), new StructureTemplate.StructureBlockInfo(info.pos(), newState, info.nbt())));
            }
        }
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> update : updates) {
            setBlock(update.getKey(), update.getValue());
        }
    }

    /**
     * Kalkışta basınç tahliyesi (buhar tıslaması + bulut), yanaşmada basınç kilitlenmesi,
     * uçuş boyunca itkiyle hızlanan pervane uğultusu. Tamamen istemci tarafıdır.
     */
    private void tickFlightSounds() {
        boolean docked = isDocked();
        AABB box = getBoundingBox();
        double cx = box.getCenter().x;
        double cz = box.getCenter().z;

        if (clientLastDocked == null) {
            // Dünyaya girişte veya oyuncu yaklaştığında yanlış tetiklenmesin
            clientLastDocked = docked;
        } else if (clientLastDocked != docked) {
            clientLastDocked = docked;
            if (!docked) {
                // KALKIŞ: basınçlı hava tahliyesi + halat çözülmesi + alt gövdeden buhar bulutu
                level().playLocalSound(cx, box.minY, cz, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 1.0f, 0.55f, false);
                level().playLocalSound(cx, box.minY, cz, SoundEvents.LEASH_KNOT_BREAK, SoundSource.NEUTRAL, 0.9f, 0.7f, false);
                level().playLocalSound(cx, box.minY, cz, SoundEvents.WOODEN_TRAPDOOR_OPEN, SoundSource.NEUTRAL, 0.6f, 0.6f, false);
                for (int i = 0; i < 40; i++) {
                    if (!ModClientConfig.shouldSpawnFlightParticle(random)) {
                        continue;
                    }
                    double px = box.minX + random.nextDouble() * (box.maxX - box.minX);
                    double pz = box.minZ + random.nextDouble() * (box.maxZ - box.minZ);
                    level().addParticle(net.minecraft.core.particles.ParticleTypes.CLOUD,
                            px, box.minY + 0.1, pz,
                            (random.nextDouble() - 0.5) * 0.15, -0.05, (random.nextDouble() - 0.5) * 0.15);
                }
            } else {
                // YANAŞMA: kilitlenme tıkırtısı + kısa basınç tıslaması
                level().playLocalSound(cx, box.minY, cz, SoundEvents.CHAIN_PLACE, SoundSource.NEUTRAL, 1.0f, 0.8f, false);
                level().playLocalSound(cx, box.minY, cz, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.5f, 1.2f, false);
            }
        }

        // UÇUŞ: itkiye göre sıklaşan ve tizleşen pervane uğultusu
        if (!docked && visualThrust > 0.05f) {
            if (--propellerSoundTimer <= 0) {
                propellerSoundTimer = Math.max(4, Math.round(12 - 8 * visualThrust));
                level().playLocalSound(getX(), getY(), getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.NEUTRAL,
                        0.25f + 0.35f * visualThrust, 0.45f + 0.35f * visualThrust, false);
            }
        } else {
            propellerSoundTimer = 0;
        }
    }

    @Override
    public boolean handlePlayerInteraction(Player player, BlockPos localPos, Direction side, InteractionHand interactionHand) {
        return super.handlePlayerInteraction(player, localPos, side, interactionHand);
    }

    @Override
    public void disassemble() {
        if (!level().isClientSide() && level() instanceof ServerLevel serverLevel) {
            Optional<UUID> idOpt = getAirshipId();
            if (idOpt.isPresent()) {
                SkyNetworkSavedData network = SkyNetworkSavedData.get(serverLevel);
                if (network.getAirship(idOpt.get()).isPresent()) {
                    if (network.disassembleAirship(serverLevel, idOpt.get(), null)) {
                        markSuppressRemovalSync();
                    }
                    discard();
                    return;
                }
            }
        }
        super.disassemble();
    }

    public void onDisassembled(StructureTransform transform) {
        if (this.contraption != null) {
            this.contraption.addPassengersToWorld(this.level(), transform, this.getPassengers());
        }
        this.ejectPassengers();
    }

    public boolean occupiesWorldBlock(BlockPos worldPos) {
        if (this.contraption == null || this.contraption.getBlocks().isEmpty()) {
            return false;
        }
        if (!getBoundingBox().inflate(0.75).intersects(new AABB(worldPos))) {
            return false;
        }
        Vec3 localVec = toLocalVector(Vec3.atCenterOf(worldPos), 1.0f);
        BlockPos localBlockPos = BlockPos.containing(localVec);
        return this.contraption.getBlocks().containsKey(localBlockPos);
    }

    public boolean isHitByExplosion(Vec3 explosionCenter, float radius, List<BlockPos> affectedBlocks) {
        if (this.contraption == null || this.contraption.getBlocks().isEmpty()) {
            return false;
        }
        if (!getBoundingBox().inflate(radius + 2.0).contains(explosionCenter)) {
            return false;
        }
        Vec3 localCenter = toLocalVector(explosionCenter, 1.0f);
        double maxDistSq = (radius + 1.35) * (radius + 1.35);
        for (BlockPos localPos : this.contraption.getBlocks().keySet()) {
            if (localPos.distToCenterSqr(localCenter) <= maxDistSq) {
                return true;
            }
        }
        for (BlockPos affected : affectedBlocks) {
            if (occupiesWorldBlock(affected)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION) && level() instanceof ServerLevel serverLevel) {
            getAirshipId().ifPresent(id -> SkyNetworkSavedData.get(serverLevel).disassembleAirship(serverLevel, id, null));
            // Contraption dünyaya söküldü: kaldırılırken eski veriyi geri senkronlama
            markSuppressRemovalSync();
            discard();
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** Bu varlığın kaldırılması sırasında TravellingAirship senkronunu devre dışı bırakır. */
    public void markSuppressRemovalSync() {
        this.suppressRemovalSync = true;
    }

    /** Koltuklarda (hâlâ dünyada olan) en az bir oyuncu oturuyor mu? */
    public boolean hasPlayerPassenger() {
        for (Entity passenger : getPassengers()) {
            if (passenger instanceof Player p && !p.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sıfır-pencere senkronu (C-2/C-3): bastırılmamış HER kaldırmada (DISCARDED, UNLOADED_TO_CHUNK,
     * UNLOADED_WITH_PLAYER, CHANGED_DIMENSION...) kanonik varlık, contraption depolarını ve koltuk yolcularını
     * kaldırılmadan hemen önce TravellingAirship'e yazar. Böylece son periyodik senkrondan bu yana
     * yapılan envanter değişiklikleri asla kaybolmaz.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (!suppressRemovalSync && !isRemoved() && !level().isClientSide()
                && level() instanceof ServerLevel serverLevel && this.contraption != null) {
            Optional<UUID> idOpt = getAirshipId();
            if (idOpt.isPresent()) {
                SkyNetworkSavedData.get(serverLevel).getAirship(idOpt.get()).ifPresent(ship -> {
                    UUID canonical = ship.getSpawnedEntityId();
                    if (canonical == null || canonical.equals(this.getUUID())) {
                        ship.capturePassengersBeforeEntityUnload(this);
                        ship.syncFromContraption(serverLevel, this.contraption);
                    }
                });
            }
        }
        super.remove(reason);
    }

    @Override
    public void kill() {
        if (level() instanceof ServerLevel serverLevel) {
            getAirshipId().ifPresent(id -> SkyNetworkSavedData.get(serverLevel).disassembleAirship(serverLevel, id, null));
            markSuppressRemovalSync();
        }
        super.kill();
    }

    public Optional<UUID> getAirshipId() {
        return entityData.get(DATA_AIRSHIP_ID);
    }

    public void setAirshipId(UUID id) {
        entityData.set(DATA_AIRSHIP_ID, Optional.of(id));
    }

    public boolean isDocked() {
        return entityData.get(DATA_DOCKED);
    }

    public float getVisualSteerYaw(float partialTicks) {
        return Mth.lerp(partialTicks, prevVisualSteerYaw, visualSteerYaw);
    }

    public float getVisualSteerPitch(float partialTicks) {
        return Mth.lerp(partialTicks, prevVisualSteerPitch, visualSteerPitch);
    }

    public float getBladeAngle(float partialTicks) {
        return Mth.lerp(partialTicks, prevBladeAngle, bladeAngle);
    }

    public float getVisualAirRatio(float partialTicks) {
        if (!airRatioInitialized) {
            return entityData.get(DATA_AIR_RATIO);
        }
        return Mth.lerp(partialTicks, prevVisualAirRatio, visualAirRatio);
    }

    @Override
    protected void readAdditional(CompoundTag compound, boolean spawnPacket) {
        super.readAdditional(compound, spawnPacket);
        if (compound.hasUUID("AirshipId")) {
            setAirshipId(compound.getUUID("AirshipId"));
        }
        if (compound.contains("ShipYaw")) {
            float savedYaw = compound.getFloat("ShipYaw");
            entityData.set(DATA_SHIP_YAW, savedYaw);
            this.yaw = savedYaw;
            this.prevYaw = savedYaw;
            this.yawInitialized = true;
        }
    }

    @Override
    protected void writeAdditional(CompoundTag compound, HolderLookup.Provider registries, boolean spawnPacket) {
        super.writeAdditional(compound, registries, spawnPacket);
        getAirshipId().ifPresent(id -> compound.putUUID("AirshipId", id));
        compound.putFloat("ShipYaw", entityData.get(DATA_SHIP_YAW));
    }
}
