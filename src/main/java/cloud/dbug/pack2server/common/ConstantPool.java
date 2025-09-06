package cloud.dbug.pack2server.common;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.PathUtil;
import lombok.SneakyThrows;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    public static final String DIR = System.getProperty("user.dir");
    /**
     * 测试目录
     */
    public static final File TEST_DIR = FileUtil.file(ConstantPool.DIR, ConstantPool.TEMP);
    /**
     * 服务目录
     */
    public static final List<String> SERVER_DIRS = List.of("mods", "cache", "logs", "versions", "libraries", "config");
    /**
     * 构建目录
     */
    public static final Consumer<File> BUILD_DIRECTORY = parent -> SERVER_DIRS.forEach(dir -> FileUtil.mkdir(FileUtil.file(parent, dir)));

    /**
     * 初始化目录
     * @param parent 父母
     */
    public static void iniDir(final File parent) {
        BUILD_DIRECTORY.accept(parent);
    }

    /**
     * 确保目录
     * @param path dir
     */
    @SneakyThrows
    public static void ensure(final Path path) {
        final Path parent = path.getParent();
        if (Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        if (PathUtil.isDirectory(path, Boolean.TRUE) && Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        if (PathUtil.isFile(path, Boolean.TRUE) && Files.exists(path)) {
            Files.delete(path);
        }
    }
}