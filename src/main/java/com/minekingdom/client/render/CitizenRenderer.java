package com.minekingdom.client.render;

import com.minekingdom.entity.CitizenEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Renders citizens with the vanilla player model: classic (Steve) for males,
 * slim (Alex) for females. HumanoidMobRenderer already installs the
 * item-in-hand layer, so the main hand item is drawn in the right hand.
 */
public class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, PlayerModel<CitizenEntity>> {
    private static final ResourceLocation MALE_TEXTURE =
            new ResourceLocation("textures/entity/player/wide/steve.png");
    private static final ResourceLocation FEMALE_TEXTURE =
            new ResourceLocation("textures/entity/player/slim/alex.png");

    private final PlayerModel<CitizenEntity> classicModel;
    private final PlayerModel<CitizenEntity> slimModel;

    public CitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.classicModel = this.getModel();
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.classicModel.setAllVisible(true);
        this.slimModel.setAllVisible(true);
    }

    @Override
    public void render(CitizenEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        this.model = entity.isFemale() ? this.slimModel : this.classicModel;
        this.model.rightArmPose = entity.getMainHandItem().isEmpty()
                ? HumanoidModel.ArmPose.EMPTY
                : HumanoidModel.ArmPose.ITEM;
        this.model.leftArmPose = entity.getOffhandItem().isEmpty()
                ? HumanoidModel.ArmPose.EMPTY
                : HumanoidModel.ArmPose.ITEM;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        return entity.isFemale() ? FEMALE_TEXTURE : MALE_TEXTURE;
    }
}
