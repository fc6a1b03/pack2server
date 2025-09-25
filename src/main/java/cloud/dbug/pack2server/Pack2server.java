package cloud.dbug.pack2server;

import cloud.dbug.pack2server.cli.ConvertCommand;
import cloud.dbug.pack2server.common.provider.ManifestVersionProvider;
import cn.hutool.core.lang.Console;
import picocli.CommandLine;

/**
 * 应用入口
 * @author 拒绝者
 * @date 2025-09-05
 */
@CommandLine.Command(
        name = "pack2serv",
        header = "CurseForge mod package -> runnable server directory",
        versionProvider = ManifestVersionProvider.class,
        mixinStandardHelpOptions = true,
        subcommands = {ConvertCommand.class}
)
public class Pack2server implements Runnable {
    @Override
    public void run() {
        Console.log("Run 'pack2server convert - h' to understand its usage");
    }

    static void main(final String[] args) {
        System.exit(new CommandLine(new Pack2server()).execute(args));
    }
}