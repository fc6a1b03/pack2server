package cloud.dbug.pack2server;

import cloud.dbug.pack2server.common.ConstantPool;
import cloud.dbug.pack2server.common.detector.ServerModDetector;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

/**
 * 服务文件测试
 * @author xuhaifeng
 * @date 2025-09-06
 */
public class ServerFileTest {
    @Test
    @DisplayName("服务器Mods检测器")
    public void serverModsDetector() {
        final File file = FileUtil.file(ConstantPool.TEST_DIR, "cloth-config-19.0.147-fabric.jar");
        Console.log("检查结果 | {}", ServerModDetector.detect(file.toPath()));
    }

    @Test
    @DisplayName("服务器目录初始化")
    public void serverDirectoryInit() {
        ConstantPool.iniDir(ConstantPool.TEST_DIR);
    }
}