package cloud.dbug.pack2server.common;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.PathUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Supplier2;
import cn.hutool.core.lang.func.VoidFunc;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.compress.CompressUtil;
import cn.hutool.extra.compress.extractor.Extractor;
import lombok.SneakyThrows;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 服务工作区常量
 * @author 拒绝者
 * @date 2025-09-05
 */
public class ServerWorkspace {
    /**
     * 模组目录
     */
    public static final String MOD = "mods";
    /**
     * 临时目录
     */
    public static final String TEMP = "Temp";
    /**
     * 模组包其余文件目录
     */
    public static final String OVERRIDES = "overrides";
    /**
     * 模组清单文件名
     */
    public static final String MANIFEST = "manifest.json";
    /**
     * 根目录
     */
    public static final String USER_HOME = System.getProperty("user.dir");
    /**
     * 测试目录
     */
    public static final File TEST_DIR = FileUtil.file(ServerWorkspace.USER_HOME, ServerWorkspace.TEMP);
    /**
     * 服务目录
     */
    public static final List<String> SERVER_DIRS = List.of("mods", "cache", "logs", "versions", "libraries", "config");
    /**
     * Java路径
     */
    public static final Function<Path, String> JAVA_PROGRAM = path ->
            Path.of(Opt.ofNullable(path).map(item -> path.toAbsolutePath().normalize().toString())
                            .orElseGet(() -> System.getProperty("java.home")), "bin", "java")
                    .toAbsolutePath().normalize().toString();
    /**
     * 构建基础目录与文件
     */
    public static final Consumer<File> BUILD_DIR = parent ->
            SERVER_DIRS.forEach(dir -> ServerWorkspace.ensure(Paths.get(dir), () -> {
                FileUtil.mkdir(FileUtil.file(parent, dir));
                FileUtil.writeUtf8String("eula=true", FileUtil.file(parent, "eula.txt"));
            }));
    /**
     * 复制目录
     */
    public static final Supplier2<File, File, File> COPY_DIR = (src, dest) -> {
        ServerWorkspace.ensure(dest.toPath());
        return FileUtil.copyContent(src, dest, Boolean.TRUE);
    };
    /**
     * 提取文件
     */
    public static final VoidFunc<Path> EXTRACT_FILES = path -> {
        try (final Extractor extractor = CompressUtil.createExtractor(CharsetUtil.CHARSET_UTF_8, path[0].toFile())) {
            final File file = path[1].toFile();
            FileUtil.del(file);
            ensure(file.toPath());
            extractor.extract(file);
        }
    };

    /**
     * 确保目录
     * @param path 路径
     */
    @SneakyThrows
    public static void ensure(final Path path) {
        ensure(path, null);
    }

    /**
     * 确保目录
     * @param path     路径
     * @param runnable 运行
     */
    @SneakyThrows
    public static void ensure(final Path path, final Runnable runnable) {
        if (Objects.nonNull(path)) {
            final Path parent = path.getParent();
            if (Objects.nonNull(parent) && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
            if (PathUtil.isFile(path, Boolean.TRUE) && Files.exists(path)) {
                Files.delete(path);
            }
        }
        Opt.ofNullable(runnable).ifPresent(Runnable::run);
    }


    /**
     * 合法文件名
     * @param raw 原始
     * @return {@link String }
     */
    public static String legalFileName(final String raw) {
        return StrUtil.isEmpty(raw) ? "unknown" :
                // 去掉 Windows 非法字符
                raw.replaceAll("[\\\\/:*?\"<>|]", "_")
                        // 空格也替换成 _
                        .replaceAll("\\s+", "_")
                        // 多个 _ 合并
                        .replaceAll("_+", "_");
    }

    /**
     * 从 URL 提取“尽可能合理”的文件名
     * @param uri 统一资源标识符
     * @return {@link String }
     */
    public static String parseFileName(final String uri) {
        try {
            final URI u = URI.create(uri);
            final String path = u.getPath();
            final String query = u.getQuery();
            // 路径最后一截
            String name = StrUtil.emptyToNull(StrUtil.subAfter(path, "/", Boolean.TRUE));
            // 路径没有就拿查询参数第一个 value
            if (Objects.isNull(name) && Objects.nonNull(query)) {
                name = StrUtil.subBefore(query, "&", Boolean.FALSE);
                name = StrUtil.subAfter(name, "=", Boolean.FALSE);
            }
            //  再空就给默认
            return legalFileName(StrUtil.blankToDefault(name, "file_%d".formatted(System.currentTimeMillis())));
        } catch (final Exception e) {
            return "file_%d".formatted(System.currentTimeMillis());
        }
    }
}