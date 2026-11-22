package com.createairlogistics.content.pressure;

import com.simibubi.create.content.equipment.armor.BacktankUtil;
import net.minecraft.util.Mth;

/**
 * Basınçlı hava sisteminin tek kaynaklı sabitleri ve hesapları.
 *
 * Bütün kapasiteler Create'in Copper Backtank config değerine ({@code airInBacktank}, varsayılan 900) bağlıdır;
 * sunucu sahibi backtank kapasitesini değiştirirse motor, liman ve menzil de aynı oranda ölçeklenir.
 * Dolum hızı ve SU maliyeti Copper Backtank ile birebir aynıdır ({@link Compressor}, stres etkisi 4.0).
 */
public final class AirPressure {

    /** Copper Backtank'in RPM başına stres etkisi (Create BuilderTransformers.backtank → CStress.setImpact(4.0)). */
    public static final double DOCK_STRESS_IMPACT = 4.0;

    /** Motor başına saniyede harcanan hava (1 hava = backtank'te 1 saniyelik nefes). */
    public static final double AIR_PER_ENGINE_SECOND = 2.0;
    public static final double AIR_PER_ENGINE_TICK = AIR_PER_ENGINE_SECOND / 20.0;

    /** Kalkış ivmelenmesi için eklenen tahmini süre (TravellingAirship ivmesi ≈ 29 tick). */
    private static final double ACCELERATION_OVERHEAD_TICKS = 30.0;
    /** Hesap güvenlik payı: sefer gerçekte asla tahmin edilenden fazla hava harcamaz. */
    private static final double SAFETY_MARGIN = 1.05;

    /** Liman kilitliyken tick başına gemiye aktarılan en fazla hava (900 hava ≈ 2 sn). */
    public static final int DOCK_TRANSFER_PER_TICK = 20;

    private AirPressure() {
    }

    /** Create config'indeki büyüsüz backtank kapasitesi. */
    public static int backtankAir() {
        try {
            return Math.max(1, BacktankUtil.maxAirWithoutEnchants());
        } catch (IllegalStateException ignored) {
            return 900;
        }
    }

    /** Backtank kapasitesi (Capacity büyüsü dahil). */
    public static int backtankAir(int capacityEnchantLevel) {
        try {
            return Math.max(1, BacktankUtil.maxAir(capacityEnchantLevel));
        } catch (IllegalStateException ignored) {
            return 900;
        }
    }

    /** Basınç Motorunun kendi iç deposu: yarım backtank. Küçük gemiler için tek başına yeterlidir. */
    public static int engineCapacity() {
        return Math.max(1, backtankAir() / 2);
    }

    /** Limanın iç tankı: iki backtank. Gemi uzaktayken dolar, gemi yanaşınca anında aktarır. */
    public static int dockCapacity() {
        return backtankAir() * 2;
    }

    /**
     * Bir seferin (tek bacak) hava maliyeti. Zamana bağlıdır: yavaş gemi daha uzun uçar, daha çok harcar.
     *
     * @param arcLength     bacağın uzunluğu (blok)
     * @param blocksPerTick seyir hızı
     * @param engines       çalışan motor sayısı
     */
    public static int legCost(double arcLength, double blocksPerTick, int engines) {
        if (engines <= 0 || arcLength <= 0.0) {
            return 0;
        }
        double speed = Math.max(0.01, blocksPerTick);
        double brakingDistance = Mth.clamp(speed * 40.0, 6.0, 16.0);
        double ticks = arcLength / speed + ACCELERATION_OVERHEAD_TICKS + brakingDistance / speed;
        return (int) Math.ceil(engines * AIR_PER_ENGINE_TICK * ticks * SAFETY_MARGIN);
    }

    /**
     * Kalkış için gereken hava: varış limanında şaft gücü yoksa dönüş bacağı da depoda olmalıdır,
     * böylece gemi karşı limanda asla mahsur kalmaz.
     */
    public static int requiredForDeparture(int legCost, boolean destinationPowered) {
        return destinationPowered ? legCost : legCost * 2;
    }

    /**
     * Copper Backtank'in dolum algoritmasının birebir kopyası (BacktankBlockEntity#tick):
     * RPM arttıkça hem bekleme süresi kısalır hem de adım başına eklenen hava artar.
     */
    public static final class Compressor {
        private int timer = 0;

        /**
         * @return bu tick eklenmesi gereken hava miktarı (0 olabilir)
         */
        public int tick(float speed) {
            if (speed == 0.0f) {
                return 0;
            }
            if (timer > 0) {
                timer--;
                return 0;
            }
            float abs = Math.abs(speed);
            int increment = Mth.clamp(((int) abs - 100) / 20, 1, 5);
            timer = Mth.clamp((int) (128.0f - abs / 5.0f) - 108, 0, 20);
            return increment;
        }

        public int getTimer() {
            return timer;
        }

        public void setTimer(int timer) {
            this.timer = Math.max(0, timer);
        }
    }
}
