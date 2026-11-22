package com.createairlogistics.client.particle;

import com.createairlogistics.content.contraption.AirshipEntity;
import com.createairlogistics.content.contraption.balloon.CanvasBalloonBlock;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;

/**
 * Hava gemisinin kendi yerel (contraption-local) koordinat sisteminde hareket eden,
 * balon kubbesini ve gemi tavanını asla delmeyen sıcak hava / kamp ateşi duman parçacığı.
 */
public class AirshipDomeSmokeParticle extends TextureSheetParticle {

    private static final double CEILING_CUSHION = 0.28;
    private static final double SIDE_CUSHION = 0.25;
    private static final int CEILING_FADE_TICKS = 18;
    private static final int END_OF_LIFE_FADE_TICKS = 20;
    private static final int CHIMNEY_SCAN_HEIGHT = 10;

    public enum SmokeProfile {
        COSY_CAMPFIRE,
        SIGNAL_CAMPFIRE,
        SUPER_HEATER
    }

    private final WeakReference<AirshipEntity> shipRef;
    private final BlockPos localSourcePos;
    private final boolean enclosedChimney;
    private final boolean isSuperHeater;
    private final float baseAlpha;
    private final float maxQuadSize;
    private final double maxLvy;
    private final BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();

    private double localX;
    private double localY;
    private double localZ;
    private double lvx;
    private double lvy;
    private double lvz;
    private boolean hitCeiling = false;
    private int ceilingTicks = 0;

    public AirshipDomeSmokeParticle(
            ClientLevel level,
            AirshipEntity entity,
            BlockPos localSourcePos,
            double localX,
            double localY,
            double localZ,
            SmokeProfile profile,
            SpriteSet sprites
    ) {
        super(level, 0.0, 0.0, 0.0);
        this.shipRef = new WeakReference<>(entity);
        this.localSourcePos = localSourcePos.immutable();
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
        this.hasPhysics = false;
        this.setSize(0.25f, 0.25f);

        Contraption contraption = entity.getContraption();
        this.enclosedChimney = contraption != null && hasCeilingAbove(level, contraption, this.localSourcePos);
        this.isSuperHeater = (profile == SmokeProfile.SUPER_HEATER);

        this.lvx = (this.random.nextDouble() - 0.5) * 0.008;
        this.lvy = 0.045 + this.random.nextDouble() * 0.015;
        this.lvz = (this.random.nextDouble() - 0.5) * 0.008;

        switch (profile) {
            case SIGNAL_CAMPFIRE -> {
                this.quadSize = 0.31f + this.random.nextFloat() * 0.09f;
                this.maxQuadSize = 0.40f;
                this.baseAlpha = 0.92f;
                this.maxLvy = 0.085;
                this.setColor(1.0f, 1.0f, 1.0f);
                this.lifetime = this.enclosedChimney
                        ? (80 + this.random.nextInt(31))
                        : (140 + this.random.nextInt(45));
            }
            case SUPER_HEATER -> {
                this.quadSize = 0.34f + this.random.nextFloat() * 0.11f;
                this.maxQuadSize = 0.50f;
                this.baseAlpha = 0.88f;
                this.maxLvy = 0.122;
                int tone = this.random.nextInt(3);
                float baseR;
                float baseG;
                float baseB;
                if (tone == 0) {
                    // Koyu kömür isi
                    baseR = 0.26f;
                    baseG = 0.21f;
                    baseB = 0.20f;
                } else if (tone == 1) {
                    // Sıcak bakır-kahve
                    baseR = 0.58f;
                    baseG = 0.41f;
                    baseB = 0.32f;
                } else {
                    // Yoğun sıcak kül grisi
                    baseR = 0.48f;
                    baseG = 0.43f;
                    baseB = 0.40f;
                }
                float jitter = (this.random.nextFloat() - 0.5f) * 0.06f;
                this.setColor(
                        Mth.clamp(baseR + jitter, 0.0f, 1.0f),
                        Mth.clamp(baseG + jitter, 0.0f, 1.0f),
                        Mth.clamp(baseB + jitter, 0.0f, 1.0f)
                );
                this.lvx = (this.random.nextDouble() - 0.5) * 0.011;
                this.lvy = 0.088 + this.random.nextDouble() * 0.030;
                this.lvz = (this.random.nextDouble() - 0.5) * 0.011;
                this.lifetime = this.enclosedChimney
                        ? (85 + this.random.nextInt(41))
                        : (115 + this.random.nextInt(41));
            }
            case COSY_CAMPFIRE -> {
                this.quadSize = 0.28f + this.random.nextFloat() * 0.08f;
                this.maxQuadSize = 0.40f;
                this.baseAlpha = 0.85f;
                this.maxLvy = 0.085;
                this.setColor(1.0f, 1.0f, 1.0f);
                this.lifetime = this.enclosedChimney
                        ? (70 + this.random.nextInt(41))
                        : (95 + this.random.nextInt(36));
            }
            default -> {
                this.quadSize = 0.30f;
                this.maxQuadSize = 0.40f;
                this.baseAlpha = 0.85f;
                this.maxLvy = 0.085;
                this.setColor(1.0f, 1.0f, 1.0f);
                this.lifetime = 85;
            }
        }

        this.setAlpha(this.baseAlpha);
        this.pickSprite(sprites);

        Vec3 localVec = new Vec3(this.localX, this.localY, this.localZ);
        Vec3 worldPos = entity.toGlobalVector(localVec, 1.0f);
        this.setPos(worldPos.x, worldPos.y, worldPos.z);
        if (!entity.isPrevPosInvalid()) {
            Vec3 prevWorldPos = entity.toGlobalVector(localVec, 0.0f, true);
            this.xo = prevWorldPos.x;
            this.yo = prevWorldPos.y;
            this.zo = prevWorldPos.z;
        } else {
            this.xo = worldPos.x;
            this.yo = worldPos.y;
            this.zo = worldPos.z;
        }
    }

