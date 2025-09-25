package cloud.dbug.pack2server.common.downloader;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 下载器
 * @author 拒绝者
 * @date 2025-09-25
 */
@UtilityClass
public class Downloader {
    /**
     * 下载缓冲区大小
     */
    private static final int BUFFER_SIZE = 8192;
    /**
     * 默认的单文件并发下载线程数
     */
    private static final int DEFAULT_THREAD_COUNT = 4;
    /**
     * HTTP请求超时时间（秒）
     */
    private static final Duration TIMEOUT_DURATION = Duration.ofSeconds(30);
    /**
     * 存储每个文件下载进度的映射
     */
    private final Map<String, DownloadProgress> progressMap = new ConcurrentHashMap<>();
    /**
     * HTTP客户端，配置为自动跟随重定向
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .priority(1)
            .connectTimeout(TIMEOUT_DURATION)
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /**
     * 批量下载多个文件
     * @param urlList         文件URL列表
     * @param targetDirectory 下载的目标目录
     * @return 下载文件的映射关系，键为原始URL，值为目标文件路径
     */
    public Map<String, Path> fetchAll(final List<String> urlList, final Path targetDirectory) {
        // 确保目标目录存在
        try {
            Files.createDirectories(targetDirectory);
        } catch (final IOException e) {
            Console.error("创建目标目录失败: {}", targetDirectory, e);
            throw new RuntimeException("无法创建目标目录", e);
        }
        // 使用虚拟线程池并发执行下载任务
        try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<CompletableFuture<Map.Entry<String, Path>>> futures = urlList.stream().filter(StrUtil::isNotEmpty)
                    .map(url -> CompletableFuture.supplyAsync(() -> {
                                try {
                                    final String fileName = extractFileName(url).orElse("downloaded_file_" + System.currentTimeMillis());
                                    final Path targetPath = targetDirectory.resolve(fileName);
                                    final Path resultPath = fetch(url, targetPath);
                                    return Map.entry(url, resultPath);
                                } catch (final Exception e) {
                                    Console.error("无法从URL下载文件: {}", url, e);
                                    return Map.entry(url, Path.of(""));
                                }
                            }, executor)
                    ).toList();
            final CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            try {
                // 等待所有下载完成
                allDone.join();
            } catch (final Exception e) {
                Console.error("批量下载时出错", e);
            }
            return futures.stream()
                    .map(CompletableFuture::join)
                    .filter(entry -> Objects.nonNull(entry.getValue()) && Files.exists(entry.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    /**
     * 单个文件下载
     * @param fileUrl    文件URL
     * @param targetPath 下载的目标路径
     * @return 下载文件的目标路径
     */
    @SneakyThrows
    public Path fetch(final String fileUrl, final Path targetPath) {
        Console.log("开始下载: {} -> {}", fileUrl, targetPath);
        final long totalFileSize;
        final boolean supportsRangeRequests;
        // --- 探测服务器支持情况 ---
        // 尝试发送 HEAD 请求
        final HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl))
                .timeout(TIMEOUT_DURATION)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        final HttpResponse<Void> headResponse = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());
        final int headStatusCode = headResponse.statusCode();
        if (headStatusCode == HttpURLConnection.HTTP_OK || headStatusCode == HttpURLConnection.HTTP_PARTIAL) {
            // 从 HEAD 响应中获取信息
            totalFileSize = headResponse.headers().firstValueAsLong("Content-Length").orElse(-1L);
            supportsRangeRequests = headResponse.headers()
                    .firstValue("Accept-Ranges")
                    .filter(rangeType -> rangeType.equalsIgnoreCase("bytes"))
                    .isPresent();
        } else {
            // 如果 HEAD 请求失败，尝试发送 GET 请求来探测
            final HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(fileUrl))
                    .timeout(TIMEOUT_DURATION)
                    .build();
            final HttpResponse<InputStream> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofInputStream());
            final int getStatusCode = getResponse.statusCode();
            if (getStatusCode == HttpURLConnection.HTTP_OK || getStatusCode == HttpURLConnection.HTTP_PARTIAL) {
                // 从 GET 响应中获取信息
                totalFileSize = getResponse.headers().firstValueAsLong("Content-Length").orElse(-1L);
                supportsRangeRequests = getResponse.headers()
                        .firstValue("Accept-Ranges")
                        .filter(rangeType -> rangeType.equalsIgnoreCase("bytes"))
                        .isPresent();
                IoUtil.close(getResponse.body());
            } else {
                Console.error("URL的初始探测请求失败: {}. HEAD: {}, GET: {}", fileUrl, headStatusCode, getStatusCode);
                throw new IOException("未能探测服务器功能。头: %d, GET: %d".formatted(headStatusCode, getStatusCode));
            }
        }

        // --- 初始化进度跟踪 (在此处传递探测到的 totalFileSize) ---
        initializeProgress(fileUrl, targetPath, totalFileSize);

        // --- 根据探测结果选择下载策略 ---
        if (totalFileSize <= 0) {
            // 情况1: 无法获取文件大小，使用流式下载
            streamDownload(fileUrl, targetPath);
        } else if (supportsRangeRequests) {
            // 情况2: 支持断点续传和多线程，使用多线程下载
            multiThreadDownload(fileUrl, targetPath, totalFileSize);
        } else {
            // 情况3: 知道文件大小但不支持Range，使用单线程下载
            singleThreadDownload(fileUrl, targetPath);
        }
        updateProgressOnCompletion(fileUrl);
        Console.log("下载完成：{} -> {}", fileUrl, targetPath);
        return targetPath;
    }


    /**
     * 使用多线程并发下载单个文件
     * @param fileUrl       文件URL
     * @param targetPath    目标文件路径
     * @param totalFileSize 文件总大小
     */
    private void multiThreadDownload(final String fileUrl, final Path targetPath, final long totalFileSize) {
        final int numberOfThreads = DEFAULT_THREAD_COUNT;
        final long chunkSize = totalFileSize / numberOfThreads;
        // 创建目标文件
        FileUtil.touch(targetPath.toFile());
        // 使用虚拟线程池执行分块下载任务
        try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < numberOfThreads; i++) {
                final long startByte = i * chunkSize;
                // 确保最后一个块位于文件末尾
                final long endByte = (i == numberOfThreads - 1) ? totalFileSize - 1 : (startByte + chunkSize - 1);
                executor.submit(() -> {
                    try {
                        downloadChunk(fileUrl, targetPath, startByte, endByte);
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    /**
     * 下载文件的一个分块
     * @param fileUrl    文件URL
     * @param targetPath 目标文件路径
     * @param startByte  开始字节
     * @param endByte    结束字节
     */
    private void downloadChunk(final String fileUrl, final Path targetPath, final long startByte, final long endByte) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl)).timeout(TIMEOUT_DURATION)
                .header("Range", "bytes=%d-%d".formatted(startByte, endByte))
                .build();
        final HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != HttpURLConnection.HTTP_PARTIAL) {
            throw new IOException("服务器对范围请求的响应状态为%d。预期%d".formatted(response.statusCode(), HttpURLConnection.HTTP_PARTIAL));
        }
        // 使用RandomAccessFile直接写入文件的指定位置
        try (final RandomAccessFile randomAccessFile = new RandomAccessFile(targetPath.toFile(), "rw");
             final InputStream inputStream = response.body()) {
            randomAccessFile.seek(startByte);
            final byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                randomAccessFile.write(buffer, 0, bytesRead);
                updateProgress(fileUrl, bytesRead);
                // 打印进度条
                Opt.ofNullable(progressMap.get(fileUrl)).ifPresent(DownloadProgress::printProgressBar);
            }
        }
    }

    /**
     * 当服务器不支持Range请求或无法获取文件大小时，使用单线程流式下载
     * @param fileUrl    文件URL
     * @param targetPath 目标文件路径
     */
    private void singleThreadDownload(final String fileUrl, final Path targetPath) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl)).timeout(TIMEOUT_DURATION).build();
        final HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("服务器对单线程下载的响应状态为%d。".formatted(response.statusCode()));
        }
        try (final InputStream inputStream = response.body();
             final ReadableByteChannel readableByteChannel = Channels.newChannel(inputStream);
             final RandomAccessFile randomAccessFile = new RandomAccessFile(targetPath.toFile(), "rw");
             final FileChannel fileChannel = randomAccessFile.getChannel()) {
            long count;
            long position = 0;
            while ((count = fileChannel.transferFrom(readableByteChannel, position, BUFFER_SIZE)) > 0) {
                position += count;
                updateProgress(fileUrl, count); // Fix applied here too
                // 打印进度条
                Opt.ofNullable(progressMap.get(fileUrl)).ifPresent(DownloadProgress::printProgressBar);
            }
        }
    }

    /**
     * 处理服务器不返回Content-Length的情况，直接流式写入
     * @param fileUrl    文件URL
     * @param targetPath 目标文件路径
     */
    private void streamDownload(final String fileUrl, final Path targetPath) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl)).timeout(TIMEOUT_DURATION).build();
        final HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("服务器响应流下载状态为%d。".formatted(response.statusCode()));
        }
        try (final InputStream inputStream = response.body(); final OutputStream outputStream = Files.newOutputStream(targetPath)) {
            int bytesRead;
            final byte[] buffer = new byte[BUFFER_SIZE];
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                updateProgress(fileUrl, bytesRead); // Fix applied here too
                // 打印进度条
                Opt.ofNullable(progressMap.get(fileUrl)).ifPresent(DownloadProgress::printProgressBar);
            }
        }
    }

    /**
     * 提取URL中的文件名
     * @param fileUrl 文件URL
     * @return 文件名的Optional
     */
    private Optional<String> extractFileName(final String fileUrl) {
        try {
            final String fileName = Paths.get(URI.create(fileUrl).getPath()).getFileName().toString();
            return fileName.isEmpty() ? Optional.empty() : Optional.of(fileName);
        } catch (final Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 初始化下载进度 (修改：接受 totalFileSize 参数)
     * @param fileUrl       文件URL
     * @param targetPath    目标路径
     * @param totalFileSize 探测到的文件总大小 (-1 表示未知)
     */
    private void initializeProgress(final String fileUrl, final Path targetPath, final long totalFileSize) {
        long existingFileSize = 0;
        if (Files.exists(targetPath)) {
            try {
                existingFileSize = Files.size(targetPath);
            } catch (final IOException _) {
                // 忽略读取错误
            }
        }
        // 将探测到的 totalFileSize 传递给 DownloadProgress 构造函数
        progressMap.put(fileUrl, new DownloadProgress(fileUrl, targetPath, existingFileSize, totalFileSize));
    }

    /**
     * 更新下载进度
     * @param fileUrl         文件URL
     * @param bytesDownloaded 已下载的字节数
     */
    private void updateProgress(final String fileUrl, final long bytesDownloaded) {
        Opt.ofNullable(progressMap.get(fileUrl))
                .ifPresent(progress -> progress.addDownloadedBytes(bytesDownloaded));
    }

    /**
     * 下载完成后更新进度状态
     * @param fileUrl 文件URL
     */
    private void updateProgressOnCompletion(final String fileUrl) {
        Opt.ofNullable(progressMap.get(fileUrl)).ifPresent(DownloadProgress::markAsCompleted);
    }

    /**
     * 获取当前所有下载的进度映射
     * @return 进度映射
     */
    @SuppressWarnings("unused")
    public Map<String, DownloadProgress> getProgressMap() {
        return new HashMap<>(progressMap);
    }

    /**
     * 内部类，用于封装单个文件的下载进度信息
     * @author xuhaifeng
     * @date 2025-09-25
     */
    @Getter
    public static class DownloadProgress {
        /**
         * 网址
         */
        private final String url;
        /**
         * 目标路径
         */
        private final Path targetPath;
        /**
         * 初始大小 (已存在的文件大小)
         */
        private final long initialSize;
        /**
         * 文件总大小 (从服务器获取)，-1 表示未知
         */
        private final long totalFileSize;
        /**
         * 已下载的字节数 (本次下载)
         */
        private final AtomicLong downloadedBytes = new AtomicLong(0);
        /**
         * 是否已完成
         */
        private volatile boolean completed;
        /**
         * 上次打印进度条时的已下载总字节数缓存，用于避免重复打印
         */
        private volatile long lastPrintedTotalDownloadedBytes = -1;
        /**
         * 上次打印进度条时的完成状态缓存，用于避免重复打印
         */
        private volatile boolean lastPrintedCompletedStatus = Boolean.FALSE;

        /**
         * 构造一个新地下载进度对象
         * @param url           文件URL
         * @param targetPath    目标文件路径
         * @param initialSize   初始已存在文件大小
         * @param totalFileSize 探测到的文件总大小 (-1 表示未知)
         */
        public DownloadProgress(final String url, final Path targetPath, final long initialSize, final long totalFileSize) {
            this.url = url;
            this.targetPath = targetPath;
            this.initialSize = initialSize;
            this.completed = Boolean.FALSE;
            this.totalFileSize = totalFileSize;
        }

        /**
         * 将字节数格式化为人类可读的字符串 (例如 KB, MB, GB)
         * @param bytes 字节数
         * @return 格式化后的字符串
         */
        private static String formatBytes(final long bytes) {
            if (bytes < 0) {
                return "未知";
            }
            int unitIndex = 0;
            double size = bytes;
            final String[] units = {"B", "KB", "MB", "GB", "TB"};
            while (size >= 1024.0 && unitIndex < units.length - 1) {
                size /= 1024.0;
                unitIndex++;
            }
            return String.format("%.2f %s", size, units[unitIndex]);
        }

        /**
         * 添加本次下载的字节数
         * @param bytes 本次下载的字节数
         */
        public void addDownloadedBytes(final long bytes) {
            this.downloadedBytes.addAndGet(bytes);
        }

        /**
         * 标记下载已完成
         */
        public void markAsCompleted() {
            this.completed = Boolean.TRUE;
        }

        /**
         * 获取当前已下载的总字节数 (初始大小 + 本次下载)
         * @return 总已下载字节数
         */
        public long getTotalDownloadedBytesSoFar() {
            return this.initialSize + this.downloadedBytes.get();
        }

        /**
         * 在控制台打印详细的进度条信息
         * 此方法会检查进度或状态是否发生变化，仅在变化时才打印，避免重复输出。
         */
        public void printProgressBar() {
            final boolean currentCompletedStatus = this.completed;
            final long currentTotalDownloaded = this.getTotalDownloadedBytesSoFar();
            // 检查进度或完成状态是否与上次打印时不同
            if (currentTotalDownloaded != this.lastPrintedTotalDownloadedBytes || currentCompletedStatus != this.lastPrintedCompletedStatus) {
                if (currentCompletedStatus) {
                    // 下载完成时打印最终状态
                    Console.log("[{}] 下载完成。总大小: {}, 已下载: {}",
                            this.url,
                            formatBytes(this.totalFileSize),
                            formatBytes(currentTotalDownloaded)
                    );
                } else {
                    // 下载进行中时打印进度
                    final String progressInfo = getProgressInfo(currentTotalDownloaded);
                    Console.log("[{}] {}", this.url, progressInfo);
                }
                // 更新缓存的打印状态，防止重复打印
                this.lastPrintedTotalDownloadedBytes = currentTotalDownloaded;
                this.lastPrintedCompletedStatus = currentCompletedStatus;
            }
        }

        /**
         * 获取进度信息
         * @param currentTotalDownloaded 当前总下载量
         * @return {@link String }
         */
        private String getProgressInfo(final long currentTotalDownloaded) {
            final String progressInfo;
            if (this.totalFileSize > 0) {
                // 如果已知总大小，计算并显示百分比和进度条
                final double percentage = (double) currentTotalDownloaded / this.totalFileSize * 100.0;
                progressInfo = String.format("%.2f%% (%s / %s)",
                        percentage,
                        formatBytes(currentTotalDownloaded),
                        formatBytes(this.totalFileSize)
                );
            } else {
                // 如果未知总大小，只显示已下载字节数
                progressInfo = String.format("已下载: %s", formatBytes(currentTotalDownloaded));
            }
            return progressInfo;
        }

        @Override
        public String toString() {
            return "DownloadProgress{url='%s', targetPath=%s, initialSize=%d, downloadedBytes=%d, totalFileSize=%d, completed=%s, totalDownloadedSoFar=%d}"
                    .formatted(url, targetPath, initialSize, downloadedBytes.get(), totalFileSize, completed, getTotalDownloadedBytesSoFar());
        }
    }
}