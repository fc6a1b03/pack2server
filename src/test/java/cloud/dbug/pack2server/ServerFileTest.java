package cloud.dbug.pack2server;

import cloud.dbug.pack2server.common.ConstantPool;
import cloud.dbug.pack2server.common.detector.ServerModDetector;
import cloud.dbug.pack2server.common.detector.enums.Side;
import cloud.dbug.pack2server.common.fetcher.LoaderFetcher;
import cloud.dbug.pack2server.common.fetcher.ModsBulkFetcher;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 服务文件测试
 * @author xuhaifeng
 * @date 2025-09-06
 */
public class ServerFileTest {
    @Test
    @DisplayName("服务器目录初始化")
    public void serverDirectoryInit() {
        ConstantPool.BUILD_DIR.accept(ConstantPool.TEST_DIR);
    }

    @Test
    @DisplayName("模组批量提取")
    public void modsBulkFetcher() {
        System.setProperty("CF_API_KEY", "123");
        ModsBulkFetcher.fetch(
                FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6\\manifest.json").toPath(),
                FileUtil.file(ConstantPool.TEST_DIR, ConstantPool.MOD).toPath()
        );
    }

    @Test
    @DisplayName("服务器Mods检测器")
    public void serverModsDetector() throws IOException {
        try (final Stream<Path> walk = Files.walk(FileUtil.file(ConstantPool.TEST_DIR, ConstantPool.MOD).toPath())) {
            walk.filter(p -> FileUtil.isFile(p.toFile())).forEach(jar -> {
                final Side detect = ServerModDetector.detect(jar);
                Console.log("检查结果 | {} -> {} | 删除 -> {}", jar.getFileName(), detect, detect.isServer() ? Boolean.FALSE : FileUtil.del(jar));
            });
        }
    }

    @Test
    @DisplayName("模组资源目录复制")
    public void copyResourceDir() {
        ConstantPool.COPY_DIR.get(
                FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6\\overrides"),
                ConstantPool.TEST_DIR
        );
    }

    @Test
    @DisplayName("服务加载器生成")
    public void serviceLoaderGeneration() throws IOException {
        final LoaderFetcher.Loader exec = LoaderFetcher.exec(
                FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6\\manifest.json").toPath(), ConstantPool.TEST_DIR.toPath()
        );
        Console.log("生成配置 | {} \n {}", exec, String.join(" ", exec.cmd()));
        Console.log("生成结果 | {}", exec.startByProcess());
    }
}