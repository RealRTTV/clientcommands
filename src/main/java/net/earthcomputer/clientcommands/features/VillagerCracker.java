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
import net.earthcomputer.clientcommands.util.BuildInfo;
import net.earthcomputer.clientcommands.util.CUtil;
import net.earthcomputer.clientcommands.util.CombinedMedianEM;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
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
    public static boolean hasSentInteractionPackets = false;
    public static boolean hasQueuedInteractionPackets = false;
    public static boolean isFirstLevelCrack = true;

    private static long lastTimeSyncTime;
    public static int serverMspt = SharedConstants.MILLIS_PER_TICK;
    public static int magicMillisecondCorrection = 25;
    public static int maxTicksBefore = 10;
    public static int maxTicksAfter = 10;
    public static CombinedMedianEM combinedMedianEM = new CombinedMedianEM();

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

        if (level != null && level.getDayTime() % 24000 < 12000) {
            simulator.onBadSetup("day");
            ClientCommandHelper.sendHelp(Component.translatable("villagerManip.help.day"));
        }

        if (villager != null) {
            cachedVillager = new WeakReference<>(villager);
            villagerUuid = villager.getUUID();
        } else {
            reset();
        }
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
        } else if (BuiltInRegistries.SOUND_EVENT.getKey(soundEvent).getPath().startsWith("item.armor.equip_")) {
            simulator.onBadSetup("itemEquipped");
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

        if (Configs.villagerManipulation && clockPos != null && !isNewClock && clockTicksSinceLastTimeSync != 20) {
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

        final Villager targetVillager = getVillager();
        if (targetVillager == null) {
            return;
        }

        if (simulator.isAtLeastPartiallyCracked()) {
            checkVillagerStateSetup();
        }

        simulator.simulateTick();

        if (isRunning() && checkVillagerWaitingStateSetup() && !hasQueuedInteractionPackets && simulator.isCracked()) {
            trySendInteraction();
        }
    }

    private static void trySendInteraction() {
        final Minecraft mc = Minecraft.getInstance();
        final Villager targetVillager = getVillager();
        final long nanoTime = System.nanoTime();
        int millisecondsUntilInteract = simulator.getTicksRemaining() * serverMspt - PingCommand.getLocalPing() + magicMillisecondCorrection;
        if (millisecondsUntilInteract < 200) {
            LocalPlayer oldPlayer = mc.player;
            assert oldPlayer != null;

            if (isFirstLevelCrack) {
                CUtil.sendAtPreciseTime(
                    nanoTime + millisecondsUntilInteract * 1_000_000L,
                    ServerboundInteractPacket.createInteractionPacket(targetVillager, false, InteractionHand.MAIN_HAND),
                    VillagerCracker::isRunning,
                    () -> {
                        LocalPlayer player = mc.player;
                        if (player == oldPlayer) {
                            player.swing(InteractionHand.MAIN_HAND);
                        }
                        hasSentInteractionPackets = true;
                    }
                );
            } else {
                // todo: could potentially still break if a "hrmm" happens between this and the interaction
                if (!(mc.screen instanceof MerchantScreen screen)) {
                    simulator.onBadSetup("wrongScreen");
                    return;
                }

                screen.slotClicked(screen.getMenu().slots.get(2), 2, 0, ClickType.QUICK_MOVE);

                CUtil.sendAtPreciseTime(
                    nanoTime + millisecondsUntilInteract * 1_000_000L,
                    new ServerboundContainerClosePacket(oldPlayer.containerMenu.containerId),
                    VillagerCracker::isRunning,
                    () -> {
                        LocalPlayer player = mc.player;
                        player.clientSideCloseContainer();
                        hasSentInteractionPackets = true;
                    }
                );

                // debug
                CUtil.sendAtPreciseTime(
                    nanoTime + (millisecondsUntilInteract + 41L * serverMspt) * 1_000_000L,
                    ServerboundInteractPacket.createInteractionPacket(targetVillager, false, InteractionHand.MAIN_HAND),
                    VillagerCracker::isRunning,
                    () -> {
                        LocalPlayer player = mc.player;
                        if (player == oldPlayer) {
                            player.swing(InteractionHand.MAIN_HAND);
                        }
                    }
                );
            }

            simulator.resetWaitingState();
            hasQueuedInteractionPackets = true;
        }
    }

    private static boolean checkVillagerStateSetup() {
        final Minecraft mc = Minecraft.getInstance();
        final Villager targetVillager = getVillager();

        if (targetVillager == null) {
            return false;
        }

        if (!isResting(targetVillager.level().dayTime())) {
            simulator.onBadSetup("day");
            ClientCommandHelper.sendHelp(Component.translatable("villagerManip.help.day"));
            return false;
        } else if (targetVillager.isInWater() && targetVillager.getFluidHeight(FluidTags.WATER) > targetVillager.getFluidJumpThreshold() || targetVillager.isInLava()) {
            simulator.onBadSetup("swim");
            return false;
        } else if (!targetVillager.getActiveEffects().isEmpty()) {
            simulator.onBadSetup("potion");
            return false;
        } else if (!mc.player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            simulator.onBadSetup("itemInMainHand");
            return false;
        } else {
            Level level = targetVillager.level();
            Vec3 pos = targetVillager.position();
            BlockPos blockPos = targetVillager.blockPosition();

            int villagersNearVillager = level.getEntities(EntityTypeTest.forExactClass(Villager.class), AABB.ofSize(pos, 10.0, 10.0, 10.0), entity -> entity.position().distanceToSqr(pos) <= 5.0 * 5.0).size();
            if (villagersNearVillager > 1) {
                simulator.onBadSetup("gossip");
                return false;
            }

            List<BlockPos> bedHeadPositions = BlockPos.betweenClosedStream(blockPos.offset(-16, -16, -16), blockPos.offset(16, 16, 16)).map(BlockPos::new).filter(p -> p.distSqr(blockPos) <= 16.0 * 16.0).filter(p -> level.getBlockState(p).is(BlockTags.BEDS) && level.getBlockState(p).getValue(BedBlock.OCCUPIED) == Boolean.FALSE && level.getBlockState(p).getValue(BedBlock.PART) == BedPart.HEAD).toList();
            if (bedHeadPositions.size() != 1) {
                simulator.onBadSetup("invalidBedPosition");
                sendInvalidSetupHelp();
                return false;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos trapdoorPos = blockPos.relative(direction).above();
                BlockState trapdoorPosState = level.getBlockState(trapdoorPos);
                if (!(trapdoorPosState.is(BlockTags.TRAPDOORS) && trapdoorPosState.getValue(TrapDoorBlock.HALF) == Half.TOP && trapdoorPosState.getValue(TrapDoorBlock.HALF) == Half.TOP && trapdoorPosState.getValue(TrapDoorBlock.OPEN) == Boolean.FALSE)) {
                    simulator.onBadSetup("invalidCage");
                    sendInvalidSetupHelp();
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean checkVillagerWaitingStateSetup() {
        if (!Configs.villagerManipulation) {
            simulator.onBadSetup("manipInactive");
            stopRunning();
            return false;
        }

        Villager targetVillager = VillagerCracker.getVillager();
        if (targetVillager == null || !VillagerCracker.simulator.isCracked()) {
            simulator.onBadSetup("uncrackedMisc");
            stopRunning();
            return false;
        }

        VillagerProfession profession = targetVillager.getVillagerData().getProfession();
        if (profession == VillagerProfession.NONE) {
            simulator.onBadSetup("professionLost");
            stopRunning();
            return false;
        }

        return true;
    }

    private static void sendInvalidSetupHelp() {
        ClientCommandHelper.sendHelp(
            Component.translatable("villagerManip.help.setup",
                Component.translatable("villagerManip.help.setup.link").withStyle(style -> style
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(
                        ClickEvent.Action.OPEN_URL,
                        "https://github.com/Earthcomputer/clientcommands/blob/%s/docs/villager_rng_setup.png?raw=true".formatted(BuildInfo.INSTANCE.shortCommitHash(10))
                    ))
                )
            )
        );
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

        // potentially not required?
        if (player.distanceTo(villager) > 2.0) {
            simulator.onBadSetup("distance");
            stopRunning();
            return;
        }

        if ((VillagerCracker.isFirstLevelCrack && isRunning()) || (!VillagerCracker.isFirstLevelCrack && VillagerCracker.hasSentInteractionPackets)) {
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

            simulator.reset();
            stopRunning();
            hasQueuedInteractionPackets = false;
            hasSentInteractionPackets = false;
            isFirstLevelCrack = true;
        }
    }

    public static void reset() {
        simulator.reset();
        isFirstLevelCrack = true;
        hasSentInteractionPackets = false;
        hasQueuedInteractionPackets = false;
        clockPos = null;
        villagerUuid = null;
        cachedVillager = new WeakReference<>(null);
        stopRunning();
    }

    public static boolean isRunning() {
        return targetOffer != null;
    }

    public static void stopRunning() {
        targetOffer = null;
    }

    public static boolean isResting(long dayTime) {
        long timeOfDay = dayTime % 24_000;
        return timeOfDay < 10 || timeOfDay >= 12_000;
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
