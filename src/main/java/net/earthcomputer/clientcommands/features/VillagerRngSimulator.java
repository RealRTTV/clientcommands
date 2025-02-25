package net.earthcomputer.clientcommands.features;

import com.demonwav.mcdev.annotations.Translatable;
import com.seedfinding.latticg.math.component.BigFraction;
import com.seedfinding.latticg.math.component.BigMatrix;
import com.seedfinding.latticg.math.component.BigVector;
import com.seedfinding.latticg.math.lattice.enumerate.EnumerateRt;
import com.seedfinding.latticg.math.optimize.Optimize;
import com.seedfinding.mcseed.rand.JRand;
import net.earthcomputer.clientcommands.command.ClientCommandHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.LongStream;

public class VillagerRngSimulator {
    private static final BigMatrix[] LATTICES;
    private static final BigMatrix[] INVERSE_LATTICES;
    private static final BigVector[] OFFSETS;

    private JRand random;
    private long prevRandomSeed = 0;
    private int ambientSoundTime = -80;
    private int prevAmbientSoundTime = -80;
    private boolean madeSound = false;
    private int totalAmbientSounds = 0;
    private int tickCount = 0;
    private int totalTicksWaiting = 0;
    private float firstPitch = Float.NaN;
    private int ticksBetweenSounds = 0;
    private float secondPitch = Float.NaN;
    private long @Nullable [] seedsFromTwoPitches = null;

