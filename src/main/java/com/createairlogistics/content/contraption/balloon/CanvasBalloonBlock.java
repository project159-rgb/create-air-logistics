package com.createairlogistics.content.contraption.balloon;

import com.createairlogistics.config.ModServerConfig;
import com.createairlogistics.registry.ModBlocks;
import com.createairlogistics.registry.ModTags;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Airship Balonu (Canvas Balloon):
 *
 * Gerçekçi Sıcak Hava & Kubbe Fizik Kuralları:
 * 1. Isı Olmadan Kaldırma Kuvveti = 0 (Isıtılmayan bez balon uçamaz).
 * 2. Dikey Sıcak Hava Bacası + Kubbe Yayılımı (Flood-Fill):
 *    - Bir ısı kaynağı (Kamp Ateşi / Hava Gemisi Süper Isıtıcısı) yalnızca tam üstündeki dikey hava koridorundan
 *      (1..8 blok yukarıya doğru, arada katı engel olmadan) ulaştığı balon kümesini ısıtabilir.
 *    - Sıcak hava ilk balona ulaştığında, o balona birbirine değerek bağlanan (bitişik kubbe)
 *      diğer balon bloklarına yayılır:
 *      * Yanık Kamp Ateşi / Ruh Kamp Ateşi: Balon başına +4 Lift (Maks 16 balon/ocak)
 *      * Hava Gemisi Süper Isıtıcısı (Airship Super Heater): Balon başına +8 Lift (Maks 32 balon/ocak, yakıtsız)
 * 3. Kubbe Geometri Kuralı (Zeminden Balon Engeli):
 *    - Üstünde balon olmayan katı bir gövde bloğu bulunan balon "ezilmiş" sayılır ve 0 Lift verir.
 *    - Balonların ortalama yüksekliği (Y), gemi gövdesinin ortalama yüksekliğinin altındaysa kurulum reddedilir.
 */
public class CanvasBalloonBlock extends Block implements IWrenchable {

    public static final int LIFT_UNHEATED = 0;
    public static final int LIFT_CAMPFIRE = 4;
    public static final int LIFT_SUPER_HEATER = 8;

    public static final int MAX_VERTICAL_PLUME_DISTANCE = 8;

    private static final int[][] BFS_OFFSETS = {
            // 6 face neighbors (manhattan == 1)
            { 1,  0,  0},
            {-1,  0,  0},
            { 0,  1,  0},
            { 0, -1,  0},
            { 0,  0,  1},
            { 0,  0, -1},
            // 12 edge-diagonal neighbors (manhattan == 2)
            { 1,  1,  0},
            { 1, -1,  0},
            {-1,  1,  0},
            {-1, -1,  0},
            { 1,  0,  1},
            { 1,  0, -1},
            {-1,  0,  1},
            {-1,  0, -1},
            { 0,  1,  1},
            { 0,  1, -1},
            { 0, -1,  1},
            { 0, -1, -1}
    };

    private static final int[][] PLUME_RING_OFFSETS = {
            // 4 cardinal neighbors
            { 1,  0},
            {-1,  0},
            { 0,  1},
            { 0, -1},
            // 4 corner neighbors
            { 1,  1},
            { 1, -1},
            {-1,  1},
            {-1, -1}
    };

    public record BalloonLiftAnalysis(
            int totalLift,
            int balloonCount,
            int heatedBalloonCount,
            boolean hasActiveHeatSource,
            boolean balloonsProperlyPlaced
    ) {
    }

    private final DyeColor color;

    public CanvasBalloonBlock(Properties properties) {
        this(properties, DyeColor.WHITE);
    }

    public CanvasBalloonBlock(Properties properties, DyeColor color) {
        super(properties);
        this.color = color;
    }

