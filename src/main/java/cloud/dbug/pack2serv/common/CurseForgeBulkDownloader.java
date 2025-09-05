package cloud.dbug.pack2serv.common;

import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * CurseForge批量下载器
 * @author xuhaifeng
 * @date 2025-09-05
 */
public class CurseForgeBulkDownloader {
    /**
     * 组
     */
    private static final int GROUP = 50;
    /**
     * 单文件解析超时 ms
     */
    private static final int TIMEOUT = 8_000;
    /**
     * CurseForge api url
     */
    private static final String CF_API_URL = "https://api.curseforge.com/v1/mods/files";
    /**
     * CurseForge api密钥
     */
    private static final String CF_API_KEY = Opt.ofBlankAble(System.getenv("CF_API_KEY")).orElseGet(() -> System.getProperty("CF_API_KEY"));

    public static void fetch(final Path manifest, final Path saveDir) {
        if (Files.notExists(manifest)) {
            return;
        }
        ConstantPool.ensure(saveDir);
        final List<Mod> mods = parseMods(manifest);
        Console.log("待下载模组数量：{}", mods.size());
        // 批量获取模组下载地址
        final Map<Long, String> id2url = queryDownloadUrls(mods);
        // 批量下载
        Downloader.fetchAll(
                mods.stream().filter(Objects::nonNull)
                        .map(m -> id2url.getOrDefault(m.fileId, ""))
                        .filter(StrUtil::isNotEmpty).toList(),
                saveDir
        );
    }

    /**
     * 解析mods
     * @param manifest 清单
     * @return {@link List }<{@link Mod }>
     */
    private static List<Mod> parseMods(final Path manifest) {
        final JSONArray files = JSONUtil.readJSONObject(manifest.toFile(), Charset.defaultCharset()).getJSONArray("files");
        return IntStream.range(0, files.size())
                .mapToObj(files::getJSONObject).filter(Objects::nonNull)
                .map(o -> new Mod(o.getLong("projectID"), o.getLong("fileID")))
                .toList();
    }

    /**
     * 查询下载网址
     * @param mods 模组
     * @return {@link Map }<{@link Long }, {@link String }>
     */
    private static Map<Long, String> queryDownloadUrls(final List<Mod> mods) {
        final List<Long> ids = mods.stream().map(m -> m.fileId).toList();
        final Map<Long, String> map = new HashMap<>(ids.size());
        // 50个一组
        IntStream.iterate(0, i -> i < ids.size(), i -> i + GROUP)
                .mapToObj(i -> ids.subList(i, Math.min(i + GROUP, ids.size())))
                .map(sub -> JSONUtil.createObj().set("fileIds", sub).toStringPretty())
                .forEach(body -> {
                    try (final HttpResponse resp = HttpRequest.post(CF_API_URL)
                            .header("x-api-key", CF_API_KEY)
                            .header("Content-Type", "application/json")
                            .body(body).timeout(TIMEOUT).execute()) {
                        final JSONArray arr = JSONUtil.parseObj(resp.body()).getJSONArray("data");
                        IntStream.range(0, arr.size()).mapToObj(arr::getJSONObject)
                                .forEach(o -> map.put(o.getLong("id"), o.getStr("downloadUrl")));
                    }
                });
        return map;
    }

    /**
     * 模组
     * @author xuhaifeng
     * @date 2025-09-05
     */
    private record Mod(long projectId, long fileId) {
    }
}