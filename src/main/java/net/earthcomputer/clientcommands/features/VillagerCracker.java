package net.earthcomputer.clientcommands.features;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.earthcomputer.clientcommands.Configs;
import net.earthcomputer.clientcommands.command.ClientCommandHelper;
import net.earthcomputer.clientcommands.command.PingCommand;
import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.event.ClientConnectionEvents;
import net.earthcomputer.clientcommands.event.MoreClientEvents;
import net.earthcomputer.clientcommands.util.CUtil;
import net.earthcomputer.clientcommands.util.CombinedMedianEM;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public class VillagerCracker {
    // This value was computed by brute forcing all seeds
    public static final float MAX_ERROR = 5 * 0x1.0p-24f;

    @Nullable
    private static UUID villagerUuid = null;
    @Nullable
    private static WeakReference<Villager> cachedVillager = null;
    public static final VillagerRngSimulator simulator = new VillagerRngSimulator(null);
    @Nullable
    private static GlobalPos clockPos = null;
    private static boolean isNewClock;
    public static final List<Goal> goals = new ArrayList<>();
    @Nullable
    public static Offer targetOffer = null;
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
        ClientConnectionEvents.DISCONNECT.register(VillagerCracker::onDisconnect);
        MoreClientEvents.TIME_SYNC.register(packet -> onTimeSync());
    }

    @Nullable
    public static Villager getVillager() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }

        if (villagerUuid == null) {
            cachedVillager = null;
            return null;
        }
        if (cachedVillager != null) {
            Villager villager = cachedVillager.get();
            if (villager != null && !villager.isRemoved() && villager.level() == level) {
                return villager;
            }
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (villagerUuid.equals(entity.getUUID()) && entity instanceof Villager villager) {
                cachedVillager = new WeakReference<>(villager);
                return villager;
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
            simulator.reset();
        }

        if (clockPos == null) {
            ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.noClock"));
        }

        ClientLevel level = Minecraft.getInstance().level;

        if (level.getDayTime() % 24000 < 12000) { // todo, double check this works
            ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.day"));
        }

        cachedVillager = new WeakReference<>(villager);
        villagerUuid = villager == null ? null : villager.getUUID();
    }

    public static void setClockPos(@Nullable GlobalPos pos) {
        clockPos = pos;
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
            simulator.onAmbientSoundPlayed(packet.getPitch());
        } else if (soundEvent == SoundEvents.VILLAGER_NO) {
            VillagerProfession profession = targetVillager.getVillagerData().getProfession();
            simulator.onNoSoundPlayed(packet.getPitch(), profession != VillagerProfession.NONE && profession != VillagerProfession.NITWIT);
        } else if (soundEvent == SoundEvents.VILLAGER_YES) {
            simulator.onYesSoundPlayed(packet.getPitch());
        } else if (soundEvent == SoundEvents.GENERIC_SPLASH) {
            simulator.onSplashSoundPlayed(packet.getPitch());
        }
    }

    public static void onXpOrbSpawned(ClientboundAddExperienceOrbPacket packet) {
        Villager targetVillager = getVillager();
        if (targetVillager == null) {
            return;
        }

        simulator.onXpOrbSpawned(packet.getValue());
    }

    private static void onTimeSync() {
        long now = System.nanoTime();

        if (Configs.getVillagerManipulation() && clockPos != null && !isNewClock && clockTicksSinceLastTimeSync != 20) {
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

        simulator.simulateTick();

        if (isRunning() && !hasClickedVillager) {
            int millisecondsUntilInteract = simulator.getTicksRemaining() * serverMspt - PingCommand.getLocalPing() + magicMillisecondCorrection;
            if (millisecondsUntilInteract < 200) {
                LocalPlayer oldPlayer = Minecraft.getInstance().player;
                assert oldPlayer != null;
                CUtil.sendAtPreciseTime(
                    System.nanoTime() + millisecondsUntilInteract * 1_000_000L,
                    ServerboundInteractPacket.createInteractionPacket(targetVillager, false, InteractionHand.MAIN_HAND),
                    () -> true,
                    () -> {
                        LocalPlayer player = Minecraft.getInstance().player;
                        if (player == oldPlayer) {
                            player.swing(InteractionHand.MAIN_HAND);
                        }
                    }
                );
                simulator.resetBruteForceState();
                hasClickedVillager = true;
            }
        }
    }

    private static void onDisconnect() {
        if (Relogger.isRelogging) {
            UUID prevVillagerUuid = villagerUuid;
            GlobalPos prevClockPos = clockPos;
            Relogger.relogSuccessTasks.add(() -> {
                villagerUuid = prevVillagerUuid;
                setClockPos(prevClockPos);
            });
        }

        reset();
    }

    private static int[] possibleTicksAhead(Offer[] actualOffers, VillagerRngSimulator.SurroundingOffers surroundingOffers) {
        IntList ticksAhead = new IntArrayList();

        for (int i = 0; i < surroundingOffers.before().size(); i++) {
            Offer[] offers = surroundingOffers.before().get(i);
            if (Arrays.equals(offers, actualOffers)) {
                // we need to adjust by 1 to get it to not be 0 for the last value in `beforeOffers`
                ticksAhead.add((surroundingOffers.before().size() - 1 - i) + 1);
            }
        }

        if (Arrays.equals(surroundingOffers.middle(), actualOffers)) {
            ticksAhead.add(0);
        }

        for (int i = 0; i < surroundingOffers.after().size(); i++) {
            Offer[] offers = surroundingOffers.after().get(i);
            if (Arrays.equals(offers, actualOffers)) {
                // we need to adjust by 1 to get it to not be 0 for the first value in `afterOffers`
                ticksAhead.add(-(i + 1));
            }
        }

        int[] result = ticksAhead.toIntArray();
        ArrayUtils.reverse(result);
        return result;
    }

    public static void onGuiOpened(List<Offer> actualOffersList) {
        final LocalPlayer player = Minecraft.getInstance().player;
        assert player != null;

        Villager villager = getVillager();
        if (villager == null) {
            return;
        }

        if (player.distanceTo(villager) > 2.0) {
            ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.outOfSync.distance").withStyle(ChatFormatting.RED), 100);
            simulator.reset();
            stopRunning();
            return;
        }

        if (isRunning()) {
            int[] possibleTicksAhead = possibleTicksAhead(actualOffersList.toArray(Offer[]::new), surroundingOffers);
            // chop possible ticks ahead into a limited reasonable range
            final int consideredTickRange = 21;
            int lowerIndex = Arrays.binarySearch(possibleTicksAhead, -consideredTickRange / 2);
            if (lowerIndex < 0) {
                lowerIndex = -lowerIndex - 1;
            }
            int upperIndex = Arrays.binarySearch(possibleTicksAhead, consideredTickRange / 2);
            if (upperIndex < 0) {
                upperIndex = -upperIndex - 2;
            }
            possibleTicksAhead = Arrays.copyOfRange(possibleTicksAhead, lowerIndex, upperIndex + 1);

            int prevCorrection = magicMillisecondCorrection;
            if (possibleTicksAhead.length > 0) {
                if (combinedMedianEM.data.size() >= 10) {
                    combinedMedianEM.data.removeFirst();
                }
                DoubleList possibleMillisecondsAhead = new DoubleArrayList(possibleTicksAhead.length);
                for (int ticksAhead : possibleTicksAhead) {
                    possibleMillisecondsAhead.add(ticksAhead * serverMspt + magicMillisecondCorrection);
                }
                combinedMedianEM.data.add(possibleMillisecondsAhead);
                maxTicksBefore = Math.max(maxTicksBefore, -possibleTicksAhead[0]);
                maxTicksAfter = Math.max(maxTicksAfter, possibleTicksAhead[possibleTicksAhead.length - 1]);
                combinedMedianEM.update(serverMspt, consideredTickRange);
                magicMillisecondCorrection = (int) Math.round(combinedMedianEM.getResult());
            }

            if (actualOffersList.contains(targetOffer)) {
                ClientCommandHelper.sendFeedback(Component.translatable("commands.cvillager.success", prevCorrection).withStyle(ChatFormatting.GREEN));
                player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0f, 2.0f);
            } else {
                ClientCommandHelper.sendFeedback(Component.translatable("commands.cvillager.failure", prevCorrection, magicMillisecondCorrection).withStyle(ChatFormatting.RED));
                player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1.0f, 1.0f);
            }

            ClientCommandHelper.addOverlayMessage(Component.empty(), 0);
            simulator.reset();
            stopRunning();
        }
    }

    public static void reset() {
        simulator.reset();
        clockPos = null;
        villagerUuid = null;
        stopRunning();
    }

    public static boolean isRunning() {
        return targetOffer != null;
    }

    public static void stopRunning() {
        targetOffer = null;
    }

    public record Goal(
        String resultString,
        Predicate<ItemStack> result,
        @Nullable String firstString,
        @Nullable Predicate<ItemStack> first,
        @Nullable String secondString,
        @Nullable Predicate<ItemStack> second
    ) {
        public boolean matches(Offer offer) {
            return result.test(offer.result)
                && (first == null || first.test(offer.first))
                && (second == null || (offer.second != null && second.test(offer.second)));
        }

        @Override
        public String toString() {
            if (firstString == null) {
                return resultString;
            } else if (secondString == null) {
                return String.format("%s = %s", firstString, resultString);
            } else {
                return String.format("%s + %s = %s", firstString, secondString, resultString);
            }
        }
    }

    public record Offer(ItemStack first, @Nullable ItemStack second, ItemStack result) {
        public Offer(MerchantOffer offer) {
            this(offer.getBaseCostA(), offer.getItemCostB().map(ItemCost::itemStack).orElse(null), offer.getResult());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Offer(ItemStack offerFirst, ItemStack offerSecond, ItemStack offerResult))) {
                return false;
            }
            return ItemStack.isSameItemSameComponents(this.first, offerFirst) && this.first.getCount() == offerFirst.getCount()
                && (this.second == offerSecond || this.second != null && offerSecond != null && ItemStack.isSameItemSameComponents(this.second, offerSecond) && this.second.getCount() == offerSecond.getCount())
                && ItemStack.isSameItemSameComponents(this.result, offerResult) && this.result.getCount() == offerResult.getCount();
        }

        @Override
        public String toString() {
            if (second == null) {
                return String.format("%s = %s", VillagerCommand.displayText(first, false), VillagerCommand.displayText(result, false));
            } else {
                return String.format("%s + %s = %s", VillagerCommand.displayText(first, false), VillagerCommand.displayText(second, false), VillagerCommand.displayText(result, false));
            }
        }
    }
}
