package com.createairlogistics.ponder;

import com.createairlogistics.content.contraption.propulsion.PathfinderPropellerBlock;
import com.createairlogistics.content.contraption.propulsion.PressureEngineBlockEntity;
import com.createairlogistics.content.dock.DockShaftSocket;
import com.createairlogistics.content.dock.SkyDockBlock;
import com.createairlogistics.content.dock.SkyDockBlockEntity;
import com.createairlogistics.content.logistics.cargo.AirshipCargoHoldBlock;
import com.createairlogistics.content.pressure.AirPressure;
import com.createairlogistics.content.pressure.PressureTankBlockEntity;
import com.createairlogistics.registry.ModBlocks;
import com.createairlogistics.registry.ModItems;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllFluids;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Create [W] Ponder 3B Etkileşimli Eğitim Sahneleri (ponderairship2 Gerçek Gemi Şeması)
 *
 * Tüm sahneler kullanıcının oyun içinde inşa ettiği 11x8x11 boyutlu gerçek zeplin şeması
 * ({@code ponder/ponderairship2.nbt}) üzerinde çalışır. Bloklar sıfırdan koyulmaz; şemadaki
 * hazır bloklar katman katman sahneye indirilir, vurgulanır ve sonunda Super Glue ile tek parça
 * halinde havalandırılır.
 *
 * Şema Haritası:
 *  - Liman Hattı (z=1..2): [1,1,1] Büyük Dişli, [1,2,2] Dişli, [2..3,2,2] Şaft, [4,2,2] Sky Dock, [5,2,2] Brass Funnel
 *  - Yanaşma Boşluğu: [4,2,3]
 *  - Gemi (x=2..9, y=1..6, z=4..8): Taban, Ship Port, Korkuluk, Ambar, Kamp Ateşleri, Tank, Motor, Pervane, Balon Kubbesi
 */
public final class ModPonderScenes {

    private ModPonderScenes() {
    }

    // =====================================================================
    // ŞEMA KOORDİNATLARI (ponderairship2.nbt)
    // =====================================================================

    private static final int SIZE = 11;

    private static BlockPos largeCog(SceneBuildingUtil u) { return u.grid().at(1, 1, 1); }
    private static BlockPos smallCog(SceneBuildingUtil u) { return u.grid().at(1, 2, 2); }
    private static BlockPos dock(SceneBuildingUtil u) { return u.grid().at(4, 2, 2); }
    private static BlockPos funnel(SceneBuildingUtil u) { return u.grid().at(5, 2, 2); }
    private static BlockPos gap(SceneBuildingUtil u) { return u.grid().at(4, 2, 3); }
    private static BlockPos port(SceneBuildingUtil u) { return u.grid().at(4, 2, 4); }
    private static BlockPos hold(SceneBuildingUtil u) { return u.grid().at(4, 2, 6); }
    private static BlockPos campfireA(SceneBuildingUtil u) { return u.grid().at(5, 2, 6); }
    private static BlockPos campfireB(SceneBuildingUtil u) { return u.grid().at(6, 2, 6); }
    private static BlockPos tank(SceneBuildingUtil u) { return u.grid().at(7, 2, 7); }
    private static BlockPos engine(SceneBuildingUtil u) { return u.grid().at(8, 2, 6); }
    private static BlockPos propeller(SceneBuildingUtil u) { return u.grid().at(9, 2, 6); }

    /** Liman hattının tamamı: büyük dişli + dişli + şaftlar + Sky Dock + Brass Funnel. */
    private static Selection dockLine(SceneBuildingUtil u) {
        return u.select().position(1, 1, 1).add(u.select().fromTo(1, 2, 2, 5, 2, 2));
    }

    /** Dişli zinciri + Sky Dock (kinetik hız alan bloklar). */
    private static Selection drivenLine(SceneBuildingUtil u) {
        return u.select().fromTo(1, 2, 2, 4, 2, 2);
    }

    private static Selection hull(SceneBuildingUtil u) {
        return u.select().fromTo(2, 1, 4, 8, 1, 8);
    }

    /** y=2 çevre korkulukları (Ship Port ve Basınç Motoru hariç, kapı dahil). */
    private static Selection railings(SceneBuildingUtil u) {
        return u.select().fromTo(2, 2, 4, 8, 2, 8)
                .substract(u.select().fromTo(3, 2, 5, 7, 2, 7))
                .substract(u.select().position(4, 2, 4))
                .substract(u.select().position(8, 2, 6));
    }

    /** Güverte katı (y=2): korkuluk, port, iç donanım, motor ve pervane. */
    private static Selection deckLevel(SceneBuildingUtil u) {
        return u.select().fromTo(2, 2, 4, 9, 2, 8);
    }

    private static Selection posts(SceneBuildingUtil u) {
        return u.select().fromTo(2, 3, 4, 8, 4, 8);
    }

    private static Selection balloonFrame(SceneBuildingUtil u) {
        return u.select().fromTo(2, 5, 4, 8, 5, 8);
    }

