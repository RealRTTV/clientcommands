package net.earthcomputer.clientcommands.mixin.commands.villager;

import net.earthcomputer.clientcommands.Configs;
import net.earthcomputer.clientcommands.command.ClientCommandHelper;
import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.features.VillagerCracker;
import net.earthcomputer.clientcommands.features.VillagerRngSimulator;
import net.earthcomputer.clientcommands.interfaces.IVillager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

@Mixin(Villager.class)
public abstract class VillagerMixin extends AbstractVillager implements IVillager {
    public VillagerMixin(EntityType<? extends AbstractVillager> entityType, Level level) {
        super(entityType, level);
    }

    @Unique
    VillagerRngSimulator rng = new VillagerRngSimulator(null);

    @Override
    public void clientcommands_onAmbientSoundPlayed(float pitch) {
        rng.onAmbientSoundPlayed(pitch);
    }

    @Override
    public void clientcommands_onNoSoundPlayed(float pitch) {
        VillagerProfession profession = ((Villager) (Object) this).getVillagerData().getProfession();
        rng.onNoSoundPlayed(pitch, profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT);
    }

    @Override
    public void clientcommands_onYesSoundPlayed(float pitch) {
        rng.onYesSoundPlayed(pitch);
    }

    @Override
    public void clientcommands_onSplashSoundPlayed(float pitch) {
        rng.onSplashSoundPlayed(pitch);
    }

    @Override
    public void clientcommands_onXpOrbSpawned(int value) {
        rng.onXpOrbSpawned(value);
    }

    @Override
    public void clientcommands_onServerTick() {
        rng.simulateTick();

        if (rng.shouldInteractWithVillager()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof MerchantScreen) {
                minecraft.screen.onClose();
            } else {
                InteractionResult result = minecraft.gameMode.interact(minecraft.player, this, InteractionHand.MAIN_HAND);
                if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.CLIENT) {
                    minecraft.player.swing(InteractionHand.MAIN_HAND);
                }
            }
        }
    }

    @Override
    public VillagerRngSimulator clientcommands_getVillagerRngSimulator() {
        return rng;
    }

    @Override
    public void clientcommands_onGuiOpened(List<VillagerCommand.Offer> availableOffersList) {
        rng.onGuiOpened(availableOffersList, (Villager) (Object) this);
    }
}
