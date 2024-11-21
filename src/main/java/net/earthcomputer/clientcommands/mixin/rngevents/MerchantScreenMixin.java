package net.earthcomputer.clientcommands.mixin.rngevents;

import net.earthcomputer.clientcommands.Configs;
import net.earthcomputer.clientcommands.command.ClientCommandHelper;
import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.features.VillagerCracker;
import net.earthcomputer.clientcommands.interfaces.IVillager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.ItemCost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin extends AbstractContainerScreen<MerchantMenu> {
    public MerchantScreenMixin(MerchantMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(CallbackInfo ci) {
        Villager targetVillager = VillagerCracker.getVillager();
        if (targetVillager != null) {
            if (Minecraft.getInstance().player.distanceTo(targetVillager) > 2.0) {
                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.outOfSync.distance").withStyle(ChatFormatting.RED), 100);
                ((IVillager) targetVillager).clientcommands_getVillagerRngSimulator().reset();
                return;
            }

            if (VillagerCracker.targetOffer != null) {
                if (menu.getOffers().stream().map(offer -> new VillagerCommand.Offer(offer.getBaseCostA(), offer.getItemCostB().map(ItemCost::itemStack).orElse(null), offer.getResult())).anyMatch(offer -> offer.equals(VillagerCracker.targetOffer))) {
                    ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.success", Configs.villagerAdjustment * 50).withStyle(ChatFormatting.GREEN), 100);
                    minecraft.player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0f, 2.0f);
                } else {
                    a: {
                        if (VillagerCracker.surroundingOffers != null) {
                            VillagerCommand.Offer[] availableOffers = menu.getOffers().stream().map(VillagerCommand.Offer::new).toArray(VillagerCommand.Offer[]::new);
                            List<VillagerCommand.Offer[]> beforeOffers = VillagerCracker.surroundingOffers.getFirst();
                            List<VillagerCommand.Offer[]> afterOffers = VillagerCracker.surroundingOffers.getSecond();
                            OptionalInt currentOfferTickIndex = OptionalInt.empty();
                            for (int i = beforeOffers.size() - 1; i >= 0; i--) {
                                VillagerCommand.Offer[] offers = beforeOffers.get(i);
                                if (Arrays.equals(offers, availableOffers)) {
                                    // we need to adjust by -1 to get it to not be 0 for the last value in `beforeOffers`
                                    currentOfferTickIndex = OptionalInt.of(-(beforeOffers.size() - 1 - i) - 1);
                                    break;
                                }
                            }

                            for (int i = 0; i < afterOffers.size(); i++) {
                                VillagerCommand.Offer[] offers = afterOffers.get(i);
                                if (Arrays.equals(offers, availableOffers)) {
                                    if (currentOfferTickIndex.isEmpty() || Mth.abs(currentOfferTickIndex.getAsInt()) > i + 1) {
                                        // we need to adjust by 1 to get it to not be 0 for the first value in `afterOffers`
                                        currentOfferTickIndex = OptionalInt.of(i + 1);
                                        break;
                                    }
                                }
                            }

                            if (currentOfferTickIndex.isPresent()) {
                                // we negate the currentOfferTickIndex as adjustment because in the case that our actual offers are found
                                ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.failure.detailed", Configs.villagerAdjustment * 50, -currentOfferTickIndex.getAsInt()).withStyle(ChatFormatting.RED), 100);
                                Configs.villagerAdjustment -= -currentOfferTickIndex.getAsInt();
                                break a;
                            }
                        }

                        ClientCommandHelper.addOverlayMessage(Component.translatable("commands.cvillager.failure", Configs.villagerAdjustment * 50).withStyle(ChatFormatting.RED), 100);
                        minecraft.player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1.0f, 1.0f);
                    }
                }
                VillagerCracker.targetOffer = null;
            }
        }
    }
}
