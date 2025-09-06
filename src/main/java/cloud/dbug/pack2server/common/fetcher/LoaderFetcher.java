package cloud.dbug.pack2server.common.fetcher;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.downloader.Downloader;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.system.SystemUtil;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import lombok.Data;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 服务加载器-生成器
 * @author 拒绝者
 * @date 2025-09-06
 */
@UtilityClass
@SuppressWarnings("SpellCheckingInspection")
public final class LoaderFetcher {
    /**
     * 全局虚拟线程池
     */
    private static final Executor EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 执行提取
     * @param manifest 清单路径
     * @param work     工作目录
     * @return Loader
     */
    public static Loader exec(final Path manifest, final Path work) {
        ServerWorkspace.ensure(work);
        return CompletableFuture.supplyAsync(() -> parse(manifest, work), EXECUTOR)
                .thenApplyAsync(loader -> loader.download(work), EXECUTOR)
                .thenApplyAsync(Function.identity(), EXECUTOR).join();
    }

    /**
     * 解析加载器
     * @param manifest 清单
     * @param work     工作目录
     * @return {@link Loader }
     */
    private static Loader parse(final Path manifest, final Path work) {
        return Optional.ofNullable(JSONUtil.readJSONObject(manifest.toFile(), CharsetUtil.CHARSET_UTF_8))
                .map(root -> {
                    final String name = root.getByPath("minecraft.modLoaders[0].id", String.class);
                    return new Loader(work, name, name.replaceFirst("(?<=-)[\\d.]+", "server.jar"), parseUrl(root));
                })
                .orElseThrow(() -> new IllegalStateException("未找到支持的加载程序"));
    }

    /**
     * 解析下载地址
     * @param root 根
     * @return {@link String }
     */
    private static String parseUrl(final JSONObject root) {
        final String mc = root.getByPath("minecraft.version", String.class);
        final String id = root.getByPath("minecraft.modLoaders[0].id", String.class);
        final String[] sp = id.split("-");
        return switch (sp[0]) {
            case "fabric" -> {
                final DocumentContext context = JsonPath.parse(HttpUtil.get("https://meta.fabricmc.net/v2/versions"));
                yield "https://meta.fabricmc.net/v2/versions/loader/%s/%s/%s/server/jar".formatted(
                        context.read("$.game[?(@.stable==true)].version", List.class).getFirst(),
                        context.read("$.loader[?(@.stable==true)].version", List.class).getFirst(),
                        context.read("$.installer[?(@.stable==true)].version", List.class).getFirst()
                );
            }
            // TODO: 待完善
            case "forge" ->
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/%1$s-%2$s/forge-%1$s-%2$s.jar".formatted(mc, sp[1]);
            // TODO: 待完善
            case "neoforge" ->
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-server.jar".formatted(sp[1], sp[1]);
            // TODO: 待完善
            case "quilt" -> "https://meta.quiltmc.org/v3/versions/loader/%s/%s/server/jar".formatted(mc, sp[1]);
            default -> throw new IllegalArgumentException("未知加载器: %s".formatted(sp[0]));
        };
    }

    /**
     * 加载器
     * @author 拒绝者
     * @date 2025-09-06
     */
    @Data
    public class Loader {
        private Path path;
        private final Path work;
        private final String url;
        private final String name;
        private final String jarName;

        /**
         * 加载器
         * @param name 名字
         * @param url  下载地址
         */
        public Loader(final Path work, final String name, final String jarName, final String url) {
            this.url = url;
            this.work = work;
            this.name = name;
            this.jarName = jarName;
        }

        /**
         * 下载加载器
         */
        private Loader download(final Path work) {
            this.path = Downloader.fetch(url, FileUtil.file(work.toFile(), jarName).toPath());
            return this;
        }

        /**
         * 执行命令
         */
        public List<String> cmd() {
            // 命令列表：java -jar <jar> --nogui --universe <cache>
            return List.of(
                    ServerWorkspace.JAVA_PROGRAM, "-jar", work.resolve(jarName).toString(), "--nogui", "--universe", work.resolve("cache").toString()
            );
        }

        /**
         * 启动
         * @return {@link Process }
         * @throws IOException IOException
         */
        public Process start() throws IOException {
            if (SystemUtil.getOsInfo().isWindows()) {
                return new ProcessBuilder(List.of(
                        "cmd.exe", "/c",
                        "start", "\"\"", "/B", "/D", work.toString(),
                        cmd().stream().map("\"%s\""::formatted).collect(Collectors.joining(" "))
                )).directory(work.toFile()).redirectErrorStream(Boolean.TRUE).start();
            }
            return new ProcessBuilder(cmd()).directory(work.toFile()).redirectErrorStream(Boolean.TRUE).start();
        }

        /**
         * 过程
         * @return {@link String }
         * @throws IOException IOException
         */
        public String startByProcess() throws IOException {
            return StrUtil.utf8Str(start().getInputStream().readAllBytes());
        }
    }
}