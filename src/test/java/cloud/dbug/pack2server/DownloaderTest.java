package cloud.dbug.pack2server;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.downloader.Downloader;
import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 下载器测试
 * @author xuhaifeng
 * @date 2025-09-05
 */
public class DownloaderTest {
    @Test
    @DisplayName("核心-单个下载")
    public void single() {
        final String url = "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.7/picocli-4.7.7.jar";
        Downloader.fetch(url, FileUtil.file(ServerWorkspace.JAVA_PROGRAM, ServerWorkspace.TEMP, ServerWorkspace.PARSED_NAME.apply(url)).toPath());
    }

    @Test
    @DisplayName("核心-批量下载")
    public void multipart() {
        Downloader.fetchAll(
                List.of(
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.6/picocli-4.7.6.jar",
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.5/picocli-4.7.5.jar",
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.4/picocli-4.7.4.jar",
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.3/picocli-4.7.3.jar"
                ),
                FileUtil.file(ServerWorkspace.JAVA_PROGRAM, ServerWorkspace.TEMP).toPath()
        );
    }
}