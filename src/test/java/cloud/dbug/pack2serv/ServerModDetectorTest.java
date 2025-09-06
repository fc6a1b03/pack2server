package cloud.dbug.pack2serv;

import cloud.dbug.pack2serv.common.detector.ServerModDetector;
import cn.hutool.core.lang.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

/**
 * 服务器mods检测器测试
 * @author xuhaifeng
 * @date 2025-09-06
 */
public class ServerModDetectorTest {
    @Test
    @DisplayName("检测器测试")
    public void test() {
        final String path = "D:\\project\\server\\pack2server\\Temp\\cloth-config-19.0.147-fabric.jar";
        Console.log("检查结果 | {}", ServerModDetector.detect(Paths.get(path)));
    }
}