    private static Selection balloonCeiling(SceneBuildingUtil u) {
        return u.select().fromTo(2, 6, 4, 8, 6, 8);
    }

    private static Selection fullShip(SceneBuildingUtil u) {
        return u.select().fromTo(2, 1, 4, 9, 6, 8);
    }

    /** Her sahne başında: geniş taban, dişliler durgun, manometreler sıfır. */
    private static CreateSceneBuilder setupShipScene(SceneBuilder builder, SceneBuildingUtil util,
                                                     String id, String title, float scale) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(id, title);
        scene.configureBasePlate(0, 0, SIZE);
        scene.scaleSceneView(scale);

        scene.world().setKineticSpeed(dockLine(util), 0f);
        scene.world().modifyBlockEntity(engine(util), PressureEngineBlockEntity.class, be -> be.setAir(0));
        scene.world().modifyBlockEntity(tank(util), PressureTankBlockEntity.class, be -> be.setAir(0));

        scene.showBasePlate();
        scene.idle(15);
        return scene;
    }

    private static void spinDockLine(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().setKineticSpeed(util.select().position(largeCog(util)), -32f);
        scene.world().setKineticSpeed(drivenLine(util), 64f);
    }

    private static void showDeck(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().showSection(hull(util), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(deckLevel(util), Direction.DOWN);
        scene.idle(15);
    }

    private static void showBalloonDome(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().showSection(posts(util), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(balloonFrame(util), Direction.DOWN);
        scene.idle(8);
        scene.world().showSection(balloonCeiling(util), Direction.DOWN);
        scene.idle(15);
    }

    private static void takeOff(CreateSceneBuilder scene, SceneBuildingUtil util) {
        ElementLink<WorldSectionElement> shipLink = scene.world().makeSectionIndependent(fullShip(util));
        // Pervane DOĞU'ya üfler -> gemi ileri yönü olan BATI'ya (-X) süzülür
        scene.world().moveSection(shipLink, new Vec3(-3.0, 2.5, 0.0), 70);
        scene.idle(80);
    }

    // =====================================================================
    // SAHNE 1: TAM GEMİ KURULUMU, BLOKLARIN GÖREVLERİ VE SUPER GLUE
    // =====================================================================

    public static void airshipMasterGuide(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = setupShipScene(builder, util,
                "airship_master_guide", "Hava Gemisi Kurulumu: Bloklar, Görevleri ve Süper Tutkal", 0.68f);

        BlockPos dock = dock(util);
        BlockPos funnel = funnel(util);
        BlockPos gap = gap(util);
        BlockPos port = port(util);
        BlockPos hold = hold(util);
        BlockPos tank = tank(util);
        BlockPos engine = engine(util);
        BlockPos prop = propeller(util);

        // ---------- ADIM 1: LİMAN HATTI VE KİNETİK GÜÇ ----------
        scene.world().showSection(dockLine(util), Direction.DOWN);
        scene.idle(15);
        spinDockLine(scene, util);
        scene.effects().rotationSpeedIndicator(smallCog(util));
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.BLUE, "dock", util.select().position(dock), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.BLUE)
                .text("Liman Vinci: Ön yüz dışındaki bir yüzüne bağlanan şaftın dönme gücüyle basınçlı hava üretir ve kendi tankında biriktirir.")
                .pointAt(util.vector().topOf(dock))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.OUTPUT, "funnel", util.select().position(funnel), 80);
        scene.overlay().showText(80)
                .colored(PonderPalette.OUTPUT)
                .text("Yanındaki Pirinç Huni, limana kargo verir veya limandan kargo alır. Gemi yanaştığında yük otomatik aktarılır.")
                .pointAt(util.vector().centerOf(funnel))
                .placeNearTarget();
        scene.idle(90);

        // ---------- ADIM 2: GÖVDE, SHIP PORT, KORKULUKLAR VE 1 BLOK BOŞLUK ----------
        scene.world().showSection(hull(util), Direction.DOWN);
        scene.idle(12);
        scene.world().showSection(util.select().position(port), Direction.DOWN);
        scene.idle(8);
        scene.world().showSection(railings(util), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.GREEN, "gap", util.select().position(gap), 100);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "port", util.select().position(port), 100);
        scene.overlay().showBigLine(PonderPalette.GREEN, util.vector().blockSurface(dock, Direction.SOUTH),
                util.vector().blockSurface(port, Direction.NORTH), 100);
        scene.overlay().showText(100)
                .colored(PonderPalette.GREEN)
                .text("Gemi Yanaşma Kapısı, geminin iskele kapısıdır. Limana dönük kurulur ve Liman Vinci ile arasında TAM 1 BLOK boşluk bırakılır. Hava ve kargo bu boşluktan aktarılır.")
                .pointAt(util.vector().centerOf(gap))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(110);

        // ---------- ADIM 3: İÇ DONANIM ----------
        scene.world().showSection(util.select().position(hold), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.BLUE, "hold", util.select().position(hold), 75);
        scene.overlay().showText(75)
                .colored(PonderPalette.BLUE)
                .text("Gemi Ambarı: Taşınan tüm kargo burada saklanır.")
                .pointAt(util.vector().topOf(hold))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(85);

        Selection campfires = util.select().fromTo(5, 2, 6, 6, 2, 6);
        scene.world().showSection(campfires, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.RED, "campfires", campfires, 80);
        scene.overlay().showText(80)
                .colored(PonderPalette.RED)
                .text("Kamp Ateşleri: Üstteki balonları ısıtır. Isınan balon kaldırma kuvveti üretir; ısıtıcısız balon gemiyi kaldıramaz.")
                .pointAt(util.vector().topOf(campfireA(util)))
                .placeNearTarget();
        scene.idle(90);

        scene.world().showSection(util.select().position(tank), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.GREEN, "tank", util.select().position(tank), 75);
        scene.overlay().showText(75)
                .colored(PonderPalette.GREEN)
                .text("Basınç Tankı: +900 Hava ek depo. Daha uzun rotalar için menzili uzatır.")
                .pointAt(util.vector().topOf(tank))
                .placeNearTarget();
        scene.idle(85);

        // ---------- ADIM 4: BASINÇ MOTORU + YÖN BULUCU PERVANE ----------
        Selection drive = util.select().fromTo(8, 2, 6, 9, 2, 6);
        scene.world().showSection(drive, Direction.WEST);
        scene.idle(15);

        scene.overlay().showControls(util.vector().topOf(prop), Pointing.DOWN, 60)
                .rightClick()
                .withItem(ModItems.FLIGHT_CHART.asStack());
        scene.overlay().showOutline(PonderPalette.RED, "drive", drive, 100);
        scene.overlay().showText(100)
                .colored(PonderPalette.RED)
                .text("Yön Bulucu Pervane geminin kenarına, önü 3x3 açık olacak şekilde konur. Tam arkasına Basınç Motoru (450 Hava) takılır. Pervaneye sağ tıklayıp Uçuş Haritasını yerleştirin.")
                .pointAt(util.vector().centerOf(prop))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(110);

        // ---------- ADIM 5: ÇİT DİREKLER VE BALON KUBBESİ ----------
        showBalloonDome(scene, util);
        scene.overlay().showOutline(PonderPalette.WHITE, "dome", util.select().fromTo(2, 5, 4, 8, 6, 8), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.WHITE)
                .text("4 köşedeki çit direkler, 2 katlı Kanvas Balon kubbesini taşır. Kubbe, kamp ateşlerinin tam üstünde durmalıdır.")
                .pointAt(util.vector().topOf(util.grid().at(5, 6, 6)))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        // ---------- ADIM 6: SUPER GLUE, BASINÇ DOLUMU VE KALKIŞ ----------
        Selection fullShip = fullShip(util);

        // Tutkal gövdeden kubbeye doğru yayılır
        scene.overlay().showOutline(PonderPalette.GREEN, "glue", hull(util), 10);
        scene.effects().superGlue(util.grid().at(5, 1, 6), Direction.UP, true);
        scene.idle(8);
        scene.overlay().showOutline(PonderPalette.GREEN, "glue", util.select().fromTo(2, 1, 4, 9, 2, 8), 10);
        scene.effects().superGlue(engine, Direction.EAST, true);
        scene.idle(8);
        scene.overlay().showOutline(PonderPalette.GREEN, "glue", util.select().fromTo(2, 1, 4, 9, 4, 8), 10);
        scene.effects().superGlue(util.grid().at(2, 4, 4), Direction.UP, true);
        scene.idle(8);
        scene.overlay().showOutline(PonderPalette.GREEN, "glue", fullShip, 130);
        scene.effects().superGlue(util.grid().at(5, 5, 4), Direction.UP, true);

        scene.overlay().showControls(util.vector().topOf(util.grid().at(5, 6, 6)), Pointing.DOWN, 70)
                .rightClick()
                .withItem(AllItems.SUPER_GLUE.asStack());

        // Manometre ibreleri canlı yükselir
        scene.world().modifyBlockEntity(engine, PressureEngineBlockEntity.class, be -> be.setAir(AirPressure.engineCapacity()));
        scene.world().modifyBlockEntity(tank, PressureTankBlockEntity.class, be -> be.setAir(PressureTankBlockEntity.getCapacity()));

        scene.overlay().showText(130)
                .colored(PonderPalette.GREEN)
                .text("EN ÖNEMLİ ADIM: Geminin tek parça uçabilmesi için Ahşap Taban, Çit Direkler, Balon Kubbesi, Gemi Yanaşma Kapısı, Gemi Ambarı, Kamp Ateşleri, Basınç Tankı, Motor ve Pervanenin TAMAMI Süper Tutkal ile birbirine yapıştırılmalıdır! Tutkalsız kalan blok limanda kalır.")
                .pointAt(util.vector().centerOf(port))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(140);

        scene.overlay().showText(70)
                .colored(PonderPalette.BLUE)
                .text("Tanklar dolu, tutkal tamam: Kalkış!")
                .pointAt(util.vector().topOf(engine))
                .placeNearTarget();
        scene.idle(20);
        takeOff(scene, util);
        scene.markAsFinished();
    }

    // =====================================================================
    // SAHNE 2: BASINÇLI HAVA, LİMAN ŞAFT GİRİŞİ, MANOMETRELER VE TAKVİYE
    // =====================================================================

    public static void pressureAndDockPowerGuide(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("pressure_and_dock_power", "Basınçlı Hava: Liman Gücü, Manometreler ve Takviye");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.76f);

        BlockPos largeCog = util.grid().at(3, 1, 2);
        BlockPos smallCog = util.grid().at(3, 2, 3);
        BlockPos dock = util.grid().at(6, 2, 3);
        BlockPos gap = util.grid().at(6, 2, 4);
        BlockPos port = util.grid().at(6, 2, 5);
        BlockPos tank = util.grid().at(9, 2, 8);
        BlockPos engine = util.grid().at(10, 2, 7);

        Selection cogs = util.select().position(largeCog).add(util.select().position(smallCog));
        Selection shafts = util.select().fromTo(4, 2, 3, 5, 2, 3);
        Selection dockAndFunnel = util.select().fromTo(6, 2, 3, 7, 2, 3);
        Selection dockLine = util.select().position(3, 1, 2).add(util.select().fromTo(3, 2, 3, 7, 2, 3));
        Selection drivenLine = util.select().fromTo(3, 2, 3, 6, 2, 3);
        Selection lightHull = util.select().fromTo(4, 1, 5, 10, 1, 9);
        Selection lightDeck = util.select().fromTo(4, 2, 5, 11, 2, 9);
        Selection lightShip = util.select().fromTo(4, 1, 5, 11, 2, 9);

        scene.world().setKineticSpeed(dockLine, 0f);
        scene.world().modifyBlockEntity(engine, PressureEngineBlockEntity.class, be -> be.setAir(0));
        scene.world().modifyBlockEntity(tank, PressureTankBlockEntity.class, be -> be.setAir(0));
        scene.showBasePlate();
        scene.idle(15);
        scene.world().modifyBlock(dock, s -> s.setValue(SkyDockBlock.SHAFT, DockShaftSocket.NONE), false);

        scene.world().showSection(cogs, Direction.DOWN);
        scene.world().showSection(dockAndFunnel, Direction.DOWN);
        scene.idle(20);

        // ---------- ADIM 1: WRENCH İLE ŞAFT GİRİŞİ ----------
        scene.overlay().showControls(util.vector().blockSurface(dock, Direction.WEST), Pointing.RIGHT, 50)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.overlay().showText(70)
                .text("Liman Vincinin ön yüzü dışındaki bir yüzüne İngiliz Anahtarı ile sağ tıklayın: O yüzde şaft girişi açılır.")
                .pointAt(util.vector().blockSurface(dock, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(30);
        scene.world().modifyBlock(dock, s -> s.setValue(SkyDockBlock.SHAFT, DockShaftSocket.WEST), true);
        scene.idle(50);

        scene.world().showSection(shafts, Direction.DOWN);
        scene.idle(12);
        scene.world().setKineticSpeed(util.select().position(largeCog), -32f);
        scene.world().setKineticSpeed(drivenLine, 64f);
        scene.effects().rotationSpeedIndicator(dock);

        scene.overlay().showOutline(PonderPalette.BLUE, "line", drivenLine, 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.BLUE)
                .text("Şafttan gelen RPM, limanın 1800 Hava kapasiteli tankını doldurur. Hız ne kadar yüksekse dolum o kadar hızlıdır.")
                .pointAt(util.vector().topOf(dock))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        // ---------- ADIM 2: GEMİ YANAŞIR, OTOMATİK BASINÇ TRANSFERİ ----------
        scene.world().showSection(lightHull, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(lightDeck, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showBigLine(PonderPalette.BLUE, util.vector().centerOf(dock), util.vector().centerOf(port), 100);
        scene.overlay().showBigLine(PonderPalette.BLUE, util.vector().centerOf(port), util.vector().centerOf(engine), 100);
        scene.overlay().showBigLine(PonderPalette.BLUE, util.vector().centerOf(port), util.vector().centerOf(tank), 100);
        scene.overlay().showText(100)
                .colored(PonderPalette.BLUE)
                .text("Gemi yanaştığında hava, 1 blokluk boşluk üzerinden Gemi Yanaşma Kapısına ve oradan gemideki motor ile tanklara otomatik aktarılır.")
                .pointAt(util.vector().centerOf(gap))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(110);

        // ---------- ADIM 3: MANOMETRELER YÜKSELİR ----------
        scene.world().modifyBlockEntity(engine, PressureEngineBlockEntity.class, be -> be.setAir(AirPressure.engineCapacity()));
        scene.overlay().showOutline(PonderPalette.OUTPUT, "engine", util.select().position(engine), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.OUTPUT)
                .text("Basınç Motoru: 450 Hava dahili depo. Manometre ibresi dolumu canlı gösterir. Rota için yeterli hava yoksa gemi kalkmaz.")
                .pointAt(util.vector().topOf(engine))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.world().modifyBlockEntity(tank, PressureTankBlockEntity.class, be -> be.setAir(PressureTankBlockEntity.getCapacity()));
        scene.overlay().showOutline(PonderPalette.GREEN, "tank", util.select().position(tank), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.GREEN)
                .text("Basınç Tankı: +900 Hava. Motorun yetmediği uzun rotalarda ek hava sağlar. Birden fazla tank yapıştırılabilir.")
                .pointAt(util.vector().topOf(tank))
                .placeNearTarget();
        scene.idle(100);

        // ---------- ADIM 4: AĞIR GEMİLER İÇİN TAKVİYE PERVANESİ ----------
        scene.world().hideSection(lightShip, Direction.UP);
        scene.idle(15);
        ElementLink<WorldSectionElement> heavyShip = scene.world().showIndependentSection(
                util.select().fromTo(1, 6, 5, 12, 7, 13), Direction.DOWN);
        scene.world().moveSection(heavyShip, new Vec3(0.0, -5.0, 0.0), 0);
        scene.idle(20);

        Selection heavyCargoBay = util.select().fromTo(3, 2, 7, 5, 2, 11);
        Selection boostPods = util.select().fromTo(10, 2, 7, 11, 2, 7)
                .add(util.select().fromTo(10, 2, 11, 11, 2, 11));
        scene.overlay().showOutline(PonderPalette.OUTPUT, "heavy_cargo", heavyCargoBay, 105);
        scene.overlay().showOutline(PonderPalette.GREEN, "boost_pods", boostPods, 105);
        scene.overlay().showText(105)
                .colored(PonderPalette.GREEN)
                .text("120 Ağırlığı aşan gemiler yavaşlar. Yön Bulucu Pervane ile aynı yöne bakan, arkasında Basınç Motoru olan Takviye Pervaneleri ekleyerek hızı geri kazanın.")
                .pointAt(util.vector().centerOf(util.grid().at(11, 2, 11)))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(115);
        scene.markAsFinished();
    }

    // =====================================================================
    // SAHNE 3: UÇUŞ HARİTASI, ROTA VE KARGO OTOMASYONU
    // =====================================================================

    public static void flightChartAndDockGuide(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = setupShipScene(builder, util,
                "flight_chart_and_dock_guide", "Uçuş Haritası, Rota ve Kargo Otomasyonu", 0.74f);

        BlockPos dock = dock(util);
        BlockPos funnel = funnel(util);
        BlockPos port = port(util);
        BlockPos hold = hold(util);
        BlockPos prop = propeller(util);

        // Başlangıçta harita takılı değil, ambar kapalı
        scene.world().modifyBlock(prop, s -> s.setValue(PathfinderPropellerBlock.HAS_CHART, false), false);
        scene.world().modifyBlock(hold, s -> s.setValue(AirshipCargoHoldBlock.OPEN, false), false);

        scene.world().showSection(dockLine(util), Direction.DOWN);
        scene.idle(12);
        spinDockLine(scene, util);
        showDeck(scene, util);

        // ---------- ADIM 1: LİMANI HARİTAYA KAYDET ----------
        scene.overlay().showControls(util.vector().topOf(dock), Pointing.DOWN, 50)
                .rightClick()
                .withItem(ModItems.FLIGHT_CHART.asStack());
        scene.overlay().showOutline(PonderPalette.OUTPUT, "dock", util.select().position(dock), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.OUTPUT)
                .text("Elinizde Uçuş Haritası varken Liman Vincine sağ tıklayın: Liman rotaya durak olarak kaydedilir. Varış limanını da aynı şekilde ekleyin.")
                .pointAt(util.vector().topOf(dock))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(80)
                .text("Ara noktalar: Bir zemin bloğuna sağ tıklayın veya havaya sağ tıklayıp harita menüsünden X/Y/Z ekleyin.")
                .pointAt(util.vector().topOf(util.grid().at(6, 0, 1)))
                .placeNearTarget();
        scene.idle(90);

        // ---------- ADIM 2: FUNNEL -> LİMAN -> AMBAR KARGO AKIŞI ----------
        ElementLink<EntityElement> cargo = scene.world().createItemEntity(
                util.vector().of(6.6, 2.6, 2.5), util.vector().of(-0.08, 0.05, 0), new ItemStack(Items.IRON_INGOT));
        scene.idle(12);
        scene.world().modifyEntity(cargo, Entity::discard);
        scene.world().flapFunnel(funnel, false);
        scene.idle(5);

        scene.world().modifyBlock(hold, s -> s.setValue(AirshipCargoHoldBlock.OPEN, true), false);
        scene.overlay().showBigLine(PonderPalette.OUTPUT, util.vector().centerOf(funnel), util.vector().centerOf(dock), 100);
        scene.overlay().showBigLine(PonderPalette.OUTPUT, util.vector().centerOf(dock), util.vector().centerOf(port), 100);
        scene.overlay().showBigLine(PonderPalette.OUTPUT, util.vector().centerOf(port), util.vector().centerOf(hold), 100);
        scene.overlay().showControls(util.vector().topOf(hold), Pointing.DOWN, 60)
                .withItem(new ItemStack(Items.IRON_INGOT));
        scene.overlay().showText(100)
                .colored(PonderPalette.BLUE)
                .text("Pirinç Huniye giren eşyalar limana, oradan Gemi Yanaşma Kapısı üzerinden Gemi Ambarına aktarılır. Liman menüsünden YÜKLE / BOŞALT modunu seçin.")
                .pointAt(util.vector().topOf(hold))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(110);
        scene.world().modifyBlock(hold, s -> s.setValue(AirshipCargoHoldBlock.OPEN, false), false);
        scene.idle(10);

        // ---------- ADIM 3: HARİTAYI PERVANEYE TAK ----------
        scene.overlay().showControls(util.vector().topOf(prop), Pointing.DOWN, 50)
                .rightClick()
                .withItem(ModItems.FLIGHT_CHART.asStack());
        scene.idle(15);
        scene.world().modifyBlock(prop, s -> s.setValue(PathfinderPropellerBlock.HAS_CHART, true), true);
        scene.overlay().showOutline(PonderPalette.RED, "prop", util.select().position(prop), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.RED)
                .text("Rotası hazır haritayı Yön Bulucu Pervaneye sağ tıklayarak takın. Gemi artık rotayı kendi başına izler.")
                .pointAt(util.vector().centerOf(prop))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        // ---------- ADIM 4: KUBBE + OTOMATİK SEFER ----------
        showBalloonDome(scene, util);
        scene.overlay().showText(70)
                .colored(PonderPalette.GREEN)
                .text("Hava ve kalkış şartı sağlanınca gemi otomatik sefere çıkar, varışta kargoyu boşaltır ve geri döner.")
                .pointAt(util.vector().topOf(util.grid().at(5, 6, 6)))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(30);
        takeOff(scene, util);
        scene.markAsFinished();
    }

    // =====================================================================
    // SAHNE 4: AĞIRLIK SINIFLARI VE BALON ISI ÇARPANLARI (Gerçek Gemi Üzerinde)
    // =====================================================================

    public static void balloonPhysicsAndWeights(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = setupShipScene(builder, util,
                "balloon_physics_and_weights", "Ağırlık Hesabı ve Balon Isı Çarpanları", 0.72f);

        BlockPos campA = campfireA(util);
        BlockPos campB = campfireB(util);

        showDeck(scene, util);

        // ---------- ADIM 1: AĞIRLIK SINIFLARI ----------
        scene.overlay().showOutline(PonderPalette.GREEN, "light", hull(util), 100);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "medium",
                util.select().position(port(util)).add(util.select().fromTo(8, 2, 6, 9, 2, 6)), 100);
        scene.overlay().showOutline(PonderPalette.RED, "heavy", util.select().position(hold(util)), 100);
        scene.overlay().showText(100)
                .text("AĞIRLIK: Balon ve Isıtıcı = 0 | Ahşap, Yün, Cam = 1 | Bakır, Kaplama, Motor ve Makineler = 2 | Demir, Altın, Obsidyen = 3 | Gemi Ambarı = 15.")
                .pointAt(util.vector().topOf(hold(util)))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(110);

        // ---------- ADIM 2: ISITICI KADEMELERİ ----------
        Selection heaters = util.select().fromTo(5, 2, 6, 6, 2, 6);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "heaters", heaters, 80);
        scene.overlay().showText(80)
                .colored(PonderPalette.OUTPUT)
                .text("Kamp Ateşi (Yanık): Balon başına +4 Kaldırma Kuvveti (ısıtıcı başına maks. 16 balon). Hafif ve başlangıç seviyesi gemiler için idealdir.")
                .pointAt(util.vector().topOf(campA))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(90);

        scene.world().setBlock(campA, ModBlocks.AIRSHIP_SUPER_HEATER.getDefaultState(), true);
        scene.idle(8);
        scene.world().setBlock(campB, ModBlocks.AIRSHIP_SUPER_HEATER.getDefaultState(), true);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.RED, "super_heaters", heaters, 90);
        scene.overlay().showControls(util.vector().topOf(campB), Pointing.DOWN, 60)
                .withItem(ModBlocks.AIRSHIP_SUPER_HEATER.asStack());
        scene.overlay().showText(90)
                .colored(PonderPalette.RED)
                .text("Hava Gemisi Süper Isıtıcısı: Balon başına +8 Kaldırma Kuvveti (ısıtıcı başına maks. 32 balon) — yakıt gerektirmez ve ağır kargo gemilerini kolayca kaldırır!")
                .pointAt(util.vector().topOf(campB))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);

        // ---------- ADIM 3: KUBBE ISITICILARIN ÜSTÜNDE ----------
        showBalloonDome(scene, util);
        scene.overlay().showOutline(PonderPalette.WHITE, "dome", util.select().fromTo(2, 5, 4, 8, 6, 8), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.WHITE)
                .text("Balonlar ısıtıcıların üstünde kubbe oluşturmalıdır. Isıtılmayan balon 0 Kaldırma Kuvveti üretir; toplam Kaldırma Kuvveti, geminin ağırlığını aşmalıdır.")
                .pointAt(util.vector().topOf(util.grid().at(5, 6, 6)))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(100);
        scene.markAsFinished();
    }

    // =====================================================================
    // SAHNE 5: SIVI TAŞIMACILIĞI, BORULAR, SIVI TANKLARI VE OTOMATİK POMPALAMA
    // =====================================================================

    public static void fluidTransportGuide(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = setupShipScene(builder, util,
                "fluid_transport_guide", "Sıvı Taşımacılığı: Borular, Sıvı Tankları ve Otomatik Pompalama", 0.68f);

        BlockPos dock = dock(util);
        BlockPos funnel = funnel(util);
        BlockPos gap = gap(util);
        BlockPos port = port(util);
        BlockPos hold = hold(util);
        BlockPos shipFluidTank = util.grid().at(6, 2, 6);
        BlockPos pressureTank = tank(util);
        BlockPos pressureEngine = engine(util);

        BlockPos dockFluidTank = util.grid().at(7, 1, 2);
        BlockPos pumpCog = util.grid().at(7, 2, 1);
        BlockPos mechanicalPump = util.grid().at(7, 2, 2);

        Selection pumpDrive = util.select().position(pumpCog).add(util.select().position(mechanicalPump));
        Selection pipes = util.select().fromTo(4, 3, 2, 7, 3, 2);
        Selection fluidSupply = util.select().position(dockFluidTank).add(pumpDrive).add(pipes);

        // Başlangıçta pompa durgun, liman tankı 8000 mB bal dolu, gemi tankı ve liman tamponu boş, ambar kapalı
        scene.world().setKineticSpeed(pumpDrive, 0f);
        FluidStack fullHoney = new FluidStack(AllFluids.HONEY.get().getSource(), 8000);
        scene.world().modifyBlockEntity(dockFluidTank, FluidTankBlockEntity.class, be -> {
            be.getTankInventory().drain(8000, IFluidHandler.FluidAction.EXECUTE);
            be.getTankInventory().fill(fullHoney, IFluidHandler.FluidAction.EXECUTE);
        });
        scene.world().modifyBlockEntity(shipFluidTank, FluidTankBlockEntity.class,
                be -> be.getTankInventory().drain(8000, IFluidHandler.FluidAction.EXECUTE));
        scene.world().modifyBlockEntity(dock, SkyDockBlockEntity.class,
                be -> be.getFluidBuffer().drain(SkyDockBlockEntity.FLUID_CAPACITY_MB, IFluidHandler.FluidAction.EXECUTE));
        scene.world().modifyBlock(hold, s -> s.setValue(AirshipCargoHoldBlock.OPEN, false), false);

        // ---------- ADIM 1: LİMAN SIVI HATTI + CAM BORUDA CANLI BAL AKIŞI ----------
        scene.world().showSection(dockLine(util), Direction.DOWN);
        scene.idle(12);
        spinDockLine(scene, util);
        scene.effects().rotationSpeedIndicator(smallCog(util));
        scene.idle(8);

        scene.world().showSection(util.select().position(dockFluidTank), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(pumpDrive, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(pipes, Direction.DOWN);
        scene.idle(12);

        scene.world().setKineticSpeed(util.select().position(pumpCog), -32f);
        scene.world().setKineticSpeed(util.select().position(mechanicalPump), 32f);
        scene.effects().rotationSpeedIndicator(mechanicalPump);
        scene.world().propagatePipeChange(mechanicalPump);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.BLUE, "fluid_supply", fluidSupply, 100);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "dock_fluid", util.select().position(dock), 100);
        scene.overlay().showText(100)
                .colored(PonderPalette.BLUE)
                .text("Liman Vinci yalnızca eşya değil, sıvı da taşır: Herhangi bir yüzüne mekanik boru ve pompa bağlayarak 16.000 mB kapasiteli dahili sıvı tamponunu doldurun.")
                .pointAt(util.vector().centerOf(util.grid().at(5, 3, 2)))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(110);

        // ---------- ADIM 2: GEMİDEKİ CREATE SIVI TANKI ----------
        scene.world().showSection(hull(util), Direction.DOWN);
        scene.idle(10);
        Selection deckWithoutFluidTank = deckLevel(util).substract(util.select().position(shipFluidTank));
        scene.world().showSection(deckWithoutFluidTank, Direction.DOWN);
        scene.idle(14);
        scene.world().showSection(util.select().position(shipFluidTank), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutline(PonderPalette.OUTPUT, "ship_fluid_tank", util.select().position(shipFluidTank), 100);
        scene.overlay().showControls(util.vector().topOf(shipFluidTank), Pointing.DOWN, 60)
                .withItem(AllBlocks.FLUID_TANK.asStack());
        scene.overlay().showText(100)
                .colored(PonderPalette.OUTPUT)
                .text("Gemiye koyduğunuz Create Sıvı Tankları otomatik olarak sıvı deposu sayılır (blok başına 5 Ağırlık). Çok bloklu tanklar da kurabilirsiniz — tamamını Süper Tutkal ile yapıştırın.")
                .pointAt(util.vector().topOf(shipFluidTank))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(110);

        // ---------- ADIM 3: EŞYA + SIVI EŞZAMANLI YÜKSEK HIZLI AKTARIM ----------
        ElementLink<EntityElement> cargo = scene.world().createItemEntity(
                util.vector().of(6.6, 2.6, 2.5), util.vector().of(-0.08, 0.05, 0), new ItemStack(Items.IRON_INGOT));
        scene.idle(12);
        scene.world().modifyEntity(cargo, Entity::discard);
        scene.world().flapFunnel(funnel, false);
        scene.idle(5);
        scene.world().modifyBlock(hold, s -> s.setValue(AirshipCargoHoldBlock.OPEN, true), false);

        scene.overlay().showOutline(PonderPalette.GREEN, "fluid_gap", util.select().position(gap), 110);
        scene.overlay().showBigLine(PonderPalette.OUTPUT, util.vector().centerOf(dock), util.vector().centerOf(port), 110);
        scene.overlay().showBigLine(PonderPalette.OUTPUT, util.vector().centerOf(port), util.vector().centerOf(shipFluidTank), 110);
        scene.overlay().showBigLine(PonderPalette.BLUE, util.vector().centerOf(funnel), util.vector().centerOf(dock), 110);
        scene.overlay().showBigLine(PonderPalette.BLUE, util.vector().centerOf(port), util.vector().centerOf(hold), 110);

        scene.overlay().showText(110)
                .colored(PonderPalette.OUTPUT)
                .text("Gemi yanaştığında sıvılar 1 blokluk boşluktan doğrudan gemideki sıvı tanklarına aktarılır. Eşyalar ve sıvılar aynı anda, yüksek hızda (4 tick'te 4.000 mB) yüklenir.")
                .pointAt(util.vector().centerOf(shipFluidTank))
                .placeNearTarget()
                .attachKeyFrame();

        for (int i = 0; i < 8; i++) {
            scene.world().modifyBlockEntity(dockFluidTank, FluidTankBlockEntity.class,
                    be -> be.getTankInventory().drain(750, IFluidHandler.FluidAction.EXECUTE));
            scene.world().modifyBlockEntity(shipFluidTank, FluidTankBlockEntity.class,
                    be -> be.getTankInventory().fill(
                            new FluidStack(AllFluids.HONEY.get().getSource(), 1000),
                            IFluidHandler.FluidAction.EXECUTE));
            scene.idle(6);
        }
        scene.idle(72);
        scene.world().modifyBlock(hold, s -> s.setValue(AirshipCargoHoldBlock.OPEN, false), false);
        scene.idle(10);

        // ---------- ADIM 4: YÜKLE/BOŞALT MODU VE KALKIŞ ŞARTLARI ----------
        scene.overlay().showControls(util.vector().topOf(dock), Pointing.DOWN, 55)
                .rightClick();
        scene.overlay().showOutline(PonderPalette.GREEN, "dock_menu", util.select().position(dock), 95);
        scene.overlay().showText(95)
                .colored(PonderPalette.GREEN)
                .text("Liman menüsünden YÜKLE veya BOŞALT modunu seçin. Kalkış şartları sıvıları da kapsar: 'Gemi Dolunca' modunda hem ambarlar hem de sıvı tankları dolmadan gemi kalkmaz.")
                .pointAt(util.vector().topOf(dock))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(105);

        // ---------- ADIM 5: VARIŞTA BOŞALT, KUBBE, SÜPER TUTKAL VE BAL DOLU KALKIŞ ----------
        showBalloonDome(scene, util);
        scene.effects().superGlue(shipFluidTank, Direction.DOWN, true);
        scene.overlay().showOutline(PonderPalette.GREEN, "glue_fluid_ship", fullShip(util), 85);
        scene.effects().superGlue(util.grid().at(5, 5, 6), Direction.UP, true);

        scene.world().modifyBlockEntity(pressureEngine, PressureEngineBlockEntity.class,
                be -> be.setAir(AirPressure.engineCapacity()));
        scene.world().modifyBlockEntity(pressureTank, PressureTankBlockEntity.class,
                be -> be.setAir(PressureTankBlockEntity.getCapacity()));

        scene.overlay().showText(90)
                .colored(PonderPalette.GREEN)
                .text("Varış limanını BOŞALT moduna alıp dışarı çeken bir pompa bağlayın: Gemi yanaşınca sıvı otomatik boşaltılır. Tanklar dolu, rota hazır — kalkış!")
                .pointAt(util.vector().topOf(shipFluidTank))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(25);
        takeOff(scene, util);
        scene.markAsFinished();
    }
}