    public DyeColor getColor() {
        return color;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && (player.isSpectator() || !player.mayBuild() || !context.getLevel().mayInteract(player, context.getClickedPos()))) {
            return InteractionResult.PASS;
        }
        return IWrenchable.super.onWrenched(state, context);
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
    protected ItemInteractionResult useItemOn(
            ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult
    ) {
        if (!player.isShiftKeyDown() && stack.getItem() instanceof DyeItem dyeItem) {
            DyeColor targetColor = dyeItem.getDyeColor();
            if (targetColor != this.color) {
                if (!level.isClientSide) {
                    level.setBlock(pos, ModBlocks.getBalloon(targetColor).getDefaultState(), Block.UPDATE_ALL);
                    level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
                    if (!player.isCreative()) {
                        stack.shrink(1);
                    }
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    /**
     * Gemideki tüm balonları, ısı kaynaklarını, dikey sıcak hava akışını ve kubbe geometrisini analiz eder.
     */
    public static BalloonLiftAnalysis analyzeShipBalloons(Level level, Set<BlockPos> shipBlocks) {
        Set<BlockPos> allBalloons = new HashSet<>();
        Set<BlockPos> validBalloons = new HashSet<>();
        List<BlockPos> heatSources = new ArrayList<>();

        double sumBalloonY = 0.0;
        int balloonCount = 0;
        double sumHullY = 0.0;
        int hullCount = 0;
        int crushedBalloonCount = 0;

        for (BlockPos pos : shipBlocks) {
            BlockState state = level.getBlockState(pos);
            if (isBalloonBlock(state)) {
                allBalloons.add(pos);
                balloonCount++;
                sumBalloonY += pos.getY();

                // Balonun tam üstünde balon olmayan başka bir gemi bloğu varsa balon üstten kapanmış/ezilmiştir
                BlockPos abovePos = pos.above();
                if (shipBlocks.contains(abovePos)) {
                    BlockState aboveState = level.getBlockState(abovePos);
                    if (!isBalloonBlock(aboveState) && !allowsHotAirPassThrough(level, abovePos, aboveState)) {
                        crushedBalloonCount++;
                        continue;
                    }
                }
                validBalloons.add(pos);
            } else {
                if (isActiveHeatSource(state)) {
                    heatSources.add(pos);
                }
                hullCount++;
                sumHullY += pos.getY();
            }
        }

        if (balloonCount == 0) {
            return new BalloonLiftAnalysis(0, 0, 0, !heatSources.isEmpty(), true);
        }

        if (heatSources.isEmpty()) {
            double avgAllBalloonY = sumBalloonY / balloonCount;
            double avgHullY = hullCount > 0 ? (sumHullY / hullCount) : (avgAllBalloonY - 1.0);

            // Balonlar geminin tabanına/zemine döşenmişse (ortalama balon yüksekliği gövdeden aşağıdaysa)
            // veya balonların yarısından fazlasının üstüne gövde inşa edilmişse geometri geçersizdir!
            boolean balloonsProperlyPlaced = (avgAllBalloonY >= avgHullY - 0.15)
                    && (crushedBalloonCount * 2 < balloonCount);

            return new BalloonLiftAnalysis(0, balloonCount, 0, false, balloonsProperlyPlaced);
        }

        // Her geçerli balonun aldığı en yüksek kaldırma kuvvetini kaydet
        Map<BlockPos, Integer> balloonLiftMap = new HashMap<>();

        Comparator<BlockPos> heatComparator = (a, b) -> {
            BlockState sa = level.getBlockState(a);
            BlockState sb = level.getBlockState(b);
            int cmp = Integer.compare(getLiftPerBalloonForHeat(sb), getLiftPerBalloonForHeat(sa));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(getMaxBalloonsForHeat(sb), getMaxBalloonsForHeat(sa));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.getY(), b.getY());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(a.getX(), b.getX());
            if (cmp != 0) return cmp;
            return Integer.compare(a.getZ(), b.getZ());
        };
        heatSources.sort(heatComparator);

        for (BlockPos heatPos : heatSources) {
            BlockState heatState = level.getBlockState(heatPos);
            int liftPerBalloon = getLiftPerBalloonForHeat(heatState);
            int maxBalloonsForThisSource = getMaxBalloonsForHeat(heatState);
            if (liftPerBalloon <= 0 || maxBalloonsForThisSource <= 0) {
                continue;
            }

            // Isı kaynağının tam üstündeki dikey bacadan (1..8 blok) ilk ulaştığı balon bloğunu bul
            BlockPos entryBalloon = findEntryBalloonAboveHeat(level, heatPos, validBalloons);
            if (entryBalloon == null) {
                continue;
            }

            // İlk balondan başlayarak bitişik balon kubbesi içinde Flood-Fill ile sıcak havayı yay
            Queue<BlockPos> bfs = new ArrayDeque<>();
            Set<BlockPos> visitedInDome = new HashSet<>();
            bfs.add(entryBalloon);
            visitedInDome.add(entryBalloon);

            int heatedByThisSource = 0;
            while (!bfs.isEmpty() && heatedByThisSource < maxBalloonsForThisSource) {
                BlockPos current = bfs.poll();
                int existingLift = balloonLiftMap.getOrDefault(current, 0);
                if (existingLift < liftPerBalloon) {
                    balloonLiftMap.put(current, liftPerBalloon);
                    heatedByThisSource++;
                }

                // 6 ana yön + 12 kenar komşusu (kavisli balon kubbelerinde ısının kesintisiz yayılması için)
                for (int[] offset : BFS_OFFSETS) {
                    int dx = offset[0];
                    int dy = offset[1];
                    int dz = offset[2];
                    BlockPos neighbor = current.offset(dx, dy, dz);
                    if (!validBalloons.contains(neighbor) || visitedInDome.contains(neighbor)) {
                        continue;
                    }
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 2) {
                        BlockPos bridgeA;
                        BlockPos bridgeB;
                        if (dx == 0) {
                            bridgeA = current.offset(0, dy, 0);
                            bridgeB = current.offset(0, 0, dz);
                        } else if (dy == 0) {
                            bridgeA = current.offset(dx, 0, 0);
                            bridgeB = current.offset(0, 0, dz);
                        } else {
                            bridgeA = current.offset(dx, 0, 0);
                            bridgeB = current.offset(0, dy, 0);
                        }
                        if (!canHotAirBridge(level, bridgeA, allBalloons)
                                && !canHotAirBridge(level, bridgeB, allBalloons)) {
                            continue;
                        }
                    }
                    if (visitedInDome.add(neighbor)) {
                        bfs.add(neighbor);
                    }
                }
            }
        }

        double avgBalloonY;
        if (!balloonLiftMap.isEmpty()) {
            double sumHeatedBalloonY = 0.0;
            for (BlockPos heatedPos : balloonLiftMap.keySet()) {
                sumHeatedBalloonY += heatedPos.getY();
            }
            avgBalloonY = sumHeatedBalloonY / balloonLiftMap.size();
        } else {
            avgBalloonY = sumBalloonY / balloonCount;
        }
        double avgHullY = hullCount > 0 ? (sumHullY / hullCount) : (avgBalloonY - 1.0);

        // Balonlar geminin tabanına/zemine döşenmişse (ortalama balon yüksekliği gövdeden aşağıdaysa)
        // veya balonların yarısından fazlasının üstüne gövde inşa edilmişse geometri geçersizdir!
        boolean balloonsProperlyPlaced = (avgBalloonY >= avgHullY - 0.15)
                && (crushedBalloonCount * 2 < balloonCount);

        if (!balloonsProperlyPlaced) {
            return new BalloonLiftAnalysis(0, balloonCount, 0, true, false);
        }

        int totalLift = 0;
        for (int lift : balloonLiftMap.values()) {
            totalLift += lift;
        }

        return new BalloonLiftAnalysis(
                totalLift,
                balloonCount,
                balloonLiftMap.size(),
                true,
                true
        );
    }

    private static boolean canHotAirBridge(Level level, BlockPos pos, Set<BlockPos> allBalloons) {
        return allBalloons.contains(pos) || allowsHotAirPassThrough(level, pos, level.getBlockState(pos));
    }

    private static BlockPos findEntryBalloonAboveHeat(Level level, BlockPos heatPos, Set<BlockPos> validBalloons) {
        for (int dy = 1; dy <= MAX_VERTICAL_PLUME_DISTANCE; dy++) {
            BlockPos directAbove = heatPos.above(dy);
            if (validBalloons.contains(directAbove)) {
                return directAbove;
            }

            // Arada sıcak havayı kesen katı bir blok varsa baca akışı kesilir
            BlockState midState = level.getBlockState(directAbove);
            if (!allowsHotAirPassThrough(level, directAbove, midState)) {
                return null;
            }

            // Aynı irtifada 1 blok yan (3x3 baca ağzı) içinde balon varsa onu da yakala
            for (int[] offset : PLUME_RING_OFFSETS) {
                int dx = offset[0];
                int dz = offset[1];
                BlockPos nearAbove = heatPos.offset(dx, dy, dz);
                if (!validBalloons.contains(nearAbove)) {
                    continue;
                }
                if (dx != 0 && dz != 0) {
                    BlockPos bridgeA = heatPos.offset(dx, dy, 0);
                    BlockPos bridgeB = heatPos.offset(0, dy, dz);
                    if (!canHotAirBridge(level, bridgeA, validBalloons)
                            && !canHotAirBridge(level, bridgeB, validBalloons)) {
                        continue;
                    }
                }
                return nearAbove;
            }
        }
        return null;
    }

    public static boolean isBalloonBlock(BlockState state) {
        if (state.getBlock() instanceof CanvasBalloonBlock || state.is(ModTags.BALLOON_BLOCKS)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null
                && id.getNamespace().equals("create_aeronautics")
                && (id.getPath().contains("envelope") || id.getPath().contains("balloon"));
    }

    public static boolean allowsHotAirPassThrough(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        Block block = state.getBlock();
        if (isBalloonBlock(state)
                || block instanceof FenceBlock
                || block instanceof WallBlock
                || block instanceof ChainBlock
                || block instanceof IronBarsBlock
                || block instanceof TrapDoorBlock
                || isActiveHeatSource(state)) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isLitCampfire(BlockState state) {
        return state.getBlock() instanceof CampfireBlock
                && state.hasProperty(CampfireBlock.LIT)
                && state.getValue(CampfireBlock.LIT);
    }

    public static boolean isHeatSourceBlock(BlockState state) {
        return state.getBlock() instanceof AirshipSuperHeaterBlock || isLitCampfire(state);
    }

    public static boolean isActiveHeatSource(BlockState state) {
        return getLiftPerBalloonForHeat(state) > 0;
    }

    public static int getLiftPerBalloonForHeat(BlockState state) {
        if (state.getBlock() instanceof AirshipSuperHeaterBlock) {
            return ModServerConfig.superHeaterLiftPerBalloon();
        }
        if (isLitCampfire(state)) {
            return ModServerConfig.campfireLiftPerBalloon();
        }
        return LIFT_UNHEATED;
    }

    public static int getMaxBalloonsForHeat(BlockState state) {
        if (state.getBlock() instanceof AirshipSuperHeaterBlock) {
            return ModServerConfig.superHeaterMaxBalloons();
        }
        if (isLitCampfire(state)) {
            return ModServerConfig.campfireMaxBalloons();
        }
        return 0;
    }
}
