package net.earthcomputer.clientcommands.interfaces;

import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.features.VillagerRngSimulator;

import java.util.List;

public interface IVillager {
    VillagerRngSimulator clientcommands_getVillagerRngSimulator();

    void clientcommands_onAmbientSoundPlayed(float pitch);

    void clientcommands_onNoSoundPlayed(float pitch);

    void clientcommands_onYesSoundPlayed(float pitch);

    void clientcommands_onSplashSoundPlayed(float pitch);

    void clientcommands_onXpOrbSpawned(int value);

    void clientcommands_onServerTick();

    void clientcommands_onGuiOpened(List<VillagerCommand.Offer> list);
}
