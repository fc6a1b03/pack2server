package cloud.dbug.pack2server.common.fetcher;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.downloader.Downloader;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
     * 设置运行时
     * @param manifestPath 清单路径
     * @param jreVersion   JRE 版本
     * @param extractDir   提取目录
     * @return {@link Path }
     */
    public static Path setupRuntime(final Path manifestPath, final int jreVersion, final Path extractDir) {
        final int targetVersion = (jreVersion <= 0) ? detectJavaVersion(manifestPath) : jreVersion;
        final String os = getNormalizedOS();
        final String arch = getNormalizedArch();
        final String extension = os.equals("windows") ? "zip" : "tar.gz";
        final Path downloadPath = extractDir.resolve("jre-runtime.%s".formatted(extension));
        ServerWorkspace.ensure(extractDir);
        // 下载JRE
        Downloader.fetchAll(
                CollUtil.newArrayList(buildJreUrl(targetVersion, os, arch)),
                downloadPath
        );
        // 记录解压前目录内容
        final List<Path> preContents = FileUtil.loopFiles(extractDir.toFile())
                .stream().map(File::toPath).collect(Collectors.toList());
        // 解压压缩包
        ServerWorkspace.EXTRACT_FILES.callWithRuntimeException(downloadPath, extractDir);
        // 获取新增的JRE目录
        final Path jreHome = findNewSubdirectory(preContents, extractDir).orElseThrow(() -> new RuntimeException("Jre提取失败"));
        // 清理压缩包
        FileUtil.del(downloadPath);
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
     * 创建Jre下载地址
     * @param version 版本
     * @param os      操作系统
     * @param arch    拱
     * @return {@link String }
     */
    private static String buildJreUrl(final int version, final String os, final String arch) {
        return String.format(
                "https://api.adoptium.net/v3/binary/latest/%d/ga/%s/%s/jre/hotspot/normal/eclipse?project=jdk",
                version, os, arch
        );
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
}