    static {
        try {
            CompoundTag root = NbtIo.read(new DataInputStream(Objects.requireNonNull(VillagerRngSimulator.class.getResourceAsStream("/villager_lattice_data.nbt"))));
            ListTag lattices = root.getList("lattices", Tag.TAG_LONG_ARRAY);
            LATTICES = new BigMatrix[lattices.size()];
            ListTag latticeInverses = root.getList("lattice_inverses", Tag.TAG_LONG_ARRAY);
            INVERSE_LATTICES = new BigMatrix[lattices.size()];
            ListTag offsets = root.getList("offsets", Tag.TAG_LONG_ARRAY);
            OFFSETS = new BigVector[offsets.size()];
            for (int i = 0; i < lattices.size(); i++) {
                long[] lattice = lattices.getLongArray(i);
                BigMatrix matrix = new BigMatrix(3, 3);
                matrix.set(0, 0, new BigFraction(lattice[0]));
                matrix.set(0, 1, new BigFraction(lattice[1]));
                matrix.set(0, 2, new BigFraction(lattice[2]));
                matrix.set(1, 0, new BigFraction(lattice[3]));
                matrix.set(1, 1, new BigFraction(lattice[4]));
                matrix.set(1, 2, new BigFraction(lattice[5]));
                matrix.set(2, 0, new BigFraction(lattice[6]));
                matrix.set(2, 1, new BigFraction(lattice[7]));
                matrix.set(2, 2, new BigFraction(lattice[8]));
                LATTICES[i] = matrix;
            }
            for (int i = 0; i < latticeInverses.size(); i++) {
                long[] lattice_inverse = latticeInverses.getLongArray(i);
                BigMatrix matrix = new BigMatrix(3, 3);
                matrix.set(0, 0, new BigFraction(lattice_inverse[0], 1L << 48));
                matrix.set(0, 1, new BigFraction(lattice_inverse[1], 1L << 48));
                matrix.set(0, 2, new BigFraction(lattice_inverse[2], 1L << 48));
                matrix.set(1, 0, new BigFraction(lattice_inverse[3], 1L << 48));
                matrix.set(1, 1, new BigFraction(lattice_inverse[4], 1L << 48));
                matrix.set(1, 2, new BigFraction(lattice_inverse[5], 1L << 48));
                matrix.set(2, 0, new BigFraction(lattice_inverse[6], 1L << 48));
                matrix.set(2, 1, new BigFraction(lattice_inverse[7], 1L << 48));
                matrix.set(2, 2, new BigFraction(lattice_inverse[8], 1L << 48));
                INVERSE_LATTICES[i] = matrix;
            }
            for (int i = 0; i < offsets.size(); i++) {
                long[] offset = offsets.getLongArray(i);
                OFFSETS[i] = new BigVector(0, offset[0], offset[1]);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public VillagerRngSimulator(@Nullable JRand random) {
        this.random = random;
    }

    public VillagerRngSimulator copy() {
        VillagerRngSimulator that = new VillagerRngSimulator(random == null ? null : random.copy());
        that.ambientSoundTime = this.ambientSoundTime;
        that.prevAmbientSoundTime = this.prevAmbientSoundTime;
        that.madeSound = this.madeSound;
        that.totalAmbientSounds = this.totalAmbientSounds;
        return that;
    }

    public void simulateTick() {
        // called on receiving clock packet at the beginning of the tick, simulates the rest of the tick

        if (random == null) {
            ambientSoundTime++;
            return;
        }

        prevRandomSeed = random.getSeed();
        prevAmbientSoundTime = ambientSoundTime;

        simulateBaseTick();
        simulateServerAiStep();

        tickCount++;
    }

    private void untick() {
        random.setSeed(prevRandomSeed, false);
        ambientSoundTime = prevAmbientSoundTime;
        tickCount--;
    }

    public int getTicksRemaining() {
        return totalTicksWaiting - tickCount;
    }

    private void simulateBaseTick() {
        // we have the server receiving ambient noise tell us if we have to do this to increment the random, this is so that our ambient sound time is synced up.
        if (random.nextInt(1000) < ambientSoundTime++ && totalAmbientSounds > 0) {
            random.nextFloat();
            random.nextFloat();
            ambientSoundTime = -80;
            madeSound = true;
        } else {
            madeSound = false;
        }
    }

    private void simulateServerAiStep() {
        random.nextInt(100);
    }

    public void updateProgressBar() {
        if (totalTicksWaiting > 0) {
            ClientCommandHelper.updateOverlayProgressBar(tickCount, totalTicksWaiting, 50, 60);
        }
    }

    @Nullable
    public VillagerCracker.Offer anyOffersMatch(VillagerTrades.ItemListing[] listings, Entity trader, Predicate<VillagerCracker.Offer> predicate) {
        if (!isCracked()) {
            return null;
        }

        RandomSource rand = new LegacyRandomSource(random.getSeed() ^ 0x5deece66dL);
        ArrayList<VillagerTrades.ItemListing> newListings = new ArrayList<>(List.of(listings));
        int i = 0;
        while (i < 2 && !newListings.isEmpty()) {
            VillagerTrades.ItemListing listing = newListings.remove(rand.nextInt(newListings.size()));
            MerchantOffer offer = listing.getOffer(trader, rand);
            if (offer != null) {
                VillagerCracker.Offer x = new VillagerCracker.Offer(offer);
                if (predicate.test(x)) {
                    return x;
                } else {
                    i++;
                }
            }
        }
        return null;
    }

    @Nullable
    public VillagerCracker.Offer[] generateOffers(VillagerTrades.ItemListing[] listings, Entity trader) {
        if (!isCracked()) {
            return null;
        }

        VillagerCracker.Offer[] offers = new VillagerCracker.Offer[Math.min(listings.length, 2)];

        RandomSource rand = new LegacyRandomSource(random.getSeed() ^ 0x5deece66dL);
        ArrayList<VillagerTrades.ItemListing> newListings = new ArrayList<>(List.of(listings));

        for (int i = 0; i < offers.length; i++) {
            VillagerTrades.ItemListing listing = newListings.remove(rand.nextInt(newListings.size()));
            MerchantOffer offer = listing.getOffer(trader, rand);
            if (offer != null) {
                offers[i] = new VillagerCracker.Offer(offer);
            }
        }

        return offers;
    }

    public void setTicksUntilInteract(int ticks) {
        tickCount = 0;
        totalTicksWaiting = ticks;
    }

    public CrackedState getCrackedState() {
        if (totalAmbientSounds == 0) {
            return CrackedState.UNCRACKED;
        } else if (totalAmbientSounds > 0 && random == null) {
            return CrackedState.PARTIALLY_CRACKED;
        }

        return CrackedState.CRACKED;
    }

    public boolean isCracked() {
        return getCrackedState() == CrackedState.CRACKED;
    }

    public boolean isAtLeastPartiallyCracked() {
        return getCrackedState() != CrackedState.UNCRACKED;
    }

    public void reset() {
        random = null;
        prevRandomSeed = 0;
        prevAmbientSoundTime = 0;
        totalAmbientSounds = 0;
        tickCount = 0;
        totalTicksWaiting = 0;
        firstPitch = Float.NaN;
        ticksBetweenSounds = 0;
        secondPitch = Float.NaN;
        seedsFromTwoPitches = null;
    }

    public void resetWaitingState() {
        totalTicksWaiting = 0;
        tickCount = 0;
    }

    @Override
    public String toString() {
        return "VillagerRngSimulator[seed=" + (random == null ? "null" : random.getSeed()) + ']';
    }

    public void onAmbientSoundPlayed(float pitch) {
        boolean justReset = false;
        if (totalAmbientSounds == 2 && !madeSound) {
            onBadSetup("ambient");
            justReset = true;
        }

        if (totalAmbientSounds == 0) {
            totalAmbientSounds++;
            firstPitch = pitch;
            ambientSoundTime = -80;
            if (!justReset) {
                ClientCommandHelper.addOverlayMessage(getCrackedState().getMessage(false).withStyle(ChatFormatting.RED), 100);
            }
            return;
        }

        if (totalAmbientSounds == 1) {
            totalAmbientSounds++;
            ticksBetweenSounds = ambientSoundTime - (-80);
            secondPitch = pitch;
            ambientSoundTime = -80;

            if (seedsFromTwoPitches != null) {
                int matchingSeeds = 0;
                long matchingSeed = 0;
                nextSeed: for (long seed : seedsFromTwoPitches) {
                    JRand rand = JRand.ofInternalSeed(seed);
                    rand.nextInt(100);
                    for (int i = -80; i < ticksBetweenSounds - 80 - 1; i++) {
                        if (rand.nextInt(1000) < i) {
                            continue nextSeed;
                        }
                        rand.nextInt(100);
                    }
                    if (rand.nextInt(1000) >= ticksBetweenSounds - 80 - 1) {
                        continue;
                    }
                    float simulatedThirdPitch = (rand.nextFloat() - rand.nextFloat()) * 0.2f + 1.0f;
                    if (simulatedThirdPitch == pitch) {
                        matchingSeeds++;
                        matchingSeed = rand.getSeed();
                    }
                }
                seedsFromTwoPitches = null;
                if (matchingSeeds == 1) {
                    random = JRand.ofInternalSeed(matchingSeed);
                    random.nextInt(100);
                    ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.crack.success", Long.toHexString(matchingSeed)).withStyle(ChatFormatting.GREEN), 100);
                    return;
                }
            }

            long[] seeds = crackSeed();
            if (seeds.length == 1) {
                random = JRand.ofInternalSeed(seeds[0]);
                random.nextInt(100);
                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.crack.success", Long.toHexString(seeds[0])).withStyle(ChatFormatting.GREEN), 100);
            } else {
                totalAmbientSounds = 1;
                firstPitch = pitch;
                secondPitch = Float.NaN;
                seedsFromTwoPitches = seeds.length > 0 ? seeds : null;
                ambientSoundTime = -80;
                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.crack.failed", seeds.length).withStyle(ChatFormatting.RED), 100);
            }
        }
    }

    public void onBadSetup(@Translatable(prefix = "villagerManip.reset.") String reason) {
        ClientCommandHelper.sendError(Component.translatable("villagerManip.reset", Component.translatable("villagerManip.reset." + reason)));
        reset();
    }

    public void onNoSoundPlayed(float pitch, boolean fromGuiInteract) {
        // the last received action before the next tick's clock
        // played both when interacting with a villager without a profession and when using the villager gui

        if (random != null) {
            if (fromGuiInteract) {
                ambientSoundTime = -80;
            }
            float simulatedPitch = (random.nextFloat() - random.nextFloat()) * 0.2f + 1.0f;
            if (pitch != simulatedPitch) {
                onBadSetup("no");
            } else {
                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.inSync", Long.toHexString(random.getSeed())).withStyle(ChatFormatting.GREEN), 100);
            }
        }
    }

    public void onYesSoundPlayed(float pitch) {
        // the last received action before the next tick's clock
        // played when using the villager gui

        if (random != null) {
            ambientSoundTime = -80;
            float simulatedPitch = (random.nextFloat() - random.nextFloat()) * 0.2f + 1.0f;
            if (pitch != simulatedPitch) {
                onBadSetup("yes");
            } else {
                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.inSync", Long.toHexString(random.getSeed())).withStyle(ChatFormatting.GREEN), 100);
            }
        }
    }

    public void onSplashSoundPlayed(float pitch) {
        // the first received action after this tick's clock

        if (random != null) {
            // simulateTick() was already called for this tick assuming no splash happened, so revert it and rerun it with the splash
            untick();

            float simulatedPitch = (random.nextFloat() - random.nextFloat()) * 0.4f + 1.0f;
            if (pitch == simulatedPitch) {
                int iterations = Mth.ceil(1.0f + EntityType.VILLAGER.getDimensions().width() * 20.0f);
                random.advance(iterations * 10L);
                simulateTick();

                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.inSync", Long.toHexString(random.getSeed())).withStyle(ChatFormatting.GREEN), 100);
            } else {
                onBadSetup("splash");
            }
        }
    }

    public void onXpOrbSpawned(int value) {
        // the last received action before the next tick's clock

        if (random != null) {
            ambientSoundTime = -80;
            int simulatedValue = 3 + this.random.nextInt(4);
            boolean leveledUp = value > 3 + 3;
            if (leveledUp) simulatedValue += 5;
            if (value != simulatedValue) {
                onBadSetup("xpOrb");
            } else {
                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.inSync", Long.toHexString(random.getSeed())).withStyle(ChatFormatting.GREEN), 100);
            }
        }
    }

    @Nullable
    public VillagerRngSimulator.BruteForceResult bruteForceOffers(VillagerTrades.ItemListing[] listings, int minTicks, int maxTicks, Predicate<VillagerCracker.Offer> predicate) {
        Villager targetVillager = VillagerCracker.getVillager();
        if (targetVillager != null && isCracked()) {
            VillagerRngSimulator branchedSimulator = this.copy();
            int ticksPassed = 0;

            for (int i = 0; i < minTicks; i++) {
                branchedSimulator.simulateTick();
                ticksPassed++;
            }

            while (ticksPassed < maxTicks) {
                VillagerRngSimulator offerSimulator = branchedSimulator.copy();
                offerSimulator.simulateTick();
                ticksPassed++;
                VillagerCracker.Offer offer = offerSimulator.anyOffersMatch(listings, targetVillager, predicate);
                if (offer != null) {
                    // we do the calls before this ticks processing so that since with 0ms ping, the server reads it next tick
                    return new BruteForceResult(ticksPassed, offer, offerSimulator.random.getSeed());
                }
                branchedSimulator.simulateTick();
            }
        }

        return null;
    }

    @Nullable
    public SurroundingOffers generateSurroundingOffers(VillagerTrades.ItemListing[] listings, int centerTicks, int radius) {
        Villager targetVillager = VillagerCracker.getVillager();

        if (targetVillager == null || !isCracked()) {
            return null;
        }

        List<VillagerCracker.Offer[]> before = new ArrayList<>(radius);
        List<VillagerCracker.Offer[]> after = new ArrayList<>(radius);

        VillagerRngSimulator branchedSimulator = this.copy();
        for (int i = 0; i < Math.max(0, centerTicks - radius); i++) {
            branchedSimulator.simulateTick();
        }
        for (int i = Math.max(0, centerTicks - radius); i < centerTicks - 1; i++) {
            branchedSimulator.simulateTick();
            before.add(branchedSimulator.generateOffers(listings, targetVillager));
        }
        branchedSimulator.simulateTick();
        VillagerCracker.Offer[] middle = branchedSimulator.generateOffers(listings, targetVillager);
        for (int i = 0; i < radius; i++) {
            branchedSimulator.simulateTick();
            after.add(branchedSimulator.generateOffers(listings, targetVillager));
        }

        return new SurroundingOffers(before, middle, after);
    }

    public long[] crackSeed() {
        if (!(80 <= ticksBetweenSounds && ticksBetweenSounds - 80 < LATTICES.length)) {
            return new long[0];
        }

        BigMatrix lattice = LATTICES[ticksBetweenSounds - 80];
        BigMatrix inverseLattice = INVERSE_LATTICES[ticksBetweenSounds - 80];
        BigVector offset = OFFSETS[ticksBetweenSounds - 80];

        float firstMin = Math.max(-1.0f + 0x1.0p-24f, (firstPitch - 1.0f) / 0.2f - VillagerCracker.MAX_ERROR);
        float firstMax = Math.min(1.0f - 0x1.0p-24f, (firstPitch - 1.0f) / 0.2f + VillagerCracker.MAX_ERROR);
        float secondMin = Math.max(-1.0f + 0x1.0p-24f, (secondPitch - 1.0f) / 0.2f - VillagerCracker.MAX_ERROR);
        float secondMax = Math.min(1.0f - 0x1.0p-24f, (secondPitch - 1.0f) / 0.2f + VillagerCracker.MAX_ERROR);

        firstMax = Math.nextUp(firstMax);
        secondMax = Math.nextUp(secondMax);

        long firstMinLong = (long) Math.ceil(firstMin * 0x1.0p24f);
        long firstMaxLong = (long) Math.ceil(firstMax * 0x1.0p24f) - 1;
        long secondMinLong = (long) Math.ceil(secondMin * 0x1.0p24f);
        long secondMaxLong = (long) Math.ceil(secondMax * 0x1.0p24f) - 1;

        long firstMinSeedDiff = (firstMinLong << 24) - 0xFFFFFF;
        long firstMaxSeedDiff = (firstMaxLong << 24) + 0xFFFFFF;
        long secondMinSeedDiff = (secondMinLong << 24) - 0xFFFFFF;
        long secondMaxSeedDiff = (secondMaxLong << 24) + 0xFFFFFF;

        long firstCombinationModMin = firstMinSeedDiff & 0xFFFFFFFFFFFFL;
        long firstCombinationModMax = firstMaxSeedDiff & 0xFFFFFFFFFFFFL;
        long secondCombinationModMin = secondMinSeedDiff & 0xFFFFFFFFFFFFL;
        long secondCombinationModMax = secondMaxSeedDiff & 0xFFFFFFFFFFFFL;

        firstCombinationModMax = firstCombinationModMax < firstCombinationModMin ? firstCombinationModMax + (1L << 48) : firstCombinationModMax;
        secondCombinationModMax = secondCombinationModMax < secondCombinationModMin ? secondCombinationModMax + (1L << 48) : secondCombinationModMax;

        Optimize optimize = Optimize.Builder.ofSize(3)
            .withLowerBound(0, 0)
            .withUpperBound(0, 0xFFFFFFFFFFFFL)
            .withLowerBound(1, firstCombinationModMin)
            .withUpperBound(1, firstCombinationModMax)
            .withLowerBound(2, secondCombinationModMin)
            .withUpperBound(2, secondCombinationModMax)
            .build();

        return EnumerateRt.enumerate(lattice, offset, optimize, inverseLattice, inverseLattice.multiply(offset)).mapToLong(vec -> vec.get(0).getNumerator().longValue() & ((1L << 48) - 1)).flatMap(seed -> {
            JRand rand = JRand.ofInternalSeed(seed);
            float simulatedFirstPitch = (rand.nextFloat() - rand.nextFloat()) * 0.2f + 1.0f;
            rand.nextInt(100);
            for (int i = -80; i < ticksBetweenSounds - 80 - 1; i++) {
                if (rand.nextInt(1000) < i) {
                    return LongStream.empty();
                }
                rand.nextInt(100);
            }
            if (rand.nextInt(1000) >= ticksBetweenSounds - 80 - 1) {
                return LongStream.empty();
            }
            float simulatedSecondPitch = (rand.nextFloat() - rand.nextFloat()) * 0.2f + 1.0f;
            if (simulatedFirstPitch == firstPitch && simulatedSecondPitch == secondPitch) {
                return LongStream.of(rand.getSeed());
            } else {
                return LongStream.empty();
            }
        }).toArray();
    }

    public enum CrackedState {
        UNCRACKED,
        PARTIALLY_CRACKED,
        CRACKED;

        public MutableComponent getMessage(boolean addColor) {
            return switch (this) {
                case UNCRACKED -> Component.translatable("commands.cvillager.uncracked").withStyle(addColor ? ChatFormatting.RED : ChatFormatting.RESET);
                case PARTIALLY_CRACKED -> Component.translatable("commands.cvillager.partiallyCracked").withStyle(addColor ? ChatFormatting.RED : ChatFormatting.RESET);
                case CRACKED -> Component.translatable("commands.cvillager.inSync").withStyle(addColor ? ChatFormatting.GREEN : ChatFormatting.RESET);
            };
        }
    }

    public record BruteForceResult(int ticksPassed, VillagerCracker.Offer offer, long seed) {
    }

    public record SurroundingOffers(List<VillagerCracker.Offer[]> before, VillagerCracker.Offer[] middle, List<VillagerCracker.Offer[]> after) {
    }
}
