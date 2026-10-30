package com.huaxidesu.gtnchat.mixins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 按实际安装的模组决定加载哪些 Mixin。
 *
 * <p>唯一需要注入的地方是 NEI 的跨服物品链接：它的数据走 NEI 自己的网络通道，
 * 既没有对外接口也没有事件，只能在其服务端入口处取一份数据。
 * 没装 NEI 时这里返回空列表，模组照常启动。
 */
public class Plugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<String>();
        if (neiInstalled()) mixins.add("MixinNeiServerUtils");
        return mixins;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    /** 扫 mods 目录，看有没有哪个 jar 带着 NEI 的服务端工具类。 */
    private static boolean neiInstalled() {
        File dir = new File("mods");
        if (!dir.isDirectory()) return false;
        File[] jars = dir.listFiles();
        if (jars == null) return false;
        for (File jar : jars) {
            if (!jar.getName().toLowerCase().endsWith(".jar")) continue;
            if (contains(jar, "codechicken/nei/NEIServerUtils.class")) return true;
        }
        return false;
    }

    private static boolean contains(File jar, String entry) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (entry.equals(entries.nextElement().getName())) return true;
            }
        } catch (IOException ignored) {
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {}
            }
        }
        return false;
    }
}
