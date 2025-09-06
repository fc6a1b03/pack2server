package cloud.dbug.pack2server.entity;

import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import picocli.CommandLine;

import java.net.URL;
import java.nio.file.Path;

/**
 * 来源
 * @author xuhaifeng
 * @date 2025-09-05
 */
@Data
public class Source {
    @CommandLine.Option(names = {"-u", "--url"}, description = "CurseForge url")
    private URL url;
    @CommandLine.Option(names = {"-z", "--zip"}, description = "CurseForge zip")
    private Path zip;

    /**
     * 获取URL
     * @return {@link String }
     */
    public String getUrl() {
        return Opt.ofNullable(url).map(URL::toString).orElse("");
    }

    /**
     * 获取名称
     * @return {@link String }
     */
    public String getName() {
        return StrUtil.subAfter(getUrl(), "/", Boolean.TRUE);
    }
}