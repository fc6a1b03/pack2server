package cloud.dbug.pack2server.common.fetcher;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.downloader.Downloader;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jayway.jsonpath.JsonPath;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 服务加载器获取与构建工具类。
 * <p>
 * 此类负责根据清单文件解析加载器信息、下载加载器JAR文件，
 * 并提供启动该加载器所代表的Minecraft服务的方法。
 * </p>
 * @author 拒绝者
 * @version 2025-09-27
 * @since 2025-09-06
 */
@UtilityClass
public final class LoaderFetcher {
    /**
     * 虚拟线程执行器
     */
    private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 执行加载器的获取、下载和初步准备流程。
     * <p>
     * 此方法会按顺序执行以下操作：<br/>
     * 1. 确保工作目录存在。<br/>
     * 2. 异步解析清单文件以获取加载器信息。<br/>
     * 3. 异步下载加载器JAR文件。<br/>
     * 4. 返回准备好的Loader对象。<br/>
     * @param manifestPath  清单文件的路径。
     * @param workDirectory 工作目录的路径。
     * @return 准备好的 {@link Loader} 实例。
     */
    public static Loader exec(final Path manifestPath, final Path workDirectory) {
        ServerWorkspace.ensure(workDirectory);
        return CompletableFuture
                .supplyAsync(() -> parse(manifestPath, workDirectory), VIRTUAL_THREAD_EXECUTOR)
                .thenApplyAsync(loader -> {
                    loader.download(workDirectory);
                    return loader;
                }, VIRTUAL_THREAD_EXECUTOR)
                .thenApplyAsync(Function.identity(), VIRTUAL_THREAD_EXECUTOR)
                .join();
    }

    /**
     * 根据清单文件解析加载器信息。
     * @param manifestPath  清单文件路径。
     * @param workDirectory 工作目录路径。
     * @return 解析得到的 {@link Loader} 对象。
     * @throws IllegalStateException 如果清单中未找到支持的加载器。
     */
    private static Loader parse(final Path manifestPath, final Path workDirectory) {
        return Opt.ofNullable(JSONUtil.readJSONObject(manifestPath.toFile(), CharsetUtil.CHARSET_UTF_8))
                .map(rootJsonObject -> {
                    // 提取 Minecraft 版本和加载器 ID (例如 "fabric-0.15.2")
                    final String modLoaderId = rootJsonObject.getByPath("minecraft.modLoaders[0].id", String.class);
                    // 生成加载器的 JAR 文件名 (例如 "fabric-server.jar")
                    final String jarFileName = modLoaderId.replaceFirst("(?<=-)[\\d.]+", "server.jar");
                    // 解析下载地址
                    final String downloadUrl = parseUrl(rootJsonObject);
                    // 创建并返回 Loader 对象
                    return new Loader(workDirectory, modLoaderId, jarFileName, downloadUrl);
                })
                .orElseThrow(() -> new IllegalStateException("未能从清单文件中找到支持的加载程序配置"));
    }

    /**
     * 根据加载器类型和版本解析其下载地址。
     * @param rootJsonObject 清单文件的根 JSON 对象。
     * @return 加载器 JAR 文件的下载 URL。
     * @throws IllegalArgumentException 如果加载器类型未知。
     */
    @SneakyThrows
    private static String parseUrl(final JSONObject rootJsonObject) {
        final String minecraftVersion = rootJsonObject.getByPath("minecraft.version", String.class);
        final String modLoaderId = rootJsonObject.getByPath("minecraft.modLoaders[0].id", String.class);
        // 将加载器ID分割为类型和版本 (例如 ["fabric", "0.15.2"])
        final String[] parts = modLoaderId.split("-");
        // 根据加载器类型构造下载URL
        return switch (parts[0]) {
            case "fabric" -> "https://meta.fabricmc.net/v2/versions/loader/%s/%s/%s/server/jar".formatted(
                    minecraftVersion, parts[1],
                    JsonPath.parse(HttpUtil.get("https://meta.fabricmc.net/v2/versions"))
                            .read("$.installer[?(@.stable==true)].version", List.class).getFirst()
            );
            case "forge" ->
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/%1$s-%2$s/forge-%1$s-%2$s.jar".formatted(minecraftVersion, parts[1]);
            case "neoforge" ->
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-server.jar".formatted(parts[1], parts[1]);
            case "quilt" ->
                    "https://meta.quiltmc.org/v3/versions/loader/%s/%s/server/jar".formatted(minecraftVersion, parts[1]);
            default -> throw new IllegalArgumentException("不支持的加载器类型: %s".formatted(parts[0]));
        };
    }

