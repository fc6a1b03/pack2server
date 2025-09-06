package cloud.dbug.pack2server.common.provider;

import cn.hutool.core.lang.Opt;
import picocli.CommandLine;

/**
 * 清单版本提供程序
 * @author 拒绝者
 * @date 2025-09-05
 */
public class ManifestVersionProvider implements CommandLine.IVersionProvider {
    /**
     * 获取版本
     * @return {@link String[] }
     */
    @Override
    public String[] getVersion() {
        return new String[]{
                Opt.ofBlankAble(getClass().getPackage().getImplementationVersion())
                        .map("pack2server %s"::formatted)
                        .orElse("dev")
        };
    }
}