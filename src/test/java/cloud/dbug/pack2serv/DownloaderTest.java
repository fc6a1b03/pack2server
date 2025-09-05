package cloud.dbug.pack2serv;

import cloud.dbug.pack2serv.common.Downloader;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 下载器测试
 * @author xuhaifeng
 * @date 2025-09-05
 */
public class DownloaderTest {
    /**
     * 临时目录
     */
    private static final String TEMP = "Temp";
    /**
     * 根目录
     */
    private static final String DIR = System.getProperty("user.dir");

    @Test
    public void single() {
        final String url = "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.7/picocli-4.7.7.jar";
        Downloader.fetch(url, FileUtil.file(DIR, TEMP, StrUtil.subAfter(url, "/", Boolean.TRUE)).toPath());
    }

    @Test
    public void multipart() {
        Downloader.fetchAll(
                List.of(
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.6/picocli-4.7.6.jar",
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.5/picocli-4.7.5.jar",
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.4/picocli-4.7.4.jar",
                        "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.3/picocli-4.7.3.jar"
                ),
                FileUtil.file(DIR, TEMP).toPath()
        );
    }
}