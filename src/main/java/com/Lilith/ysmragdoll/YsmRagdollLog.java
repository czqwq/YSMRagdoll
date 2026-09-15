package com.Lilith.ysmragdoll;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/**
 * 将模型捕获、骨架解析和物理初始化等关键诊断信息写入独立日志。
 *
 * <p>
 * 开发运行时可以通过 {@code -Dysmragdoll.projectDir=<工程目录>} 指定输出目录；
 * 普通游戏实例没有该属性时使用游戏进程的工作目录，因此工程移动或解压到另一台机器后
 * 不会继续写入某个开发者的绝对路径。
 * </p>
 *
 * <p>
 * 只使用 Java 8 的 {@code java.nio.file} API：本工程由 Jabel 提供现代语法，
 * 但产物仍然运行在 JVM 8 上，{@code Files.writeString} / {@code Path.of} 等
 * Java 11+ 方法不可用。
 * </p>
 */
public final class YsmRagdollLog {

    private static final Object LOCK = new Object();
    private static final long MAX_LOG_BYTES = 8L * 1024L * 1024L;
    private static Path logFile;

    private YsmRagdollLog() {}

    public static void info(String message) {
        write("INFO", message, true);
    }

    public static void warn(String message) {
        write("WARN", message, true);
    }

    public static void warn(String message, Throwable exception) {
        StringWriter trace = new StringWriter();
        exception.printStackTrace(new PrintWriter(trace));
        warn(message + System.lineSeparator() + trace);
    }

    /** 性能汇总只写入专属日志，避免污染 Forge 主日志。 */
    public static void performance(String message) {
        write("PERFORMANCE", message, false);
    }

    private static void write(String level, String message, boolean mirrorToForge) {
        synchronized (LOCK) {
            try {
                if (logFile == null) {
                    logFile = resolveRoot().resolve("ysm-ragdoll.log");
                }
                rotateIfNeeded();
                String line = OffsetDateTime.now() + " [" + level + "] " + message + System.lineSeparator();
                Files.write(
                    logFile,
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
                if (!mirrorToForge) {
                    return;
                }
                if ("WARN".equals(level)) {
                    ysmragdoll.LOG.warn("[ysmragdoll] {}", message);
                } else {
                    ysmragdoll.LOG.info("[ysmragdoll] {}", message);
                }
            } catch (IOException exception) {
                ysmragdoll.LOG.warn("无法写入 YSM Ragdoll 专属日志", exception);
            }
        }
    }

    /** 返回 {@code <工程目录>/ysm-ragdoll-logs}，必要时创建目录。 */
    public static Path resolveRoot() throws IOException {
        String projectDir = System.getProperty("ysmragdoll.projectDir");
        Path project = projectDir == null || projectDir.trim()
            .isEmpty() ? Paths.get("")
                .toAbsolutePath()
                .normalize() : Paths.get(projectDir);
        Path root = project.resolve("ysm-ragdoll-logs");
        Files.createDirectories(root);
        return root;
    }

    private static void rotateIfNeeded() throws IOException {
        if (!Files.isRegularFile(logFile) || Files.size(logFile) < MAX_LOG_BYTES) {
            return;
        }
        Path previous = logFile.resolveSibling("ysm-ragdoll.previous.log");
        Files.move(logFile, previous, StandardCopyOption.REPLACE_EXISTING);
    }
}
