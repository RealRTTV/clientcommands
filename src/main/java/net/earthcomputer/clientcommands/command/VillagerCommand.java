package net.earthcomputer.clientcommands.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.earthcomputer.clientcommands.Configs;
import net.earthcomputer.clientcommands.command.arguments.ClientItemPredicateArgument;
import net.earthcomputer.clientcommands.command.arguments.ItemAndEnchantmentsPredicateArgument;
import net.earthcomputer.clientcommands.command.arguments.WithStringArgument;
import net.earthcomputer.clientcommands.features.VillagerCracker;
import net.earthcomputer.clientcommands.features.VillagerRngSimulator;
import net.earthcomputer.clientcommands.task.SimpleTask;
import net.earthcomputer.clientcommands.task.TaskManager;
import net.earthcomputer.clientcommands.util.CUtil;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

import static com.mojang.brigadier.arguments.IntegerArgumentType.*;
import static dev.xpple.clientarguments.arguments.CBlockPosArgument.*;
import static dev.xpple.clientarguments.arguments.CEntityArgument.*;
import static dev.xpple.clientarguments.arguments.CRangeArgument.*;
import static net.earthcomputer.clientcommands.command.ClientCommandHelper.*;
import static net.earthcomputer.clientcommands.command.arguments.ClientItemPredicateArgument.*;
import static net.earthcomputer.clientcommands.command.arguments.ItemAndEnchantmentsPredicateArgument.*;
import static net.earthcomputer.clientcommands.command.arguments.WithStringArgument.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

