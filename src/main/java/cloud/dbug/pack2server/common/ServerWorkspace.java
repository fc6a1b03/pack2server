package cloud.dbug.pack2server.common;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Supplier2;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.compress.CompressUtil;
import cn.hutool.extra.compress.extractor.Extractor;
import lombok.SneakyThrows;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

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
    public static final Consumer<File> BUILD_DIR = parent -> SERVER_DIRS.forEach(dir -> FileUtil.mkdir(FileUtil.file(parent, dir)));
    /**
     * 许可
     */
    public static final Consumer<Path> LICENSE = parent -> FileUtil.writeUtf8String("eula=true", FileUtil.file(parent.toFile(), "eula.txt"));
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
    public static final Supplier2<Path, Path, Path> EXTRACT_FILES = (src, dest) -> {
        if (Objects.nonNull(src) && Objects.nonNull(dest)) {
            try (final Extractor extractor = CompressUtil.createExtractor(CharsetUtil.CHARSET_UTF_8, src.toFile())) {
                extractor.extract(dest.toFile());
            }
        }
        // 只列 dest 下第一层子目录
        try (final Stream<Path> sub = Files.list(dest)) {
            return sub.filter(Files::isDirectory).findFirst().orElseThrow(() -> new IORuntimeException("[EXTRACT] 文件释放失败 | home={}", dest));
        } catch (IOException e) {
            throw new IORuntimeException(e);
        }
    };

    /**
     * 确保 目录或文件
     * @param path 路径;有后缀则文件,无后缀则目录
     */
    @SneakyThrows
    public static void ensure(final Path path) {
        ensure(path, null);
    }

    /**
     * 确保 目录或文件
     * @param path     路径;有后缀则文件,无后缀则目录
     * @param runnable 可运行
     */
    public static void ensure(final Path path, final Runnable runnable) {
        ensure(path, null, runnable);
    }

    /**
     * 确保 目录或文件
     * @param path     路径;有后缀则文件,无后缀则目录
     * @param runnable 运行
     */
    @SneakyThrows
    public static void ensure(final Path path, final Boolean isFile, final Runnable runnable) {
        if (Objects.isNull(path)) {
            return;
        }
        // 决定目标类型
        final boolean asFile = Objects.nonNull(isFile) ? isFile : StrUtil.contains(StrUtil.removePrefix(path.getFileName().toString(), "."), '.');
        // 处理父目录
        final Path parent = path.getParent();
        if (Objects.nonNull(parent) && Files.notExists(parent)) {
            FileUtil.mkdir(parent);
        }
        // 处理当前目录
        if (Files.exists(path)) {
            if (asFile && Files.isDirectory(path)) {
                FileUtil.del(path);
                Files.createFile(path);
            } else if (!asFile && Files.isRegularFile(path)) {
                FileUtil.del(path);
                FileUtil.mkdir(path);
            }
        } else {
            if (asFile) {
                Files.createFile(path);
            } else {
                FileUtil.mkdir(path);
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