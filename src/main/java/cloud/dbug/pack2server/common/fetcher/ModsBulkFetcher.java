package cloud.dbug.pack2server.common.fetcher;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cloud.dbug.pack2server.common.downloader.Downloader;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.experimental.UtilityClass;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 模组批量提取器
 * @author 拒绝者
 * @date 2025-09-05
 */
@UtilityClass
public class ModsBulkFetcher {
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

    /**
     * 获取清单内全部模组
     * @param manifest 清单文件路径
     * @param saveDir  目标保存目录
     */
    public static void fetch(final Path manifest, final Path saveDir) {
        if (Files.notExists(manifest)) {
            Console.log("WARN | MANIFEST_NOT_FOUND | 清单文件不存在，任务终止 | path={}", manifest.toAbsolutePath());
            return;
        }
        ServerWorkspace.ensure(saveDir);
        // 解析模组
        final List<Mod> mods = parseMods(manifest);
        Console.log("INFO | PARSE_COMPLETE | 模组清单，解析完成 | mods={}", mods.size());
        if (mods.isEmpty()) {
            Console.log("INFO | EMPTY_MANIFEST | 无有效模组，任务结束");
            return;
        }
        // 批量获取下载地址
        final Map<Long, String> id2url = queryDownloadUrl(mods);
        Console.log("INFO | URL_QUERY_COMPLETE | 模组下载地址，获取完成 | mods={}", id2url.size());
        // 矢量化组装下载任务
        final List<String> tasks = mods.stream().filter(Objects::nonNull)
                .map(m -> id2url.getOrDefault(m.fileId, ""))
                .filter(StrUtil::isNotEmpty).toList();
        Console.log("INFO | DOWNLOAD_START | 开始批量下载 | tasks={}", tasks.size());
        Downloader.fetchAll(tasks, saveDir);
        Console.log("INFO | DOWNLOAD_FINISH | 全部模组，下载完成 | dir={}", saveDir.toAbsolutePath());
    }

    /**
     * 解析mods
     * @param manifest 清单清单路径
     * @return {@link List }<{@link Mod }>
     */
    private static List<Mod> parseMods(final Path manifest) {
        return JSONUtil.readJSONObject(manifest.toFile(), Charset.defaultCharset())
                .getJSONArray("files")
                .stream().filter(Objects::nonNull)
                .filter(JSONObject.class::isInstance).map(JSONObject.class::cast)
                .map(o -> new Mod(o.getLong("projectID"), o.getLong("fileID")))
                .toList();
    }

    /**
     * 查询模组实际下载地址
     * @param mods 模组
     * @return {@link Map }<{@link Long }, {@link String }>
     */
    private static Map<Long, String> queryDownloadUrl(final List<Mod> mods) {
        if (CollUtil.isEmpty(mods)) {
            return Map.of();
        }
        return IntStream.iterate(0, i -> i < mods.size(), i -> i + GROUP)
                .parallel().mapToObj(i -> mods.subList(i, Math.min(i + GROUP, mods.size())))
                .map(list -> list.stream().filter(Objects::nonNull).map(Mod::fileId))
                .map(sub -> JSONUtil.createObj().set("fileIds", sub.toList()).toString())
                .flatMap(body -> {
                    try (final HttpResponse resp = HttpRequest.post(CF_API_URL)
                            .header("x-api-key", CF_API_KEY)
                            .header("Content-Type", "application/json")
                            .body(body).timeout(TIMEOUT).execute()) {
                        // 解析模组原始下载地址
                        return JSONUtil.parseObj(resp.body()).getJSONArray("data")
                                .stream().filter(Objects::nonNull).map(JSONObject.class::cast)
                                .filter(o -> StrUtil.isNotEmpty(o.getStr("downloadUrl")))
                                .map(obj -> Map.entry(obj.getLong("id"), obj.getStr("downloadUrl")));
                    } catch (final Exception e) {
                        Console.log("WARN | DOWNLOAD_URL_NOT_FOUND | 模组地址获取失败 | body={}", body);
                        return null;
                    }
                }).filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (u, v) -> v, HashMap::new));
    }

    /**
     * 模组
     * @author 拒绝者
     * @date 2025-09-05
     */
    private record Mod(long projectId, long fileId) {
    }
}