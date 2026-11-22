package com.createairlogistics.content.contraption.balloon;

import com.createairlogistics.config.ModClientConfig;
import com.createairlogistics.registry.ModBlockEntities;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Hava Gemisi Süper Isıtıcısı (Airship Super Heater):
 *
 * Ağır kargo gemileri ve geniş balon kubbeleri için yakıt gerektirmeyen endüstriyel sıcak hava ocağıdır.
 * - Balon başına +8 Kaldırma Kuvveti (Lift) üretir ve tek başına 32 balona kadar ısıtabilir.
 * - Alt ve yan gövdesi ile üst baca ağzı Super Glue ile gemi güvertesine yapıştırılmaya uygundur.
 * - Gemi kurulu değilken (dünya bloğu halindeyken) Create Anahtarı (Wrench) ile koruyucu bariyer kapağı açılıp kapatılabilir.
 */
public class AirshipSuperHeaterBlock extends Block implements IBE<AirshipSuperHeaterBlockEntity>, IWrenchable {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(0, 0, 0, 16, 14, 16),
            Block.box(2, 14, 2, 14, 16, 14)
    );

    public AirshipSuperHeaterBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(OPEN, false);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !context.getLevel().mayInteract(player, context.getClickedPos()))) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        boolean nextOpen = !state.getValue(OPEN);
        Direction nextFacing = state.getValue(FACING);
        if (nextOpen) {
            if (context.getClickedFace().getAxis().isHorizontal()) {
                nextFacing = context.getClickedFace();
            } else if (player != null) {
                nextFacing = player.getDirection().getOpposite();
            }
        }

        level.setBlock(context.getClickedPos(), state.setValue(OPEN, nextOpen).setValue(FACING, nextFacing), Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, context.getClickedPos());
        level.playSound(
                null,
                context.getClickedPos(),
                nextOpen ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE,
                SoundSource.BLOCKS,
                0.5F,
                1.15F
        );
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !context.getLevel().mayInteract(player, context.getClickedPos()))) {
            return InteractionResult.PASS;
        }
        return IWrenchable.super.onSneakWrenched(state, context);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /**
     * Baca ağzından yükselen yoğun sıcak duman, kor kıvılcımları ve ızgara penceresi alevlerini oluşturur.
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY();
        double cz = pos.getZ() + 0.5;

        if (random.nextInt(16) == 0) {
            SoundEvent crackle = random.nextBoolean()
                    ? SoundEvents.BLASTFURNACE_FIRE_CRACKLE
                    : SoundEvents.CAMPFIRE_CRACKLE;
            level.playLocalSound(
                    cx,
                    cy + 0.5,
                    cz,
                    crackle,
                    SoundSource.BLOCKS,
                    0.45F + random.nextFloat() * 0.2F,
                    0.85F + random.nextFloat() * 0.3F,
                    false
            );
        }

        // Üst Chute baca ağzından yükselen yoğun duman sütunu
        int cosyCount = 1 + random.nextInt(2);
        for (int i = 0; i < cosyCount; i++) {
            if (!ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                continue;
            }
            double sx = cx + (random.nextDouble() - 0.5) * 0.28;
            double sy = cy + 0.96;
            double sz = cz + (random.nextDouble() - 0.5) * 0.28;
            double vx = (random.nextDouble() - 0.5) * 0.008;
            double vy = 0.065 + random.nextDouble() * 0.025;
            double vz = (random.nextDouble() - 0.5) * 0.008;
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, sx, sy, sz, vx, vy, vz);
        }

        if (random.nextInt(4) == 0 && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
            double sx = cx + (random.nextDouble() - 0.5) * 0.22;
            double sy = cy + 0.96;
            double sz = cz + (random.nextDouble() - 0.5) * 0.22;
            double vy = 0.07 + random.nextDouble() * 0.025;
            level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, sx, sy, sz, 0.0, vy, 0.0);
        }

        // Baca ağzı ve yan ateş ızgarası çevresinde parlayan alevler
        if (random.nextInt(2) == 0) {
            int flameCount = 1 + random.nextInt(2);
            for (int i = 0; i < flameCount; i++) {
                if (!ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                    continue;
                }
                if (i == 0 || random.nextBoolean()) {
                    double fx = cx + (random.nextDouble() - 0.5) * 0.30;
                    double fy = cy + 0.95;
                    double fz = cz + (random.nextDouble() - 0.5) * 0.30;
                    level.addParticle(ParticleTypes.FLAME, fx, fy, fz, 0.0, 0.018 + random.nextDouble() * 0.02, 0.0);
                } else {
                    boolean alongX = random.nextBoolean();
                    double side = random.nextBoolean() ? 0.46 : -0.46;
                    double spread = (random.nextDouble() - 0.5) * 0.36;
                    double fx = cx + (alongX ? side : spread);
                    double fy = cy + 0.38 + random.nextDouble() * 0.28;
                    double fz = cz + (alongX ? spread : side);
                    level.addParticle(ParticleTypes.FLAME, fx, fy, fz, 0.0, 0.008, 0.0);
                }
            }
        }

        int smallFlameCount = 1 + random.nextInt(2);
        for (int i = 0; i < smallFlameCount; i++) {
            if (!ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
                continue;
            }
            if (i == 0) {
                double sfx = cx + (random.nextDouble() - 0.5) * 0.32;
                double sfy = cy + 0.96;
                double sfz = cz + (random.nextDouble() - 0.5) * 0.32;
                level.addParticle(ParticleTypes.SMALL_FLAME, sfx, sfy, sfz, 0.0, 0.02 + random.nextDouble() * 0.025, 0.0);
            } else {
                boolean alongX = random.nextBoolean();
                double side = random.nextBoolean() ? 0.48 : -0.48;
                double spread = (random.nextDouble() - 0.5) * 0.40;
                double sfx = cx + (alongX ? side : spread);
                double sfy = cy + 0.36 + random.nextDouble() * 0.30;
                double sfz = cz + (alongX ? spread : side);
                level.addParticle(ParticleTypes.SMALL_FLAME, sfx, sfy, sfz, 0.0, 0.012, 0.0);
            }
        }

        // Baca boğazından sıçrayan kor kıvılcımları
        if (random.nextInt(3) == 0 && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
            double lx = cx + (random.nextDouble() - 0.5) * 0.24;
            double ly = cy + 0.95;
            double lz = cz + (random.nextDouble() - 0.5) * 0.24;
            level.addParticle(ParticleTypes.LAVA, lx, ly, lz, 0.0, 0.02, 0.0);
        }

        // Bariyer kapağı açıkken (OPEN=true) ön penceredeki Blaze çevresinde hafif alev titreşimi
        if (state.getValue(OPEN) && ModClientConfig.shouldSpawnSuperHeaterParticle(random)) {
            Direction facing = state.getValue(FACING);
            Direction lateralDir = facing.getClockWise();
            double forward = 0.38 + random.nextDouble() * 0.10;
            double lateral = (random.nextDouble() - 0.5) * 0.34;
            double wx = cx + facing.getStepX() * forward + lateralDir.getStepX() * lateral;
            double wy = cy + 0.34 + random.nextDouble() * 0.30;
            double wz = cz + facing.getStepZ() * forward + lateralDir.getStepZ() * lateral;
            level.addParticle(ParticleTypes.SMALL_FLAME, wx, wy, wz, 0.0, 0.01 + random.nextDouble() * 0.012, 0.0);
        }
    }

    @Override
    public Class<AirshipSuperHeaterBlockEntity> getBlockEntityClass() {
        return AirshipSuperHeaterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AirshipSuperHeaterBlockEntity> getBlockEntityType() {
        return ModBlockEntities.AIRSHIP_SUPER_HEATER.get();
    }
}
