package cloud.dbug.pack2server.common.fetcher;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.downloader.Downloader;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Jre提取器
 * @author xuhaifeng
 * @date 2025-09-09
 */
public class JreFetcher {
    /**
     * Jre地址
     */
    private static final String JRE_URL = "https://api.adoptium.net/v3/binary/latest/%d/ga/%s/%s/jre/hotspot/normal/eclipse?project=jdk";

    /**
     * 设置运行时
     * @param manifestPath 清单路径
     * @param extractDir   提取目录
     * @return {@link Path }
     */
    public static Path setupRuntime(final Path manifestPath, final Path extractDir) {
        final Instant start = Instant.now();
        final String os = getNormalizedOS();
        final String arch = getNormalizedArch();
        final int version = detectJavaVersion(manifestPath);
        ServerWorkspace.ensure(extractDir);
        final String jreUrl = JRE_URL.formatted(version, os, arch);
        Console.log("[JRE] 开始下载 | version={} os={} arch={} url={}", version, os, arch, jreUrl);
        // 生成下载路径
        final Path downloadPath = extractDir.resolve("jre-runtime.%s".formatted(StrUtil.equals(os, "windows") ? "zip" : "tar.gz"));
        // 清理路径
        FileUtil.del(downloadPath);
        // 获取Jre绝对路径
        final Path jarPath = Downloader.fetchAll(List.of(jreUrl), downloadPath).get(jreUrl);
        if (Files.notExists(jarPath)) {
            Console.error("[JRE] 提取失败");
            return null;
        }
        Console.log(
                "[JRE] 下载完成 | file={} size={} duration={}",
                jarPath.toAbsolutePath(),
                formatBytes(FileUtil.size(jarPath.toFile())),
                Duration.between(start, Instant.now())
        );
        Console.log("[JRE] 开始解压 | file={}", jarPath);
        ServerWorkspace.EXTRACT_FILES.callWithRuntimeException(jarPath, extractDir);
        // 获取Jre目录
        final Path jreHome = findNewSubdirectory(
                FileUtil.loopFiles(extractDir.toFile()).stream().filter(Objects::nonNull)
                        .map(File::toPath).collect(Collectors.toList()),
                extractDir.resolve(".jre-runtime")).orElseThrow(() -> new RuntimeException("Jre提取失败")
        );
        Console.log("[JRE] 解压完成 | home={}", jreHome);
        FileUtil.del(jarPath);
        Console.log("[JRE] 临时包已清理 | file={}", jarPath);
        return jreHome.toAbsolutePath();
    }

    /**
     * 检测Java版本
     * @param manifestPath 清单路径
     * @return int
     */
    private static int detectJavaVersion(final Path manifestPath) {
        return Optional.of(JSONUtil.parseObj(FileUtil.readUtf8String(manifestPath.toFile())))
                .map(json -> json.getByPath("minecraft.version", String.class))
                .map(ver -> Arrays.stream(ver.split("\\.")).mapToInt(Integer::parseInt).toArray())
                .filter(v -> v.length >= 2)
                .map(v -> switch (v[1]) {
                    case 17, 18, 19 -> v[0] == 1 && v[1] == 19 && v.length > 2 && v[2] >= 4 ? 21 : 17;
                    default -> v[1] > 20 ? 21 : 8;
                })
                .orElseThrow(() -> new RuntimeException("清单格式无效，请检查整合包"));
    }

    /**
     * 查找新子目录
     * @param preExisting 预先存在
     * @param extractDir  提取目录
     * @return {@link Optional }<{@link Path }>
     */
    private static Optional<Path> findNewSubdirectory(final List<Path> preExisting, final Path extractDir) {
        try (final Stream<Path> stream = Files.list(extractDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(dir -> !preExisting.contains(dir))
                    .filter(JreFetcher::containsJreFiles)
                    .findFirst();
        } catch (final Exception e) {
            throw new RuntimeException("Jre目录扫描失败", e);
        }
    }

    /**
     * 包含 JRE 文件
     * @param dir dir
     * @return boolean
     */
    private static boolean containsJreFiles(final Path dir) {
        return Files.exists(dir.resolve("bin/java")) ||
                Files.exists(dir.resolve("bin/java.exe"));
    }

    /**
     * 获取操作系统名
     * @return {@link String }
     */
    private static String getNormalizedOS() {
        return Optional.ofNullable(System.getProperty("os.name"))
                .map(String::toLowerCase)
                .map(os -> {
                    if (os.contains("win")) return "windows";
                    if (os.contains("mac")) return "mac";
                    if (os.contains("nix") || os.contains("nux")) return "linux";
                    throw new UnsupportedOperationException("不支持的操作系统: %s".formatted(os));
                })
                .orElseThrow(() -> new RuntimeException("Jre操作系统检测失败"));
    }

    /**
     * 获得架构
     * @return {@link String }
     */
    private static String getNormalizedArch() {
        return Optional.ofNullable(System.getProperty("os.arch"))
                .map(arch -> Pattern.compile("^(x8664|amd64|ia32e|em64t|x64|x86_64)$", Pattern.CASE_INSENSITIVE)
                        .matcher(arch).matches() ? "x64" : arch)
                .map(arch -> "aarch64".equalsIgnoreCase(arch) ? "aarch64" : arch)
                .orElseThrow(() -> new RuntimeException("Jre架构检测失败"));
    }

    /**
     * 格式化字节
     * @param bytes 字节
     * @return {@link String }
     */
    private static String formatBytes(final long bytes) {
        if (bytes < 1024) return "%d B".formatted(bytes);
        final double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        final double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        final double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}