    /**
     * Yalnızca komutla (/particle) doğrudan oluşturulduğunda kullanılan yedek dünya uzayı kurucusu.
     */
    protected AirshipDomeSmokeParticle(
            ClientLevel level,
            double x, double y, double z,
            double vx, double vy, double vz,
            SpriteSet sprites
    ) {
        super(level, x, y, z);
        this.shipRef = new WeakReference<>(null);
        this.localSourcePos = BlockPos.containing(x, y, z);
        this.enclosedChimney = false;
        this.isSuperHeater = false;
        this.quadSize = 0.32f;
        this.maxQuadSize = 0.40f;
        this.baseAlpha = 0.85f;
        this.maxLvy = 0.085;
        this.setAlpha(this.baseAlpha);
        this.setSize(0.25f, 0.25f);
        this.lifetime = 75 + this.random.nextInt(25);
        this.xd = vx;
        this.yd = vy + 0.04;
        this.zd = vz;
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (++this.age >= this.lifetime) {
            this.remove();
            return;
        }

        AirshipEntity entity = this.shipRef.get();
        if (entity == null || entity.isRemoved() || entity.getContraption() == null) {
            this.move(0.0, 0.04, 0.0);
            this.alpha *= 0.85f;
            if (this.alpha < 0.03f) {
                this.remove();
            }
            return;
        }

        Contraption contraption = entity.getContraption();
        if (isSmokeBlocked(this.level, contraption, this.localX, this.localY, this.localZ, this.probePos)) {
            this.remove();
            return;
        }

        // Dikey adım ve balon/tavan tampon kontrolü (0.28 blok güvenlik yastığı)
        double nextY = this.localY + this.lvy;
        double ceilingProbeY = Math.max(nextY, this.localY + 0.03) + CEILING_CUSHION;
        if (isSmokeBlocked(this.level, contraption, this.localX, ceilingProbeY, this.localZ, this.probePos)) {
            this.hitCeiling = true;
            this.lvy = 0.0;
            this.lvx = Mth.clamp(this.lvx + (this.random.nextFloat() - 0.5f) * 0.014f, -0.045, 0.045);
            this.lvz = Mth.clamp(this.lvz + (this.random.nextFloat() - 0.5f) * 0.014f, -0.045, 0.045);
            this.quadSize = Math.min(this.maxQuadSize, this.quadSize + 0.003f);
            this.ceilingTicks++;
        } else {
            this.localY = nextY;
            this.lvy = Mth.clamp(this.lvy + 0.0015, 0.04, this.maxLvy);
            this.lvx = (this.lvx + (this.random.nextFloat() - 0.5f) * 0.003f) * 0.92;
            this.lvz = (this.lvz + (this.random.nextFloat() - 0.5f) * 0.003f) * 0.92;
            this.quadSize = Math.min(this.maxQuadSize, this.quadSize + 0.0016f);
            if (this.hitCeiling) {
                this.ceilingTicks++;
            }
        }

        // Açık güverte kamp ateşleri: üstünde balon/tavan yoksa rüzgarda geriye doğru süzülür
        if (!this.enclosedChimney && this.localY > this.localSourcePos.getY() + 1.5) {
            Vec3 shipWorldVel = entity.getDeltaMovement();
            if (shipWorldVel.lengthSqr() > 1.0e-5) {
                Vec3 localShipVel = entity.reverseRotation(shipWorldVel, 1.0f);
                this.lvx = Mth.clamp(this.lvx - localShipVel.x * 0.18, -0.35, 0.35);
                this.lvz = Mth.clamp(this.lvz - localShipVel.z * 0.18, -0.35, 0.35);
            }
        }

        // Yatay X/Z adımları: yan duvarlardan ve kavisli balon eteklerinden taşmayı önler
        double nextX = this.localX + this.lvx;
        double probeX = nextX + Math.signum(this.lvx) * SIDE_CUSHION;
        if (!isSmokeBlocked(this.level, contraption, probeX, this.localY, this.localZ, this.probePos)
                && !isSmokeBlocked(this.level, contraption, probeX, this.localY + CEILING_CUSHION * 0.85, this.localZ, this.probePos)) {
            this.localX = nextX;
        } else {
            this.lvx = -this.lvx * 0.3;
        }

        double nextZ = this.localZ + this.lvz;
        double probeZ = nextZ + Math.signum(this.lvz) * SIDE_CUSHION;
        if (!isSmokeBlocked(this.level, contraption, this.localX, this.localY, probeZ, this.probePos)
                && !isSmokeBlocked(this.level, contraption, this.localX, this.localY + CEILING_CUSHION * 0.85, probeZ, this.probePos)) {
            this.localZ = nextZ;
        } else {
            this.lvz = -this.lvz * 0.3;
        }

        // Tavan sönümlemesi (~18 tick) ve ömür sonu yumuşak sönümleme
        float ageFade = 1.0f;
        int remaining = this.lifetime - this.age;
        if (remaining < END_OF_LIFE_FADE_TICKS) {
            ageFade = remaining / (float) END_OF_LIFE_FADE_TICKS;
        }
        float ceilingFade = this.hitCeiling
                ? Math.max(0.0f, 1.0f - (this.ceilingTicks / (float) CEILING_FADE_TICKS))
                : 1.0f;

        this.alpha = this.baseAlpha * Math.min(ageFade, ceilingFade);
        if (this.alpha < 0.03f) {
            this.remove();
            return;
        }

        Vec3 worldPos = entity.toGlobalVector(new Vec3(this.localX, this.localY, this.localZ), 1.0f);
        this.setPos(worldPos.x, worldPos.y, worldPos.z);
    }