    /**
     * 表示一个 Minecraft 服务加载器的类。
     * 包含加载器的元数据、下载逻辑和启动命令生成。
     * @author xuhaifeng
     * @date 2025-09-27
     */
    @Data
    public static class Loader {
        /**
         * 工作目录路径。
         */
        private final Path workDirectory;
        /**
         * 加载器 JAR 文件的下载地址。
         */
        private final String downloadUrl;
        /**
         * 加载器的标识符 (例如 "fabric-0.15.2")。
         */
        private final String loaderName;
        /**
         * 加载器 JAR 文件的名称 (例如 "fabric-server.jar")。
         */
        private final String jarFileName;
        /**
         * 下载后的 JAR 文件路径。
         */
        private Path jarPath;

        /**
         * 构造一个新的 Loader 实例。
         * @param workDirectory 工作目录。
         * @param loaderName    加载器名称。
         * @param jarFileName   JAR 文件名。
         * @param downloadUrl   下载地址。
         */
        public Loader(final Path workDirectory, final String loaderName, final String jarFileName, final String downloadUrl) {
            this.loaderName = loaderName;
            this.jarFileName = jarFileName;
            this.downloadUrl = downloadUrl;
            this.workDirectory = workDirectory;
        }

        /**
         * 从指定的 URL 下载加载器 JAR 文件到工作目录。
         * @param workDirectory 工作目录路径。
         */
        private void download(final Path workDirectory) {
            final File targetFile = FileUtil.file(workDirectory.toFile(), this.jarFileName);
            // 如果目标文件已存在，则先删除它
            FileUtil.del(targetFile);
            // 调用 Downloader 工具类执行下载
            this.jarPath = Downloader.fetch(this.downloadUrl, targetFile.toPath());
        }

        /**
         * 构建启动此加载器所需的标准 Java 命令列表。
         * <p>
         * 命令格式通常为: `java -jar <jar文件> --nogui --universe <缓存目录>`
         * </p>
         * @param jrePath JRE 的可执行文件路径。
         * @return 包含启动命令各部分的列表。
         */
        public List<String> buildStartCommand(final Path jrePath) {
            return ListUtil.toList(
                    ServerWorkspace.JAVA_PROGRAM.apply(jrePath),
                    "-jar",
                    // 使用 resolve 确保正确的相对路径
                    this.workDirectory.resolve(this.jarFileName).toString(),
                    "--nogui",
                    "--universe",
                    this.workDirectory.resolve("cache").toString()
            );
        }

        /**
         * 启动加载器代表的 Minecraft 服务进程。
         * <p>
         * 此方法会创建一个新的进程来运行加载器 JAR 文件。<br/>
         * 它会将进程的标准输出和错误流重定向到当前 Java 进程，<br/>
         * 便于日志查看。
         * </p>
         * @param jrePath JRE 的可执行文件路径。
         * @return 启动的 {@link Process} 对象。
         * @throws IOException 如果启动进程时发生 I/O 错误。
         */
        public Process start(final Path jrePath) throws IOException {
            return new ProcessBuilder(this.buildStartCommand(jrePath))
                    .redirectErrorStream(Boolean.TRUE)
                    .directory(this.workDirectory.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start();
        }

        /**
         * 启动加载器进程，并等待其完成
         * @param jrePath JRE 的可执行文件路径。
         * @throws IOException          如果启动进程或读取输出时发生 I/O 错误。
         * @throws InterruptedException 如果在等待进程完成时被中断。
         */
        public void startAndWait(final Path jrePath) throws IOException, InterruptedException {
            this.start(jrePath).waitFor();
        }
    }
}