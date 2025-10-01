package cloud.dbug.pack2server.cli;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.detector.ServerModDetector;
import cloud.dbug.pack2server.common.downloader.Downloader;
import cloud.dbug.pack2server.common.fetcher.JreFetcher;
import cloud.dbug.pack2server.common.fetcher.LoaderFetcher;
import cloud.dbug.pack2server.common.fetcher.ModsBulkFetcher;
import cloud.dbug.pack2server.entity.Source;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.Opt;
import lombok.SneakyThrows;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 将 CurseForge 整合包一键转换为可直接启动的服务器端目录。
 * <p>核心流程：下载→解压→模组批量获取→服务端侧模组过滤→覆盖文件复制→加载器生成→启动脚本写出。
 * 充分压榨多核，零阻塞。
 * @author 拒绝者
 * @date 2025-09-05
 */
@CommandLine.Command(
        name = "convert",
        description = "Convert CurseForge mod package to server directory",
        mixinStandardHelpOptions = true
)
public class ConvertCommand implements Callable<Integer> {
    @CommandLine.ArgGroup(multiplicity = "1", heading = "Input source (choose one from two):%n")
    private Source source;
    @CommandLine.Option(names = {"-f", "--force"}, description = "overwrite existing directory")
    private boolean forceOverwrite;
    @CommandLine.Option(names = {"-k", "--key"}, description = "CurseForge API Key (supports env: CF_API_KEY) Note: wrap the key in single quotes, e.g. 'your-key'.")
    private String cfApiKey;
    @CommandLine.Option(names = {"-o", "--output"}, defaultValue = "./server", description = "Output server directory (default:./server)")
    private Path serverOutputDir;

    /**
     * 统一结构化日志，方便 grep & 监控。
     * @param stage 阶段
     * @param start 开始
     */
    private static void logStage(final String stage, final Instant start) {
        final Instant now = Instant.now();
        Console.log("[{}] {} | 已耗时={}", DatePattern.NORM_DATETIME_MS_FORMATTER.format(now), stage, Duration.between(start, now));
    }

    /**
     * 解析整合包本地路径：若用户给的是 URL 则先下载，否则直接使用本地 zip。
     */
    private Path resolvePackPath() {
        return Opt.ofNullable(source.getZip())
                .orElseGet(() ->
                        Downloader.fetch(source.getUrl(), serverOutputDir.resolve(ServerWorkspace.MOD).resolve(source.getName()))
                );
    }

    @Override
    @SneakyThrows
    public Integer call() {
        final Instant start = Instant.now();
        // 处理释放目录
        serverOutputDir = serverOutputDir.toAbsolutePath().normalize();
        logStage("Stage-0 参数解析完成", start);
        /* 1. 获取整合包本地路径（下载或直接使用） */
        final Path packLocalPath = resolvePackPath();
        if (Files.notExists(packLocalPath)) {
            Console.error("[ERR] 整合包不存在：{}", packLocalPath);
            return 1;
        }
        /* 2. 清理历史输出目录（如开启 force） */
        if (forceOverwrite) {
            FileUtil.clean(serverOutputDir.toFile());
        }
        ServerWorkspace.BUILD_DIR.accept(serverOutputDir.toFile());
        logStage("Stage-1 工作目录初始化完成", start);
        /* 3. 解压整合包 -> 临时目录 */
        final Path extractDir = Files.createTempDirectory(serverOutputDir, ".extract_");
        ServerWorkspace.EXTRACT_FILES.get(packLocalPath, extractDir);
        logStage("Stage-2 整合包解压完成", start);
        /* 4. 模组批量下载 */
        Opt.ofBlankAble(cfApiKey).ifPresent(k -> System.setProperty("CF_API_KEY", k));
        final Path manifestPath = extractDir.resolve(ServerWorkspace.MANIFEST);
        final Path modDownloadDir = serverOutputDir.resolve(ServerWorkspace.MOD);
        ModsBulkFetcher.fetch(manifestPath, modDownloadDir);
        logStage("Stage-3 模组批量下载完成", start);
        /* 5. 服务端侧模组过滤 */
        final ConcurrentLinkedDeque<Path> serverOnlyMods = new ConcurrentLinkedDeque<>();
        if (FileUtil.exist(modDownloadDir.toFile())) {
            try (final Stream<Path> jarWalk = Files.walk(modDownloadDir)) {
                jarWalk.filter(p -> FileUtil.isFile(p.toFile()))
                        .collect(Collectors.toConcurrentMap(p -> p, ServerModDetector::detect))
                        .forEach((jar, side) -> {
                            if (side.isServer()) {
                                serverOnlyMods.add(jar);
                            } else {
                                FileUtil.del(jar);
                            }
                        });
            }
        }
        logStage("Stage-4 服务端模组过滤完成，保留数量=%d".formatted(serverOnlyMods.size()), start);
        /* 6. 覆盖文件复制 */
        final Path overridesDir = extractDir.resolve(ServerWorkspace.OVERRIDES);
        if (Files.exists(overridesDir)) ServerWorkspace.COPY_DIR.get(overridesDir.toFile(), serverOutputDir.toFile());
        logStage("Stage-5 覆盖文件复制完成", start);
        /* 7. 运行环境释放 */
        final Path jrePath = JreFetcher.setupRuntime(manifestPath, serverOutputDir);
        logStage("Stage-7 运行环境释放完成，总耗时=%s".formatted(Duration.between(start, Instant.now())), start);
        /* 8. 生成加载器 & 启动脚本 */
        logStage("Stage-8 初次运行服务", start);
        final LoaderFetcher.Loader loader = LoaderFetcher.exec(manifestPath, serverOutputDir);
        loader.startAndWait(jrePath);
        /* 9. 清理临时解压目录 */
        FileUtil.del(extractDir.toFile());
        logStage("Stage-9 临时目录清理完成，总耗时=%s".formatted(Duration.between(start, Instant.now())), start);
        /* 10. 打印运行脚本 */
        logStage("Stage-10 加载器=%s\n运行命令=%s".formatted(loader, loader.buildStartCommand(jrePath)), start);
        /* 11. 释放许可 及 清理目录 */
        ServerWorkspace.LICENSE.accept(serverOutputDir);
        logStage("Stage-11 已完成转换~", start);
        return 0;
    }
}