package cloud.dbug.pack2serv;

import cloud.dbug.pack2serv.cli.ConvertCommand;
import cloud.dbug.pack2serv.common.provider.ManifestVersionProvider;
import picocli.CommandLine;

/**
 * 应用入口
 * @author xuhaifeng
 * @date 2025-09-05
 */
@CommandLine.Command(
        name = "pack2serv",
        header = "CurseForge 模组包 -> 可运行的服务器目录",
        versionProvider = ManifestVersionProvider.class,
        mixinStandardHelpOptions = true,
        subcommands = {ConvertCommand.class}
)
public class Pack2server implements Runnable {
    @Override
    public void run() {
        System.out.println("运行“pack2serv convert --help”了解用法");
    }

    public static void main(final String[] args) {
        System.exit(new CommandLine(new Pack2server()).execute(args));
    }
}