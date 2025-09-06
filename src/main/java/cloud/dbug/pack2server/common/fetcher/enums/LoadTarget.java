package cloud.dbug.pack2server.common.fetcher.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 加载器目标
 * @author xuhaifeng
 * @date 2025-09-06
 */
@Getter
@AllArgsConstructor
@SuppressWarnings("SpellCheckingInspection")
public enum LoadTarget {
    FABRIC("fabric", "net.fabricmc.loader.impl.launch.server.FabricServerLauncher", "fabric-server-launch.jar"),
    FORGE("forge", "cpw.mods.bootstraplauncher.BootstrapLauncher", "forge-{mcVer}-{loaderVer}-server.jar"),
    NEO_FORGE("neoforge", "cpw.mods.bootstraplauncher.BootstrapLauncher", "neoforge-{mcVer}-{loaderVer}-server.jar"),
    QUILT("quilt", "org.quiltmc.loader.impl.launch.server.QuiltServerLauncher", "quilt-server-launch.jar");
    private final String key,
    /**
     * 主类
     */
    mainClass,
    /**
     * 文件模式
     */
    filePattern;
}