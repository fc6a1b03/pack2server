package cloud.dbug.pack2server;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.detector.ServerModDetector;
import cloud.dbug.pack2server.common.detector.enums.Side;
import cloud.dbug.pack2server.common.fetcher.LoaderFetcher;
import cloud.dbug.pack2server.common.fetcher.ModsBulkFetcher;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 一体化服务器部署测试套件：从 CurseForge 整合包到可启动服务器的端到端验证。
 *
 * <p>主要流程：</p>
 * <ol>
 *   <li>解压原始模组包（支持任意常见归档格式，自动识别后缀）。</li>
 *   <li>初始化标准服务器目录结构（configs、mods、libraries...）。</li>
 *   <li>依据 {@code manifest.json} 批量拉取服务端侧 Mod（使用 CurseForge API）。</li>
 *   <li>运行 {@link ServerModDetector} 进行“客户端/服务端/通用”三侧检测，自动剔除客户端专用 JAR。</li>
 *   <li>将 overrides 目录下的配置与资源文件合并至服务器目录，实现配置覆盖。</li>
 *   <li>根据整合包 Minecraft/Forge/Fabric/Quilt 版本自动生成对应服务端加载器（Installer + 启动脚本）。</li>
 * </ol>
 *
 * <p>测试用例彼此独立，可按需单点执行；全部通过后，
 * {@code ConstantPool.TEST_DIR} 即为可直接 {@code java -jar xxx-server.jar} 启动的服务端根目录。</p>
 *
 * <p>依赖前提：</p>
 * <ul>
 *   <li>System Property {@code CF_API_KEY} 已配置有效 CurseForge API Key。</li>
 *   <li>运行环境具备外网访问权限以下载 Mod 与加载器。</li>
 *   <li>JDK 21（适配主流高版本模组加载器）。</li>
 * </ul>
 * @author 拒绝者
 * @since 2025-09-06
 */
public class ServerFileTest {
    @Test
    @DisplayName("解压原始模组包")
    public void extractOriginalModulePackage() {
        final File src = FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6.zip");
        final File dest = FileUtil.file(src.getParentFile(), FileUtil.mainName(src.getName()));
        ServerWorkspace.EXTRACT_FILES.callWithRuntimeException(src.toPath(), dest.toPath());
    }

    @Test
    @DisplayName("服务器目录初始化")
    public void serverDirectoryInit() {
        ServerWorkspace.BUILD_DIR.accept(ServerWorkspace.TEST_DIR);
    }

    @Test
    @DisplayName("模组批量提取")
    public void modsBulkFetcher() {
        System.setProperty("CF_API_KEY", "123");
        ModsBulkFetcher.fetch(
                FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6", ServerWorkspace.MANIFEST).toPath(),
                FileUtil.file(ServerWorkspace.TEST_DIR, ServerWorkspace.MOD).toPath()
        );
    }

    @Test
    @DisplayName("服务器Mods检测器")
    public void serverModsDetector() throws IOException {
        try (final Stream<Path> walk = Files.walk(FileUtil.file(ServerWorkspace.TEST_DIR, ServerWorkspace.MOD).toPath())) {
            walk.filter(p -> FileUtil.isFile(p.toFile())).forEach(jar -> {
                final Side detect = ServerModDetector.detect(jar);
                Console.log("检查结果 | {} -> {} | 删除 -> {}", jar.getFileName(), detect, detect.isServer() ? Boolean.FALSE : FileUtil.del(jar));
            });
        }
    }

    @Test
    @DisplayName("模组资源目录复制")
    public void copyResourceDir() {
        ServerWorkspace.COPY_DIR.get(
                FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6", ServerWorkspace.OVERRIDES),
                ServerWorkspace.TEST_DIR
        );
    }

    @Test
    @DisplayName("服务加载器生成")
    public void serviceLoaderGeneration() throws IOException {
        final LoaderFetcher.Loader exec = LoaderFetcher.exec(
                FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6\\manifest.json").toPath(), ServerWorkspace.TEST_DIR.toPath()
        );
        Console.log("生成配置 | {} \n {}", exec, String.join(" ", exec.cmd()));
        Console.log("生成结果 | {}", exec.startByProcess());
    }
}