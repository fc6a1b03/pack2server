package cloud.dbug.pack2serv.cli;

import cloud.dbug.pack2serv.common.Downloader;
import cloud.dbug.pack2serv.entity.Source;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Opt;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * 转换命令
 * @author xuhaifeng
 * @date 2025-09-05
 */
@CommandLine.Command(
        name = "convert",
        description = "将 CurseForge 模组包 转换为服务器目录",
        mixinStandardHelpOptions = true
)
public class ConvertCommand implements Callable<Integer> {
    /**
     * 临时目录
     */
    private static final String TEMP = "Temp";
    @CommandLine.ArgGroup(multiplicity = "1", heading = "输入源（二选一）:%n")
    private Source source;
    @CommandLine.Option(names = {"-o", "--output"}, defaultValue = "./server", description = "输出服务器目录（默认：./server）")
    private Path output;
    @CommandLine.Option(names = {"-f", "--force"}, description = "覆盖现有目录")
    private boolean force;

    @Override
    public Integer call() {
        // 获取模组包路径
        final Path path = Opt.ofNullable(source.getZip())
                .orElseGet(() -> Downloader.fetch(source.getUrl(), FileUtil.file(output.toFile(), TEMP, source.getName()).toPath()));

        return 0;
    }
}