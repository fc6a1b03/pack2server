package cloud.dbug.pack2server.common.detector;

import cloud.dbug.pack2server.common.detector.enums.Side;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.experimental.UtilityClass;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 服务器mods检测器
 * @author xuhaifeng
 * @date 2025-09-06
 */
@UtilityClass
public class ServerModDetector {
    /**
     * 检测
     * @param jar jar路径
     * @return {@link Side }
     */
    public static Side detect(final Path jar) {
        try (final ZipFile zf = new ZipFile(jar.toFile())) {
            // Fabric / Quilt
            final Optional<Side> f = fabricSide(zf);
            if (f.isPresent()) {
                return f.get();
            }
            // Forge / NeoForge
            final Optional<Side> fg = forgeSide(zf);
            return fg.orElseGet(() -> byteCodeSide(zf));
        } catch (final Exception ignore) {
            return Side.NONE;
        }
    }

    /**
     * fabric.mod.json 解析
     * @param zf 压缩文件
     * @return {@link Optional }<{@link Side }>
     */
    private static Optional<Side> fabricSide(final ZipFile zf) {
        final ZipEntry entry = zf.getEntry("fabric.mod.json");
        if (Objects.isNull(entry)) return Optional.empty();
        try {
            final JSONObject root = JSONUtil.parseObj(IoUtil.readUtf8(zf.getInputStream(entry)));
            final boolean hasClient = root.getJSONObject("entrypoints").containsKey("client");
            final boolean hasServer = root.getJSONObject("entrypoints").containsKey("server");
            if (!hasClient && hasServer) return Optional.of(Side.SERVER);
            if (hasClient && !hasServer) return Optional.of(Side.CLIENT);
            return Optional.of(Side.BOTH);
        } catch (final Exception ignore) {
            return Optional.empty();
        }
    }

    /**
     * META-INF/mods.toml 解析
     * @param zf zf
     * @return {@link Optional }<{@link Side }>
     */
    private static Optional<Side> forgeSide(final ZipFile zf) {
        final ZipEntry entry = zf.getEntry("META-INF/mods.toml");
        if (Objects.isNull(entry)) return Optional.empty();
        try {
            final String txt = IoUtil.readUtf8(zf.getInputStream(entry));
            // 简单状态机：只关心 [[mods]] 块里的 side=xxx
            final String[] lines = txt.split("\n");
            boolean client = Boolean.FALSE, server = Boolean.FALSE;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("side=")) {
                    final String v = line.split("=")[1].trim().toLowerCase(Locale.ROOT);
                    switch (v) {
                        case "\"client\"" -> client = Boolean.TRUE;
                        case "\"server\"" -> server = Boolean.TRUE;
                        case "\"both\"" -> {
                            client = Boolean.TRUE;
                            server = Boolean.TRUE;
                        }
                    }
                }
            }
            if (server && !client) return Optional.of(Side.SERVER);
            if (client && !server) return Optional.of(Side.CLIENT);
            return Optional.of(Side.BOTH);
        } catch (final Exception ignore) {
            return Optional.empty();
        }
    }

    /**
     * 字节码扫描：@OnlyIn / DistExecutor / Mixin side
     * @param zf zf
     * @return {@link Side }
     */
    @SuppressWarnings("SpellCheckingInspection")
    private static Side byteCodeSide(final ZipFile zf) {
        boolean clientMarker = Boolean.FALSE, serverMarker = Boolean.FALSE;
        for (final ZipEntry e : zf.stream().toList()) {
            if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
            try {
                final String utf8 = StrUtil.utf8Str(IoUtil.readBytes(zf.getInputStream(e)));
                if (utf8.contains("Lnet/minecraftforge/api/distmarker/OnlyIn;")) {
                    if (utf8.contains("DEDICATED_SERVER")) serverMarker = Boolean.TRUE;
                    if (utf8.contains("CLIENT")) clientMarker = Boolean.TRUE;
                }
                if (utf8.contains("\"side\":\"SERVER\"")) serverMarker = Boolean.TRUE;
                if (utf8.contains("DistExecutor") && utf8.contains("runWhenOn")) {
                    if (utf8.contains("Dist.CLIENT")) clientMarker = Boolean.TRUE;
                    if (utf8.contains("Dist.DEDICATED_SERVER")) serverMarker = Boolean.TRUE;
                }
            } catch (final Exception ignore) {
            }
        }
        if (serverMarker && !clientMarker) return Side.SERVER;
        if (clientMarker && !serverMarker) return Side.CLIENT;
        return Side.BOTH;
    }
}