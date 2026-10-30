package com.huaxidesu.gtnchat.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端初始化：只挂一个 tick，用来扫描成就。 */
@SideOnly(Side.CLIENT)
public final class ClientSetup {

    private ClientSetup() {}

    public static void init() {
        FMLCommonHandler.instance().bus().register(new TickHandler());
    }

    @SideOnly(Side.CLIENT)
    public static final class TickHandler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) ClientAchievements.onTick();
        }
    }
}
