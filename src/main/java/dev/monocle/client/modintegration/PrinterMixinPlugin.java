package dev.monocle.client.modintegration;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Keep optional targets out of Mixin's class-loading path unless the tested releases are installed. */
public final class PrinterMixinPlugin implements IMixinConfigPlugin {
    public static String dependencyError() {
        FabricLoader loader = FabricLoader.getInstance();
        return versionError(version(loader, "litematica"), version(loader, "malilib"), version(loader, "litematica_printer"));
    }

    private static String version(FabricLoader loader, String id) {
        return loader.getModContainer(id).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    static String versionError(String litematica, String malilib, String printer) {
        if (litematica == null || malilib == null || printer == null)
            return "Install Sakura's Litematica 0.28.8, MaLiLib 0.29.6 and Litematica Printer 3.2.2 for Minecraft 26.2.";
        // ponytail: exact versions pin the private queue/method hooks; review their ABI before accepting another release.
        if (!litematica.equals("0.28.8") || !malilib.equals("0.29.6") || !printer.equals("3.2.2"))
            return "Printer Helper currently supports Sakura's Litematica 0.28.8, MaLiLib 0.29.6 and Printer 3.2.2 (26.2).";
        return null;
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return dependencyError() == null; }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
