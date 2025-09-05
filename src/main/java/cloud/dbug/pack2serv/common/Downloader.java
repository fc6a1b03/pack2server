package cloud.dbug.pack2serv.common;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.thread.ThreadUtil;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

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
    /**
     * 任务启动时间（毫秒）
     */
    private static volatile long START_MS;
    /**
     * 全局已落盘字节
     */
    private static final LongAdder GLOBAL_BYTES = new LongAdder();
    /**
     * 全局总字节
     */
    private static final LongAdder GLOBAL_TOTAL = new LongAdder();
    /**
     * 最后全局
     */
    private static final AtomicLong LAST_GLOBAL = new AtomicLong(-1);
    /**
     * 每个文件已落盘字节<br/>
     * KEY: 目标文件路径<br/>
     * VALUE: 该文件已落盘字节计数器
     */
    private static final ConcurrentMap<Path, LongAdder> FILE_BYTES = new ConcurrentHashMap<>();
    /**
     * 每个文件总大小<br/>
     * KEY: 目标文件路径<br/>
     * VALUE: 该文件总字节数
     */
    private static final ConcurrentMap<Path, Long> FILE_TOTAL = new ConcurrentHashMap<>();

    private Downloader() {
    }

    /**
     * 批量下载（并发）<br/>
     * 1. 先统计所有文件总大小<br/>
     * 2. 启动后台虚拟线程每秒打印全局进度<br/>
     * 3. 提交所有下载任务并等待完成<br/>
     * 4. 返回成功数量
     * @param uris      尤里斯
     * @param targetDir 目标目录
     * @return long
     */
    @SuppressWarnings("UnusedReturnValue")
    public static long fetchAll(final List<String> uris, final Path targetDir) {
        if (CollUtil.isEmpty(uris)) {
            return 0;
        }
        ConstantPool.ensure(targetDir);
        // 初始化计数器
        GLOBAL_BYTES.reset();
        GLOBAL_TOTAL.reset();
        FILE_BYTES.clear();
        FILE_TOTAL.clear();
        // 并行获取所有文件大小
        uris.parallelStream().forEach(u -> {
            try {
                final long len = contentLength(u);
                if (len > 0) {
                    GLOBAL_TOTAL.add(len);
                }
            } catch (final Exception ignore) {
            }
        });
        // 启动后台进度条
        START_MS = System.currentTimeMillis();
        EXECUTOR.submit(() -> {
            try {
                while (GLOBAL_BYTES.sum() < GLOBAL_TOTAL.sum()) {
                    ThreadUtil.sleep(1000);
                    printTotal();
                }
            } finally {
                // 保证 100% 那一行出现
                printTotal();
            }
        });
        // 并发下载
        return uris.stream().filter(Objects::nonNull)
                .map(u ->
                        EXECUTOR.submit(() -> fetch(u, targetDir.resolve(StrUtil.subAfter(u, "/", Boolean.TRUE)), Boolean.TRUE))
                )
                .map(Downloader::get).filter(r -> r == 0).count();
    }

    /**
     * 单文件下载公开入口（默认允许断点续传）
     * @param uri    统一资源标识符
     * @param target 目标
     * @return {@link Path }
     */
    public static Path fetch(final String uri, final Path target) {
        if (StrUtil.isEmpty(uri)) {
            return null;
        }
        return fetch(uri, target, true) == 0 ? target : null;
    }

    /**
     * 单文件下载公开入口（可控制是否续传）<br/>
     * 1. 获取文件大小<br/>
     * 2. 初始化该文件的进度计数器<br/>
     * 3. 路由到单线程 or 分块下载<br/>
     * 4. 下载完成后打印 100%
     * @param uri    统一资源标识符
     * @param target 目标
     * @param resume 简历
     * @return int
     */
    public static int fetch(final String uri, final Path target, final boolean resume) {
        if (StrUtil.isEmpty(uri)) return 0;
        try {
            final long total = contentLength(uri);
            if (total < 0) {
                throw new IOException("无法获取文件大小");
            }
            ConstantPool.ensure(target);
            // 初始化该文件进度
            FILE_TOTAL.put(target, total);
            final LongAdder fileAdder = new LongAdder();
            FILE_BYTES.put(target, fileAdder);
            if (total <= CHUNK_THRESHOLD) {
                downloadSingle(uri, target, resume, fileAdder);
            } else {
                downloadMulti(uri, target, total, resume, fileAdder);
            }
            /* 强制置为 100% 并打印 */
            fileAdder.reset();
            fileAdder.add(total);
            printFile(target);
            return 0;
        } catch (final Exception e) {
            Console.error(e);
            return 1;
        }
    }

    /**
     * 单线程直线下载
     * @param uri       统一资源标识符
     * @param target    目标
     * @param resume    简历
     * @param fileAdder 文件加法器
     * @throws IOException          IOException
     * @throws InterruptedException 中断异常
     */
    private static void downloadSingle(final String uri,
                                       final Path target,
                                       final boolean resume,
                                       final LongAdder fileAdder) throws IOException, InterruptedException {
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
        // 写入并统计
        try (final InputStream in = resp.body();
             final OutputStream out = Files.newOutputStream(target, already > 0 ? StandardOpenOption.APPEND : StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            final long before = Files.size(target);
            in.transferTo(out);
            final long increment = Files.size(target) - before;
            fileAdder.add(increment);
            GLOBAL_BYTES.add(increment);
        }
    }

    /**
     * 分块并发下载
     * @param uri       统一资源标识符
     * @param target    目标
     * @param total     总计
     * @param resume    简历
     * @param fileAdder 文件加法器
     * @throws Exception 例外
     */
    private static void downloadMulti(final String uri, final Path target, final long total, final boolean resume, final LongAdder fileAdder) throws Exception {
        long already = resume && Files.exists(target) ? Files.size(target) : 0;
        // 已完整
        if (already == total) {
            return;
        }
        // 异常残文件
        if (already > total) {
            Files.deleteIfExists(target);
            already = 0;
        }
        // 提交所有块任务并等待
        buildChunks(total, already).stream().filter(Objects::nonNull)
                .map(ch -> EXECUTOR.submit(() -> downloadChunk(uri, target, ch, fileAdder)))
                .forEach(Downloader::get);
    }

    /**
     * 构建分块区间
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
     * 下载单个分块
     * @param uri       统一资源标识符
     * @param target    目标
     * @param chunk     块
     * @param fileAdder 文件加法器
     * @throws IOException          IOException
     * @throws InterruptedException 中断异常
     */
    private static Void downloadChunk(final String uri, final Path target, final Chunk chunk, final LongAdder fileAdder) throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                .header("Range", "bytes=%d-%d".formatted(chunk.start, chunk.end))
                .build();
        final HttpResponse<InputStream> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 206) {
            throw new IOException("块 %s 响应异常 %d".formatted(chunk, resp.statusCode()));
        }
        try (final InputStream in = resp.body();
             final FileChannel fc = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            fc.position(chunk.start);
            final long transferred = fc.transferFrom(Channels.newChannel(in), chunk.start, chunk.end - chunk.start + 1);
            fileAdder.add(transferred);
            GLOBAL_BYTES.add(transferred);
        }
        return null;
    }

    /**
     * 获取远程文件大小
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
     * 等待 Future 完成
     * @param future 未来
     * @return {@link T }
     */
    @SneakyThrows
    private static <T> T get(final Future<T> future) {
        return future.get();
    }

    /**
     * 打印全局总进度
     */
    private static void printTotal() {
        final long done = GLOBAL_BYTES.sum();
        final long total = GLOBAL_TOTAL.sum();
        if (total <= 0) {
            return;
        }
        // 无变化直接跳过
        if (LAST_GLOBAL.compareAndSet(done, done)) {
            return;
        }
        LAST_GLOBAL.set(done);
        Console.log(
                ">>> 总计 {}/{} ({}%)  速度:{}",
                format(done), format(total), (int) (done * 100 / total),
                formatSpeed(done * 1000 / Math.max(1, System.currentTimeMillis() - START_MS))
        );
    }

    /**
     * 打印单文件进度
     * @param target 目标
     */
    private static void printFile(final Path target) {
        final LongAdder fileAdder = FILE_BYTES.get(target);
        final Long total = FILE_TOTAL.get(target);
        if (Objects.isNull(fileAdder) || Objects.isNull(total) || total <= 0) return;
        final long done = fileAdder.sum();
        final int pct = (int) (done * 100 / total);
        Console.log(">>> 完成 {}  {}/{} ({}%)", target.getFileName(), format(done), format(total), pct);
    }

    /**
     * 字节格式化
     * @param bytes 字节
     * @return {@link String }
     */
    private static String format(final long bytes) {
        if (bytes < 1024) return bytes + "B";
        final double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1fK", kb);
        final double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1fM", mb);
        final double gb = mb / 1024.0;
        return String.format("%.1fG", gb);
    }

    /**
     * 速度格式化
     * @param bps 字节速度
     * @return {@link String }
     */
    private static String formatSpeed(final long bps) {
        return "%s/s".formatted(format(bps));
    }

    /**
     * 分块区间记录
     * @author xuhaifeng
     * @date 2025-09-05
     */
    private record Chunk(long start, long end) {
    }
}