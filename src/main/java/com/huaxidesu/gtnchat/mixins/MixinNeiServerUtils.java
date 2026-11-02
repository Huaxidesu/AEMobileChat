package com.huaxidesu.gtnchat.mixins;

import codechicken.nei.NEIServerUtils;
import codechicken.nei.recipe.StackInfo;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.huaxidesu.gtnchat.GTNHChat;
import com.huaxidesu.gtnchat.config.Config;
import com.huaxidesu.gtnchat.nei.NeiLink;

/**
 * 在 NEI 生成物品链接前取一份物品数据。
 *
 * <p>NEI 把物品数据推给自己服的客户端后，只在本地缓存里留一个短标识；
 * <p>其它服务器没有这份数据，转发过去的链接点击后会提示找不到。这里取到原始数据，
 * 随聊天一起转给其它服务器，接收端再推给自己服的客户端。
 *
 * <p>只读取参数，不改 NEI 的行为。
 *
 * <p>{@code debug: true} 时同时输出 payload 结构和 NEI 自己的解析结果：
 * NEI 的 catch 只回 {@code e.toString()}，玩家聊天里只能看到一行
 * {@code java.lang.NullPointerException}，没有堆栈，定位不了。
 */
@Mixin(value = NEIServerUtils.class, remap = false)
public class MixinNeiServerUtils {

    @Inject(method = "sendChatItemLink", at = @At("HEAD"), remap = false, require = 0)
    private static void gtnchat$captureItemLink(EntityPlayerMP sender, NBTTagCompound payload, CallbackInfo ci) {
        if (Config.debug) diagnose(payload);
        NeiLink.onLocalShare(sender, payload);
    }

    private static void diagnose(NBTTagCompound payload) {
        if (payload == null) {
            GTNHChat.LOG.warn("[NEI] payload 是 null");
            return;
        }
        GTNHChat.LOG.info("[NEI] payload keys=" + payload.func_150296_c()
            + " linkType=" + payload.getString("linkType"));
        if (!payload.hasKey("item")) {
            GTNHChat.LOG.warn("[NEI] payload 里没有 item 标签");
            return;
        }
        NBTTagCompound item = payload.getCompoundTag("item");
        GTNHChat.LOG.info("[NEI] item keys=" + item.func_150296_c()
            + " strId=" + item.getString("strId")
            + " Count=" + item.getInteger("Count")
            + " Damage=" + item.getInteger("Damage"));
        try {
            ItemStack stack = StackInfo.loadFromNBT(item);
            GTNHChat.LOG.info("[NEI] StackInfo.loadFromNBT -> " + stack);
        } catch (Throwable t) {
            GTNHChat.LOG.warn("[NEI] StackInfo.loadFromNBT 抛异常", t);
        }
    }
}