    @Override
    protected int getLightColor(float partialTick) {
        int worldLight = super.getLightColor(partialTick);
        double distFromFire = Math.max(0.0, this.localY - (this.localSourcePos.getY() + 0.45));
        if (distFromFire < 3.5) {
            double peakLight = this.isSuperHeater ? 14.0 : 11.0;
            int minBlockLight = Mth.clamp((int) Math.round(peakLight * (1.0 - distFromFire / 3.5)), 0, 15);
            int currentBlock = LightTexture.block(worldLight);
            if (minBlockLight > currentBlock) {
                return LightTexture.pack(minBlockLight, LightTexture.sky(worldLight));
            }
        }
        return worldLight;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    private static boolean isSmokeBlocked(Level level, Contraption contraption,
                                          double lx, double ly, double lz,
                                          BlockPos.MutableBlockPos scratch) {
        scratch.set(Mth.floor(lx), Mth.floor(ly), Mth.floor(lz));
        StructureTemplate.StructureBlockInfo info = contraption.getBlocks().get(scratch);
        if (info == null) {
            return false;
        }
        BlockState state = info.state();
        if (CanvasBalloonBlock.isBalloonBlock(state)) {
            return true;
        }
        return !CanvasBalloonBlock.allowsHotAirPassThrough(level, BlockPos.ZERO, state);
    }

    public static boolean isSmokeBlocked(Level level, Contraption contraption, double lx, double ly, double lz) {
        StructureTemplate.StructureBlockInfo info = contraption.getBlocks().get(BlockPos.containing(lx, ly, lz));
        if (info == null) {
            return false;
        }
        BlockState state = info.state();
        if (CanvasBalloonBlock.isBalloonBlock(state)) {
            return true;
        }
        if (CanvasBalloonBlock.allowsHotAirPassThrough(level, BlockPos.ZERO, state)) {
            return false;
        }
        return true;
    }

    public static boolean hasCeilingAbove(Level level, Contraption contraption, BlockPos localSourcePos) {
        double cx = localSourcePos.getX() + 0.5;
        double cz = localSourcePos.getZ() + 0.5;
        for (int dy = 1; dy <= CHIMNEY_SCAN_HEIGHT; dy++) {
            if (isSmokeBlocked(level, contraption, cx, localSourcePos.getY() + dy + 0.5, cz)) {
                return true;
            }
        }
        return false;
    }

    public static class FallbackProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public FallbackProvider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientLevel level,
                double x, double y, double z,
                double xSpeed, double ySpeed, double zSpeed
        ) {
            return new AirshipDomeSmokeParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
