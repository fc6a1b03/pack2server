package cloud.dbug.pack2serv;

import cloud.dbug.pack2serv.common.ConstantPool;
import cloud.dbug.pack2serv.common.CurseForgeBulkDownloader;
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
    @Test
    public void single() {
        final String url = "https://repo1.maven.org/maven2/info/picocli/picocli/4.7.7/picocli-4.7.7.jar";
        Downloader.fetch(url, FileUtil.file(ConstantPool.DIR, ConstantPool.TEMP, StrUtil.subAfter(url, "/", Boolean.TRUE)).toPath());
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
                FileUtil.file(ConstantPool.DIR, ConstantPool.TEMP).toPath()
        );
    }

    @Test
    public void curseForgeBulkDownloader() {
        System.setProperty("CF_API_KEY", "123123123");
        CurseForgeBulkDownloader.fetch(
                FileUtil.file("E:\\备份\\modpacks\\test\\Fabulously.Optimized-10.2.0-beta.6\\manifest.json").toPath(),
                FileUtil.file(ConstantPool.DIR, ConstantPool.TEMP).toPath()
        );
    }
}