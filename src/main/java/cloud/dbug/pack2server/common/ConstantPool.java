package cloud.dbug.pack2server.common;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.PathUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.lang.func.Supplier2;
import cn.hutool.core.util.StrUtil;
import lombok.SneakyThrows;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 常量池
 * @author xuhaifeng
 * @date 2025-09-05
 */
public class ConstantPool {
    /**
     * 模组目录
     */
    public static final String MOD = "mods";
    /**
     * 临时目录
     */
    public static final String TEMP = "Temp";
    /**
     * 根目录
     */
    public static final String HOME = System.getProperty("user.dir");
    /**
     * 测试目录
     */
    public static final File TEST_DIR = FileUtil.file(ConstantPool.HOME, ConstantPool.TEMP);
    /**
     * Java
     */
    public static final String JAVA_PROGRAM = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    /**
     * 服务目录
     */
    public static final List<String> SERVER_DIRS = List.of("mods", "cache", "logs", "versions", "libraries", "config");
    /**
     * 构建目录
     */
    public static final Consumer<File> BUILD_DIR = parent -> SERVER_DIRS.forEach(dir -> {
        ConstantPool.ensure(Paths.get(dir));
        FileUtil.mkdir(FileUtil.file(parent, dir));
    });
    /**
     * 复制目录
     */
    public static final Supplier2<File, File, File> COPY_DIR = (src, dest) -> {
        ConstantPool.ensure(dest.toPath());
        return FileUtil.copyContent(src, dest, Boolean.TRUE);
    };

    /**
     * 确保目录
     * @param path dir
     */
    @SneakyThrows
    public static void ensure(final Path path) {
        if (Objects.nonNull(path)) {
            final Path parent = path.getParent();
            if (Objects.nonNull(parent) && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
            if (PathUtil.isFile(path, Boolean.TRUE) && Files.exists(path)) {
                Files.delete(path);
            }
        }
    }

    /**
     * 获取名称
     * @param path 路径
     * @return {@link String }
     */
    public static String getName(final String path) {
        return Opt.ofBlankAble(StrUtil.subAfter(path, "/", Boolean.TRUE))
                .orElseGet(() -> StrUtil.subAfter(path, "\\", Boolean.TRUE));
    }
}