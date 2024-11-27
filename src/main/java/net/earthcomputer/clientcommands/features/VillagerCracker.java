package net.earthcomputer.clientcommands.features;

import net.earthcomputer.clientcommands.command.ClientCommandHelper;
import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.event.ClientConnectionEvents;
import net.earthcomputer.clientcommands.event.MoreClientEvents;
import net.earthcomputer.clientcommands.interfaces.IVillager;
import net.earthcomputer.clientcommands.util.CombinedMedianEM;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;

public class VillagerCracker {
    // This value was computed by brute forcing all seeds
    public static final float MAX_ERROR = 5 * 0x1.0p-24f;

    @Nullable
    private static UUID villagerUuid = null;
    @Nullable
    private static WeakReference<Villager> cachedVillager = null;
    @Nullable
    private static GlobalPos clockPos = null;
    private static boolean isNewClock;
    @Nullable
    public static VillagerCommand.Offer targetOffer = null;
    public static VillagerRngSimulator.SurroundingOffers surroundingOffers = null;
    private static int clockTicksSinceLastTimeSync = 0;
    private static long lastClockRateWarning = 0;
    public static boolean hasClickedVillager = false;

    private static long lastTimeSyncTime;
    public static int serverMspt = SharedConstants.MILLIS_PER_TICK;
    public static int magicMillisecondCorrection = 25;
    public static int maxTicksBefore = 10;
    public static int maxTicksAfter = 10;
    public static final CombinedMedianEM combinedMedianEM = new CombinedMedianEM();

    static {
        ClientConnectionEvents.DISCONNECT.register(VillagerCracker::stopRunning);
        MoreClientEvents.TIME_SYNC.register(packet -> onTimeSync());
    }

    @Nullable
    public static Villager getVillager() {
        if (villagerUuid == null) {
            cachedVillager = null;
            return null;
        }
        if (cachedVillager != null) {
            Villager villager = cachedVillager.get();
            if (villager != null && !villager.isRemoved()) {
                return villager;
            }
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (Entity entity : level.entitiesForRendering()) {
                if (villagerUuid.equals(entity.getUUID()) && entity instanceof Villager villager) {
                    cachedVillager = new WeakReference<>(villager);
                    return villager;
                }
            }
        }
        return null;
    }

    @Nullable
    public static GlobalPos getClockPos() {
        return clockPos;
    }

    public static void setTargetVillager(@Nullable Villager villager) {
        Villager oldVillager = getVillager();
        if (oldVillager != null) {
            ((IVillager) oldVillager).clientcommands_getVillagerRngSimulator().reset();
        }

        if (clockPos == null) {
            ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.noClock"));
        }

        ClientLevel level = Minecraft.getInstance().level;

        if (level.getDayTime() % 24000 < 12000) {
            ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.day"));
        }

        VillagerCracker.cachedVillager = new WeakReference<>(villager);
        VillagerCracker.villagerUuid = villager == null ? null : villager.getUUID();
    }

    public static void setClockPos(@Nullable GlobalPos pos) {
        VillagerCracker.clockPos = pos;
        if (pos != null) {
            isNewClock = true;
        }
    }

    public static void onSoundEventPlayed(ClientboundSoundPacket packet, Vec3 pos) {
        Villager targetVillager = getVillager();
        if (targetVillager == null || getClockPos() == null || pos.distanceToSqr(targetVillager.position()) > 0.1f) {
            return;
        }

        SoundEvent soundEvent = packet.getSound().value();
        if (soundEvent == SoundEvents.VILLAGER_AMBIENT || soundEvent == SoundEvents.VILLAGER_TRADE) {
            ((IVillager) targetVillager).clientcommands_onAmbientSoundPlayed(packet.getPitch());
        } else if (soundEvent == SoundEvents.VILLAGER_NO) {
            ((IVillager) targetVillager).clientcommands_onNoSoundPlayed(packet.getPitch());
        } else if (soundEvent == SoundEvents.VILLAGER_YES) {
            ((IVillager) targetVillager).clientcommands_onYesSoundPlayed(packet.getPitch());
        } else if (soundEvent == SoundEvents.GENERIC_SPLASH) {
            ((IVillager) targetVillager).clientcommands_onSplashSoundPlayed(packet.getPitch());
        }
    }

    public static void onXpOrbSpawned(ClientboundAddExperienceOrbPacket packet) {
        Villager targetVillager = getVillager();
        if (targetVillager == null) {
            return;
        }

        ((IVillager) targetVillager).clientcommands_onXpOrbSpawned(packet.getValue());
    }

    private static void onTimeSync() {
        long now = System.nanoTime();

        if (getVillager() != null && clockPos != null && !isNewClock && clockTicksSinceLastTimeSync != 20) {
            if (now - lastClockRateWarning >= 60_000_000_000L) {
                if (clockTicksSinceLastTimeSync < 20) {
                    ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.tooSlow"));
                } else {
                    ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.tooFast"));
                }
                lastClockRateWarning = now;
            }
        }
        isNewClock = false;
        clockTicksSinceLastTimeSync = 0;

        serverMspt = (3 * serverMspt + (int) ((now - lastTimeSyncTime) / 20_000_000)) / 4;
        lastTimeSyncTime = now;
    }

    public static void onServerTick() {
        clockTicksSinceLastTimeSync++;

        Villager targetVillager = getVillager();
        if (targetVillager == null) {
            return;
        }

        ((IVillager) targetVillager).clientcommands_onServerTick();
    }

    public static boolean isRunning() {
        return targetOffer != null;
    }

    public static void stopRunning() {
        targetOffer = null;
    }
}
