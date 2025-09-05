package cloud.dbug.pack2serv.common;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.file.PathUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import lombok.SneakyThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 下载器
 * @author xuhaifeng
 * @date 2025-09-05
 */
public class Downloader {
    /**
     * 每块 4 MB
     */
    private static final long CHUNK_SIZE = 4 * 1024 * 1024;
    /**
     * 8 MB 以上才分块
     */
    private static final long CHUNK_THRESHOLD = 8 * 1024 * 1024;
    /**
     * 全局 HTTP/2 客户端
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    /**
     * 全局虚拟线程池
     */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private Downloader() {
    }

    /**
     * 批量下载（并发）
     * @param uris      地址列表
     * @param targetDir 本地目录（保持原文件名）
     * @return 成功数
     */
    @SuppressWarnings("UnusedReturnValue")
    public static long fetchAll(final List<String> uris, final Path targetDir) {
        if (CollUtil.isEmpty(uris)) {
            return 0;
        }
        // 确保目标目录存在f
        ensure(targetDir);
        return uris.stream().filter(Objects::nonNull)
                .map(u -> EXECUTOR.submit(() -> fetch(u, targetDir.resolve(StrUtil.subAfter(u, "/", Boolean.TRUE)), Boolean.TRUE)))
                .map(Downloader::get).filter(r -> r == 0).count();
    }

    /**
     * 公开入口
     * @param uri    下载地址
     * @param target 本地文件
     * @return Path 成功；null 失败
     */
    public static Path fetch(final String uri, final Path target) {
        if (StrUtil.isEmpty(uri)) {
            return null;
        }
        if (fetch(uri, target, Boolean.TRUE) == 0) {
            return target;
        }
        return null;
    }

    /**
     * 公开入口
     * @param uri    下载地址
     * @param target 本地文件
     * @param resume 是否允许断点续传
     * @return 0 成功；1 失败
     */
    public static int fetch(final String uri, final Path target, final boolean resume) {
        if (StrUtil.isEmpty(uri)) {
            return 0;
        }
        try {
            final long contentLength = contentLength(uri);
            if (contentLength < 0) {
                throw new IOException("无法获取文件大小");
            }
            // 确保目标目录存在
            ensure(target);
            // 小文件直接单线程
            if (contentLength <= CHUNK_THRESHOLD) {
                downloadSingle(uri, target, resume);
            } else {
                downloadMulti(uri, target, contentLength, resume);
            }
            return 0;
        } catch (final Exception e) {
            Console.error(e);
            return 1;
        }
    }

    /**
     * 单线程直下
     * @param uri    统一资源标识符
     * @param target 目标
     * @param resume 简历
     * @throws IOException          IOException
     * @throws InterruptedException 中断异常
     */
    private static void downloadSingle(final String uri, final Path target, final boolean resume) throws IOException, InterruptedException {
        final long already = resume && Files.exists(target) ? Files.size(target) : 0;
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri)).GET();
        if (already > 0) {
            builder.header("Range", "bytes=%d-".formatted(already));
        }
        final HttpResponse<InputStream> resp = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        final int status = resp.statusCode();
        if (status != 200 && status != 206) {
            throw new IOException("单块下载异常 HTTP %d".formatted(status));
        }
        try (final InputStream in = resp.body()) {
            if (already > 0) {
                try (final OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    in.transferTo(out);
                }
            } else {
                try (final OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                }
            }
        }
    }

    /**
     * 分块并发
     * @param uri    统一资源标识符
     * @param target 目标
     * @param total  总计
     * @param resume 简历
     * @throws Exception 例外
     */
    private static void downloadMulti(final String uri, final Path target, final long total, final boolean resume) throws Exception {
        long already = resume && Files.exists(target) ? Files.size(target) : 0;
        if (already == total) {
            return;
        }
        if (already > total) {
            Files.deleteIfExists(target);
            already = 0;
        }
        // 执行分块下载
        buildChunks(total, already).stream().filter(Objects::nonNull)
                .map(ch -> EXECUTOR.submit(() -> downloadChunk(uri, target, ch)))
                // 等待全部完成
                .forEach(Downloader::get);
    }

    /**
     * 构建块
     * @param total   总计
     * @param already 已经
     * @return {@link List }<{@link Chunk }>
     */
    private static List<Chunk> buildChunks(final long total, final long already) {
        long start = already;
        final List<Chunk> list = new ArrayList<>();
        while (start < total) {
            final long end = Math.min(start + CHUNK_SIZE - 1, total - 1);
            list.add(new Chunk(start, end));
            start = end + 1;
        }
        return list;
    }

    /**
     * 下载块
     * @param uri    统一资源标识符
     * @param target 目标
     * @param ch     中前卫
     * @throws IOException          IOException
     * @throws InterruptedException 中断异常
     */
    private static Void downloadChunk(final String uri, final Path target, final Chunk ch) throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .header("Range", "bytes=%d-%d".formatted(ch.start, ch.end))
                .build();
        final HttpResponse<InputStream> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 206) {
            throw new IOException("块 %s 响应异常 %d".formatted(ch, resp.statusCode()));
        }
        try (final InputStream in = resp.body()) {
            try (final FileChannel fc = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                fc.position(ch.start);
                fc.transferFrom(Channels.newChannel(in), ch.start, ch.end - ch.start + 1);
            }
        }
        return null;
    }

    /**
     * 执行
     * @param future 未来
     * @return T
     */
    @SneakyThrows
    private static <T> T get(final Future<T> future) {
        return future.get();
    }

    /**
     * 内容长度
     * @param uri 统一资源标识符
     * @return long
     * @throws IOException          IOException
     * @throws InterruptedException 中断异常
     */
    private static long contentLength(final String uri) throws IOException, InterruptedException {
        return HTTP_CLIENT.send(
                HttpRequest.newBuilder(URI.create(uri))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding()
        ).headers().firstValueAsLong("Content-Length").orElse(-1);
    }

    /**
     * 确保目录
     * @param path dir
     */
    @SneakyThrows
    private static void ensure(final Path path) {
        final Path parent = path.getParent();
        if (Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        if (PathUtil.isDirectory(path, Boolean.TRUE) && Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        if (PathUtil.isFile(path, Boolean.TRUE) && Files.exists(path)) {
            Files.delete(path);
        }
    }

    /**
     * 块
     * @author xuhaifeng
     * @date 2025-09-05
     */
    private record Chunk(long start, long end) {
    }
}