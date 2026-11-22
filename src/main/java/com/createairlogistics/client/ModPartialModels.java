package com.createairlogistics.client;

import com.createairlogistics.CreateAirLogistics;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Create'in kendi AllPartialModels yaklaşımıyla kayıtlı, BER içinde dönen/hareket eden parçalar.
 * Flywheel PartialModel'leri model yükleme aşamasından önce oluşturulmalıdır; bu yüzden {@link #init()}
 * mod yapıcısında yalnızca istemci tarafında çağrılır.
 */
public final class ModPartialModels {

    /** 3x3 pervane rotoru (ön = kuzey, rotor düzlemi bloğun 1 önündeki hücrede). */
    public static final PartialModel PROPELLER_ROTOR = block("pathfinder_propeller/rotor");

    /** Takviye Pervanesi için andezit uçlu 3x3 pervane rotoru. */
    public static final PartialModel BOOST_PROPELLER_ROTOR = block("boost_propeller/rotor");

    /** Basınç Motoru ve Basınç Tankı üzerindeki hareketli 3B manometre ibresi. */
    public static final PartialModel GAUGE_NEEDLE = block("gauge_needle");

    /** Sky Dock şaft soketi içindeki döner kavrama göbeği ve kama dişleri (dış şaft bağlıyken de görünür). */
    public static final PartialModel DOCK_SHAFT_HUB = block("sky_dock_shaft_hub");

    private ModPartialModels() {
    }

    private static PartialModel block(String path) {
        return PartialModel.of(CreateAirLogistics.asResource("block/" + path));
    }

    public static void init() {
        // Statik alanların yüklenmesini tetikler.
    }

    /** Bir PartialModel'i mevcut PoseStack dönüşümüyle, verilen render katmanında çizer. */
    public static void render(PartialModel partial, BlockState referenceState, PoseStack ms, VertexConsumer consumer,
                              RenderType renderType, int light, int overlay) {
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                ms.last(), consumer, referenceState, partial.get(),
                1.0f, 1.0f, 1.0f, light, overlay, ModelData.EMPTY, renderType);
    }

    /**
     * Blok merkezine göre (0.5, 0.5, 0.5 öteleme sonrası çağrılmalı) kuzeye bakan bir modeli verilen yöne çevirir.
     * Blockstate JSON'undaki x/y dönüşleriyle birebir aynı sonucu verir.
     */
    public static void rotateNorthTo(PoseStack ms, Direction dir) {
        switch (dir) {
            case NORTH -> {
            }
            case SOUTH -> ms.mulPose(Axis.YP.rotationDegrees(180.0f));
            case EAST -> ms.mulPose(Axis.YP.rotationDegrees(-90.0f));
            case WEST -> ms.mulPose(Axis.YP.rotationDegrees(90.0f));
            case UP -> ms.mulPose(Axis.XP.rotationDegrees(90.0f));
            case DOWN -> ms.mulPose(Axis.XP.rotationDegrees(-90.0f));
        }
    }
}
