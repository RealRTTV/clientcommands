package net.earthcomputer.clientcommands.mixin.commands.villager;

import com.llamalad7.mixinextras.sugar.Local;
import net.earthcomputer.clientcommands.features.VillagerCracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V"))
    private void onHandleSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
        Villager targetVillager = VillagerCracker.getVillager();
        if (targetVillager != null) {
            VillagerCracker.onSoundEventPlayed(packet, new Vec3(packet.getX(), packet.getY(), packet.getZ()));
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V"))
    private void onHandleChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            ResourceKey<Level> key = Minecraft.getInstance().level.dimension();
            packet.runUpdates((pos, state) -> {
                if (new GlobalPos(key, pos).equals(VillagerCracker.getClockPos())) {
                    VillagerCracker.onServerTick();
                }
            });
        }
    }

    @Inject(method = "handleAddExperienceOrb", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V"))
    private void onHandleAddExperienceOrb(ClientboundAddExperienceOrbPacket packet, CallbackInfo ci) {
        Villager targetVillager = VillagerCracker.getVillager();
        if (targetVillager != null && new Vec3(packet.getX(), packet.getY() - 0.5, packet.getZ()).distanceToSqr(targetVillager.position()) <= 0.1f) {
            VillagerCracker.onXpOrbSpawned(packet);
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V"))
    private void onHandleBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null && new GlobalPos(Minecraft.getInstance().level.dimension(), packet.getPos()).equals(VillagerCracker.getClockPos())) {
            VillagerCracker.onServerTick();
        }
    }

    @Inject(method = "handleDamageEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;handleDamageEvent(Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void onHandleDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci, @Local Entity entity) {
        if (entity == VillagerCracker.getVillager() && VillagerCracker.simulator.getCrackedState().isCracked()) {
            VillagerCracker.simulator.onBadRNG("mobHurt");
        }
    }
}
