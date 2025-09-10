package cloud.dbug.pack2server.common.downloader;

import cloud.dbug.pack2server.common.ServerWorkspace;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 下载器
 * @author 拒绝者
 * @date 2025-09-05
 */
@UtilityClass
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
            .priority(1)
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    /**
     * 获取全部
     */
    private static final AtomicBoolean FETCH_ALL = new AtomicBoolean(Boolean.FALSE);
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
     * 最后一个文件
     */
    private static final AtomicReference<String> LAST_FILE = new AtomicReference<>();
    /**
     * 每个文件总大小<br/>
     * KEY: 目标文件路径<br/>
     * VALUE: 该文件总字节数
     */
    private static final ConcurrentMap<Path, Long> FILE_TOTAL = new ConcurrentHashMap<>();
    /**
     * 每个文件已落盘字节<br/>
     * KEY: 目标文件路径<br/>
     * VALUE: 该文件已落盘字节计数器
     */
    private static final ConcurrentMap<Path, LongAdder> FILE_BYTES = new ConcurrentHashMap<>();
    /**
     * 文件锁
     */
    private static final ConcurrentMap<Path, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    /**
     * 批量下载（并发）<br/>
     * 1. 先统计所有文件总大小<br/>
     * 2. 启动后台虚拟线程每秒打印全局进度<br/>
     * 3. 提交所有下载任务并等待完成<br/>
     * 4. 返回成功数量
     * @param uris   尤里斯
     * @param target 目标目录
     * @return long
     */
    @SneakyThrows
    @SuppressWarnings({"UnusedReturnValue"})
    public static long fetchAll(final List<String> uris, final Path target) {
        if (CollUtil.isEmpty(uris)) {
            return 0;
        }
        FETCH_ALL.set(Boolean.TRUE);
        ServerWorkspace.ensure(target);
        // 初始化各种容器
        LAST_FILE.set("");
        FILE_BYTES.clear();
        FILE_TOTAL.clear();
        GLOBAL_BYTES.reset();
        GLOBAL_TOTAL.reset();
        // 并行获取所有文件大小
        uris.parallelStream().forEach(u -> Opt.of(contentLength(u)).filter(len -> len > 0).ifPresent(GLOBAL_TOTAL::add));
        // 启动后台进度条
        START_MS = System.currentTimeMillis();
        EXECUTOR.submit(() -> {
            try {
                while (GLOBAL_BYTES.sum() < GLOBAL_TOTAL.sum()) {
                    printTotal();
                }
            } finally {
                printTotal();
            }
        });
        // 启动下载
        return uris.parallelStream()
                .filter(Objects::nonNull)
                .map(u -> EXECUTOR.submit(() -> {
                    // 确定目录
                    final Path dir = Files.isDirectory(target) ? target : target.getParent();
                    // 解析器文件名
                    final String legal = ServerWorkspace.parseFileName(u);
                    // 空兜底
                    final String fileName = StrUtil.blankToDefault(legal, "file_%d_%d".formatted(System.currentTimeMillis(), Thread.currentThread().threadId()));
                    // 拼最终路径
                    return fetch(u, dir.resolve(fileName), Boolean.TRUE);
                }))
                .map(Downloader::get)
                .filter(r -> r == 0)
                .count();
    }

    /**
     * 单文件下载公开入口（默认允许断点续传）
     * @param uri    文件地址
     * @param target 目标
     * @return {@link Path }
     */
    public static Path fetch(final String uri, final Path target) {
        if (StrUtil.isEmpty(uri)) {
            return null;
        }
        return fetch(uri, target, Boolean.TRUE) == 0 ? target : null;
    }

    /**
     * 单文件下载公开入口（可控制是否续传）<br/>
     * 1. 获取文件大小<br/>
     * 2. 初始化该文件的进度计数器<br/>
     * 3. 路由到单线程 or 分块下载<br/>
     * 4. 下载完成后打印 100%
     * @param uri    文件地址
     * @param target 目标
     * @param resume 简历
     * @return int
     */
    public static int fetch(final String uri, final Path target, final boolean resume) {
        if (StrUtil.isEmpty(uri)) return 1;
        try {
            final long total = contentLength(uri);
            ServerWorkspace.ensure(target);
            // 初始化该文件进度
            FILE_TOTAL.put(target, total);
            final LongAdder fileAdder = new LongAdder();
            FILE_BYTES.put(target, fileAdder);
            if (total <= CHUNK_THRESHOLD) {
                // 执行单一下载
                downloadSingle(uri, target, resume, fileAdder);
            } else {
                // 计算已下载字节
                final long already = resume && Files.exists(target) ? Files.size(target) : 0;
                // 执行多部分下载
                downloadMulti(uri, target, total, already, fileAdder);
            }
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
     * @param uri       文件地址
     * @param target    目标
     * @param resume    简历
     * @param fileAdder 文件加法器
     * @throws IOException          IOException
     * @throws InterruptedException 中断异常
     */
    private static void downloadSingle(final String uri, final Path target, final boolean resume,
                                       final LongAdder fileAdder) throws IOException, InterruptedException {
        final ReentrantLock lock = FILE_LOCKS.computeIfAbsent(target, k -> new ReentrantLock());
        lock.lock();
        try {
            final long already = resume && Files.exists(target) ? Files.size(target) : 0;
            final HttpRequest.Builder builder = browserDisguise(HttpRequest.newBuilder(URI.create(uri)).GET());
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
        } finally {
            lock.unlock();
        }
    }

    /**
     * 分块并发下载
     * @param uri       文件地址
     * @param target    目标
     * @param total     总计
     * @param already   已经
     * @param fileAdder 文件加法器
     * @throws Exception 例外
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void downloadMulti(final String uri, final Path target, final long total, final long already, final LongAdder fileAdder) throws Exception {
        final ReentrantLock lock = FILE_LOCKS.computeIfAbsent(target, k -> new ReentrantLock());
        lock.lock();
        // 删除残文件
        if (already > total) {
            Files.deleteIfExists(target);
        }
        // 打开文件通道
        try (final FileChannel fc = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.SYNC)) {
            // 预分配空文件（避免稀疏文件）
            fc.position(total - 1);
            fc.write(java.nio.ByteBuffer.allocate(1));
            // 提交所有块任务
            buildChunks(total, already).stream()
                    .map(ch -> EXECUTOR.submit(() -> downloadChunk(uri, fc, ch, fileAdder)))
                    .forEach(Downloader::get);
        } finally {
            lock.unlock();
        }
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
     * @param uri       文件地址
     * @param fc        文件通道
     * @param chunk     块
     * @param fileAdder 文件加法器
     * @throws IOException          IOException
     * @throws InterruptedException 中断异常
     */
    private static Void downloadChunk(final String uri, final FileChannel fc, final Chunk chunk, final LongAdder fileAdder) throws IOException, InterruptedException {
        final HttpRequest req = browserDisguise(
                HttpRequest.newBuilder(URI.create(uri))
                        .header("Range", "bytes=%d-%d".formatted(chunk.start, chunk.end))
        ).build();
        final HttpResponse<InputStream> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 206) {
            throw new IOException("块 %s 响应异常 %d".formatted(chunk, resp.statusCode()));
        }
        try (final InputStream in = resp.body()) {
            final long transferred = fc.transferFrom(Channels.newChannel(in), chunk.start, chunk.end - chunk.start + 1);
            fileAdder.add(transferred);
            GLOBAL_BYTES.add(transferred);
        }
        return null;
    }

    /**
     * 获取远程文件大小
     * @param uri 文件地址
     * @return long
     */
    private static long contentLength(final String uri) {
        try {
            return HTTP_CLIENT.send(
                    browserDisguise(
                            HttpRequest.newBuilder(URI.create(uri)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                    ).build(),
                    HttpResponse.BodyHandlers.discarding()
            ).headers().firstValueAsLong("Content-Length").orElse(-1);
        } catch (final Exception e) {
            return -1;
        }
    }

    /**
     * 浏览器伪装
     * @param builder HTTP参数
     */
    private static HttpRequest.Builder browserDisguise(final HttpRequest.Builder builder) {
        builder.header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36");
        return builder;
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
        final long bytesDone = GLOBAL_BYTES.sum();
        final long totalBytes = GLOBAL_TOTAL.sum();
        if (totalBytes <= 0) {
            return;
        }
        if (bytesDone == LAST_GLOBAL.getAndSet(bytesDone)) {
            return;
        }
        final int width = 60;
        final int percent = (int) (bytesDone * 100 / totalBytes);
        final int filled = (int) (width * bytesDone / totalBytes);
        final String bar = StrUtil.repeat('█', filled) + (percent < 100 ? "" : '█') + StrUtil.repeat(' ', width - filled);
        Console.log("\r┃" + bar + "┃" +
                String.format("%5d%% %s/s%s",
                        percent,
                        formatSpeed(bytesDone * 1000 / Math.max(1, System.currentTimeMillis() - START_MS)),
                        Objects.isNull(LAST_FILE.get()) ? "" : LAST_FILE.get())
        );
        if (bytesDone == totalBytes) {
            Console.log("");
        }
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
        final String format = StrUtil.format(
                "{} | {}/{} | ({}%) ",
                target.getFileName(),
                format(done),
                format(total),
                (int) (done * 100 / total)
        );
        if (FETCH_ALL.get()) {
            LAST_FILE.set(" | %s".formatted(format));
        } else {
            Console.log(format);
        }
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
     * @author 拒绝者
     * @date 2025-09-05
     */
    private record Chunk(long start, long end) {
    }
}