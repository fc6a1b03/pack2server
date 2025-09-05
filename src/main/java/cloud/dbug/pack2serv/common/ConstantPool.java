package cloud.dbug.pack2serv.common;

import cn.hutool.core.io.file.PathUtil;
import lombok.SneakyThrows;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 常量池
 * @author xuhaifeng
 * @date 2025-09-05
 */
public class ConstantPool {
    /**
     * 临时目录
     */
    public static final String TEMP = "Temp";
    /**
     * 位置
     */
    public static final String LOCATION = "Location";
    /**
     * 根目录
     */
    public static final String DIR = System.getProperty("user.dir");

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