public class VillagerCommand {
    private static final SimpleCommandExceptionType NOT_A_VILLAGER_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cvillager.notAVillager"));
    private static final SimpleCommandExceptionType NO_CRACKED_VILLAGER_PRESENT_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cvillager.noCrackedVillagerPresent"));
    private static final SimpleCommandExceptionType NO_PROFESSION_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cvillager.noProfession"));
    private static final SimpleCommandExceptionType NOT_LEVEL_1_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cvillager.notLevel1"));
    private static final SimpleCommandExceptionType NO_GOALS_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cvillager.listGoals.noGoals"));
    private static final SimpleCommandExceptionType ALREADY_RUNNING_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cvillager.alreadyRunning"));
    private static final Dynamic2CommandExceptionType INVALID_GOAL_INDEX_EXCEPTION = new Dynamic2CommandExceptionType((index, length) -> Component.translatable("commands.cvillager.removeGoal.invalidIndex", index, length));
    private static final Dynamic2CommandExceptionType ITEM_OVERSTACKED_EXCEPTION = new Dynamic2CommandExceptionType((item, stackSize) -> Component.translatable("arguments.item.overstacked", item, stackSize));
    private static final SimpleCommandExceptionType NEED_VILLAGER_MANIPULATION_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cvillager.needVillagerManipulation")
        .withStyle(ChatFormatting.RED)
        .append(" ")
        .append(getCommandTextComponent("commands.client.enable", "/cconfig clientcommands villagerManipulation set true")));

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        dispatcher.register(literal("cvillager")
            .then(literal("add-goal")
                .then(argument("result", withString(clientItemPredicate(context)))
                    .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ClientItemPredicateArgument.ClientItemPredicate.class), MinMaxBounds.Ints.ANY, null, MinMaxBounds.Ints.ANY, null, MinMaxBounds.Ints.ANY))
                    .then(argument("result-count", intRange())
                        .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "result-count"), null, MinMaxBounds.Ints.ANY, null, MinMaxBounds.Ints.ANY))
                        .then(argument("input", withString(clientItemPredicate(context)))
                            .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "result-count"), getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), MinMaxBounds.Ints.ANY, null, MinMaxBounds.Ints.ANY))
                            .then(argument("input-count", intRange())
                                .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "result-count"), getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "input-count"), null, MinMaxBounds.Ints.ANY))
                                .then(argument("second-input", withString(clientItemPredicate(context)))
                                    .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "result-count"), getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "input-count"), getWithString(ctx, "second-input", ClientItemPredicateArgument.ClientItemPredicate.class), MinMaxBounds.Ints.ANY))
                                    .then(argument("second-input-count", intRange())
                                        .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "result-count"), getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "input-count"), getWithString(ctx, "second-input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "second-input-count"))))))))))
            .then(literal("add-enchanted-goal")
                .then(argument("result", withString(itemAndEnchantmentsPredicate(context).withSuffix("from").withEnchantmentPredicate((item, ench) -> ench.is(EnchantmentTags.TRADEABLE))))
                    .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ItemAndEnchantmentsPredicateArgument.ItemAndEnchantmentsPredicate.class).map(ClientItemPredicateArgument.EnchantedItemPredicate::new), MinMaxBounds.Ints.ANY, null, MinMaxBounds.Ints.ANY, null, MinMaxBounds.Ints.ANY))
                    .then(argument("input", withString(clientItemPredicate(context)))
                        .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ItemAndEnchantmentsPredicateArgument.ItemAndEnchantmentsPredicate.class).map(ClientItemPredicateArgument.EnchantedItemPredicate::new), MinMaxBounds.Ints.ANY, getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), MinMaxBounds.Ints.ANY, null, MinMaxBounds.Ints.ANY))
                        .then(argument("input-count", intRange())
                            .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ItemAndEnchantmentsPredicateArgument.ItemAndEnchantmentsPredicate.class).map(ClientItemPredicateArgument.EnchantedItemPredicate::new), MinMaxBounds.Ints.ANY, getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "input-count"), null, MinMaxBounds.Ints.ANY))
                            .then(argument("second-input", withString(clientItemPredicate(context)))
                                .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ItemAndEnchantmentsPredicateArgument.ItemAndEnchantmentsPredicate.class).map(ClientItemPredicateArgument.EnchantedItemPredicate::new), MinMaxBounds.Ints.ANY, getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "input-count"), getWithString(ctx, "second-input", ClientItemPredicateArgument.ClientItemPredicate.class), MinMaxBounds.Ints.ANY))
                                .then(argument("second-input-count", intRange())
                                    .executes(ctx -> addGoal(ctx.getSource(), getWithString(ctx, "result", ItemAndEnchantmentsPredicateArgument.ItemAndEnchantmentsPredicate.class).map(ClientItemPredicateArgument.EnchantedItemPredicate::new), MinMaxBounds.Ints.ANY, getWithString(ctx, "input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "input-count"), getWithString(ctx, "second-input", ClientItemPredicateArgument.ClientItemPredicate.class), Ints.getRangeArgument(ctx, "second-input-count")))))))))
            .then(literal("list-goals")
                .executes(ctx -> listGoals(ctx.getSource())))
            .then(literal("remove-goal")
                .then(argument("index", integer(1))
                    .executes(ctx -> removeGoal(ctx.getSource(), getInteger(ctx, "index")))))
            .then(literal("target")
                .executes(ctx -> setVillagerTarget(null))
                .then(argument("entity", entity())
                    .executes(ctx -> setVillagerTarget(getEntity(ctx, "entity")))))
            .then(literal("clock")
                .executes(ctx -> getClockPos())
                .then(argument("pos", blockPos())
                    .executes(ctx -> setClockPos(ctx.getSource(), getBlockPos(ctx, "pos")))))
            .then(literal("start")
                .executes(ctx -> start(false))
                .then(literal("first-level")
                    .executes(ctx -> start(false)))
                .then(literal("next-level")
                    .executes(ctx -> start(true)))));
    }

    private static int addGoal(
        FabricClientCommandSource ctx,
        Result<ClientItemPredicateArgument.ClientItemPredicate> result,
        MinMaxBounds.Ints resultCount,
        @Nullable WithStringArgument.Result<ClientItemPredicateArgument.ClientItemPredicate> first,
        MinMaxBounds.Ints firstCount,
        @Nullable WithStringArgument.Result<ClientItemPredicateArgument.ClientItemPredicate> second,
        MinMaxBounds.Ints secondCount
    ) throws CommandSyntaxException {
        checkStackSize(result, resultCount);
        if (first != null) {
            checkStackSize(first, firstCount);
        }
        if (second != null) {
            checkStackSize(second, secondCount);
        }

        if (!Configs.getVillagerManipulation()) {
            throw NEED_VILLAGER_MANIPULATION_EXCEPTION.create();
        }

        String firstString = first == null ? null : first.string() + " " + CUtil.boundsToString(firstCount);
        String secondString = second == null ? null : second.string() + " " + CUtil.boundsToString(secondCount);
        String resultString = (result.string().endsWith(" from") ? result.string().substring(0, result.string().length() - " from".length()) : result.string()) + " " + CUtil.boundsToString(resultCount);

        VillagerCracker.goals.add(new VillagerCracker.Goal(
            resultString,
            item -> result.value().test(item) && resultCount.matches(item.getCount()),

            firstString,
            first == null ? null : item -> first.value().test(item) && firstCount.matches(item.getCount()),

            secondString,
            second == null ? null : item -> second.value().test(item) && secondCount.matches(item.getCount())
        ));

        ctx.sendFeedback(Component.translatable("commands.cvillager.goalAdded"));
        return Command.SINGLE_SUCCESS;
    }

    private static void checkStackSize(WithStringArgument.Result<ClientItemPredicateArgument.ClientItemPredicate> itemPredicate, MinMaxBounds.Ints count) throws CommandSyntaxException {
        int maxCount = itemPredicate.value().getPossibleItems().stream().mapToInt(Item::getDefaultMaxStackSize).max().orElse(Item.DEFAULT_MAX_STACK_SIZE);
        if (count.min().isPresent() && count.min().get() > maxCount) {
            throw ITEM_OVERSTACKED_EXCEPTION.create(itemPredicate.string(), maxCount);
        }
        if (count.max().isPresent() && count.max().get() > maxCount) {
            throw ITEM_OVERSTACKED_EXCEPTION.create(itemPredicate.string(), maxCount);
        }
    }

    private static int listGoals(FabricClientCommandSource source) throws CommandSyntaxException {
        if (!Configs.getVillagerManipulation()) {
            throw NEED_VILLAGER_MANIPULATION_EXCEPTION.create();
        }

        if (VillagerCracker.goals.isEmpty()) {
            source.sendFeedback(Component.translatable("commands.cvillager.listGoals.noGoals").withStyle(style -> style.withColor(ChatFormatting.RED)));
        } else {
            source.sendFeedback(Component.translatable("commands.cvillager.listGoals.success", VillagerCracker.goals.size()));
            for (int i = 0; i < VillagerCracker.goals.size(); i++) {
                VillagerCracker.Goal goal = VillagerCracker.goals.get(i);
                source.sendFeedback(Component.literal((i + 1) + ": " + goal.toString()));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int removeGoal(FabricClientCommandSource source, int index) throws CommandSyntaxException {
        if (!Configs.getVillagerManipulation()) {
            throw NEED_VILLAGER_MANIPULATION_EXCEPTION.create();
        }

        index = index - 1;
        if (index < VillagerCracker.goals.size()) {
            VillagerCracker.Goal goal = VillagerCracker.goals.remove(index);
            source.sendFeedback(Component.translatable("commands.cvillager.removeGoal.success", goal.toString()));
        } else {
            throw INVALID_GOAL_INDEX_EXCEPTION.create(index + 1, VillagerCracker.goals.size());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setVillagerTarget(@Nullable Entity target) throws CommandSyntaxException {
        if (!Configs.getVillagerManipulation()) {
            throw NEED_VILLAGER_MANIPULATION_EXCEPTION.create();
        }

        if (target instanceof Villager villager) {
            VillagerCracker.setTargetVillager(villager);
            ClientCommandHelper.sendFeedback("commands.cvillager.target.set");
        } else if (target == null) {
            VillagerCracker.setTargetVillager(null);
            ClientCommandHelper.sendFeedback("commands.cvillager.target.cleared");
        } else {
            throw NOT_A_VILLAGER_EXCEPTION.create();
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int getClockPos() throws CommandSyntaxException {
        if (!Configs.getVillagerManipulation()) {
            throw NEED_VILLAGER_MANIPULATION_EXCEPTION.create();
        }

        GlobalPos pos = VillagerCracker.getClockPos();
        if (pos == null) {
            ClientCommandHelper.sendFeedback("commands.cvillager.clock.cleared");
        } else {
            ClientCommandHelper.sendFeedback("commands.cvillager.clock.set", pos.pos().getX(), pos.pos().getY(), pos.pos().getZ(), String.valueOf(pos.dimension().location()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setClockPos(FabricClientCommandSource ctx, BlockPos pos) throws CommandSyntaxException {
        if (!Configs.getVillagerManipulation()) {
            throw NEED_VILLAGER_MANIPULATION_EXCEPTION.create();
        }

        ResourceKey<Level> dimension = ctx.getWorld().dimension();
        VillagerCracker.setClockPos(pos == null ? null : new GlobalPos(dimension, pos));
        if (pos == null) {
            ClientCommandHelper.sendFeedback("commands.cvillager.clock.set.cleared");
        } else {
            ClientCommandHelper.sendFeedback("commands.cvillager.clock.set", pos.getX(), pos.getY(), pos.getZ(), String.valueOf(dimension.location()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int start(boolean levelUp) throws CommandSyntaxException {
        if (!Configs.getVillagerManipulation()) {
            throw NEED_VILLAGER_MANIPULATION_EXCEPTION.create();
        }

        Villager targetVillager = VillagerCracker.getVillager();

        if (VillagerCracker.goals.isEmpty()) {
            throw NO_GOALS_EXCEPTION.create();
        }

        if (targetVillager == null || !VillagerCracker.simulator.isCracked()) {
            throw NO_CRACKED_VILLAGER_PRESENT_EXCEPTION.create();
        }

        VillagerProfession profession = targetVillager.getVillagerData().getProfession();
        if (profession == VillagerProfession.NONE) {
            throw NO_PROFESSION_EXCEPTION.create();
        }

        if (VillagerCracker.isRunning()) {
            throw ALREADY_RUNNING_EXCEPTION.create();
        }

        int currentLevel = targetVillager.getVillagerData().getLevel();
        if (!levelUp && currentLevel != 1) {
            throw NOT_LEVEL_1_EXCEPTION.create();
        }

        int crackedLevel = levelUp ? currentLevel + 1 : currentLevel;

        VillagerTrades.ItemListing[] listings = VillagerTrades.TRADES.get(profession).getOrDefault(crackedLevel, new VillagerTrades.ItemListing[0]);
        int adjustmentTicks = levelUp ? -40 : 0;
        VillagerRngSimulator.BruteForceResult result = VillagerCracker.simulator.bruteForceOffers(listings, levelUp ? 240 : 10, Configs.maxVillagerManipulationWaitTicks, offer -> VillagerCracker.goals.stream().anyMatch(goal -> goal.matches(offer)));
        if (result == null) {
            ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.bruteForce.failed", Configs.maxVillagerManipulationWaitTicks).withStyle(ChatFormatting.RED), 100);
            return Command.SINGLE_SUCCESS;
        }
        VillagerRngSimulator.SurroundingOffers surroundingOffers = VillagerCracker.simulator.generateSurroundingOffers(listings, result.ticksPassed(), 1000);
        assert surroundingOffers != null;
        int ticks = result.ticksPassed() + adjustmentTicks;
        VillagerCracker.Offer offer = result.offer();
        String price;
        if (offer.second() == null) {
            price = displayText(offer.first(), false);
        } else {
            price = displayText(offer.first(), false) + " + " + displayText(offer.second(), false);
        }
        ClientCommandHelper.sendFeedback(Component.translatable("commands.cvillager.bruteForce.success", displayText(offer.result(), false), price, ticks).withStyle(ChatFormatting.GREEN));
        VillagerCracker.targetOffer = offer;
        VillagerCracker.surroundingOffers = surroundingOffers;
        VillagerCracker.hasClickedVillager = false;
        VillagerCracker.simulator.setTicksUntilInteract(ticks);
        TaskManager.addTask("cvillagerWaiting", new SimpleTask() {
            @Override
            public boolean condition() {
                return VillagerCracker.isRunning();
            }

            @Override
            protected void onTick() {
                VillagerCracker.simulator.updateProgressBar();
            }

            @Override
            public void onCompleted() {
                // check if `onCompleted` was cancelled
                if (condition()) {
                    VillagerCracker.stopRunning();
                }
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    public static String displayText(ItemStack stack, boolean hideCount) {
        String quantityPrefix = hideCount || stack.getCount() == 1 ? "" : stack.getCount() + " ";
        List<Component> lines = stack.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL);
        String itemDescription = lines.stream().skip(1).map(Component::getString).collect(Collectors.joining(", "));
        if (lines.size() == 1) {
            return quantityPrefix + lines.getFirst().getString();
        } else {
            return quantityPrefix + lines.getFirst().getString() + " (" + itemDescription + ")";
        }
    }
}
