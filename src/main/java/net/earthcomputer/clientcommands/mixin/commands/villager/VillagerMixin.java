package net.earthcomputer.clientcommands.mixin.commands.villager;

import net.earthcomputer.clientcommands.command.PingCommand;
import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.features.VillagerCracker;
import net.earthcomputer.clientcommands.features.VillagerRngSimulator;
import net.earthcomputer.clientcommands.interfaces.IVillager;
import net.earthcomputer.clientcommands.util.CUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(Villager.class)
public abstract class VillagerMixin extends AbstractVillager implements IVillager {
    public VillagerMixin(EntityType<? extends AbstractVillager> entityType, Level level) {
        super(entityType, level);
    }

    @Unique
    VillagerRngSimulator simulator = new VillagerRngSimulator(null);

    @Override
    public void clientcommands_onAmbientSoundPlayed(float pitch) {
        simulator.onAmbientSoundPlayed(pitch);
    }

    @Override
    public void clientcommands_onNoSoundPlayed(float pitch) {
        VillagerProfession profession = ((Villager) (Object) this).getVillagerData().getProfession();
        simulator.onNoSoundPlayed(pitch, profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT);
    }

    @Override
    public void clientcommands_onYesSoundPlayed(float pitch) {
        simulator.onYesSoundPlayed(pitch);
    }

    @Override
    public void clientcommands_onSplashSoundPlayed(float pitch) {
        simulator.onSplashSoundPlayed(pitch);
    }

    @Override
    public void clientcommands_onXpOrbSpawned(int value) {
        simulator.onXpOrbSpawned(value);
    }

    @Override
    public void clientcommands_onServerTick() {
        simulator.simulateTick();

        if (VillagerCracker.isRunning() && !VillagerCracker.hasClickedVillager) {
            int millisecondsUntilInteract = simulator.getTicksRemaining() * VillagerCracker.serverMspt - PingCommand.getLocalPing() + VillagerCracker.magicMillisecondCorrection;
            if (millisecondsUntilInteract < 200) {
                LocalPlayer oldPlayer = Minecraft.getInstance().player;
                assert oldPlayer != null;
                CUtil.sendAtPreciseTime(
                    System.nanoTime() + millisecondsUntilInteract * 1_000_000L,
                    ServerboundInteractPacket.createInteractionPacket(this, false, InteractionHand.MAIN_HAND),
                    () -> true,
                    () -> {
                        LocalPlayer player = Minecraft.getInstance().player;
                        if (player == oldPlayer) {
                            player.swing(InteractionHand.MAIN_HAND);
                        }
                    }
                );
                simulator.reset();
                VillagerCracker.hasClickedVillager = true;
            }
        }
    }

    @Override
    public VillagerRngSimulator clientcommands_getVillagerRngSimulator() {
        return simulator;
    }

    @Override
    public void clientcommands_onGuiOpened(List<VillagerCommand.Offer> availableOffersList) {
        simulator.onGuiOpened(availableOffersList, (Villager) (Object) this);
    }
}
