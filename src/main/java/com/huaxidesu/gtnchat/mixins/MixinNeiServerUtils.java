package com.huaxidesu.gtnchat.mixins;

import codechicken.nei.NEIServerUtils;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.huaxidesu.gtnchat.nei.NeiLink;

/**
 * 在 NEI 生成物品链接前取一份物品数据。
 *
 * <p>NEI 把物品数据推给自己服的客户端后，只在本地缓存里留一个短标识；
 * 别的服务器没有这份数据，转发过去的链接点了会提示找不到。这里取到原始数据，
 * 随聊天一起转给其它服务器，接收端再推给自己服的客户端。
 *
 * <p>只读取参数，不改 NEI 的行为。
 */
@Mixin(value = NEIServerUtils.class, remap = false)
public class MixinNeiServerUtils {

    @Inject(method = "sendChatItemLink", at = @At("HEAD"), remap = false, require = 0)
    private static void gtnchat$captureItemLink(EntityPlayerMP sender, NBTTagCompound payload, CallbackInfo ci) {
        NeiLink.onLocalShare(sender, payload);
    }
}
