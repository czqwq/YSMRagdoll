package com.Lilith.ysmragdoll.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.config.YsmRagdollConfig;

/** 诊断模式下以 100 Hz 记录布娃娃姿态；独立文件避免污染普通运行日志。 */
final class ClientIntensiveLogger {

    private static final long SAMPLE_INTERVAL_NANOS = 10_000_000L;
    private static final int MAX_CATCH_UP_SAMPLES = 20;

    private static long nextSampleNanos;
    private static long lastFrameNanos;
    private static float frameMillis;
    private static Path logFile;

    private ClientIntensiveLogger() {}

    /** 在渲染线程调用；用单调时钟补齐低帧率期间错过的采样时刻。 */
    static void recordFrame() {
        if (!YsmRagdollConfig.intensiveTest()) {
            reset();
            return;
        }
        long now = System.nanoTime();
        if (nextSampleNanos == 0L) {
            nextSampleNanos = now;
        }
        // 1.7.10 的 Minecraft.debugFPS 是 private，这里用渲染帧间隔自行计算帧时间。
        frameMillis = lastFrameNanos == 0L ? 0.0F : (now - lastFrameNanos) / 1.0E6F;
        lastFrameNanos = now;
        int samples = 0;
        while (now >= nextSampleNanos && samples++ < MAX_CATCH_UP_SAMPLES) {
            writeSample(nextSampleNanos);
            nextSampleNanos += SAMPLE_INTERVAL_NANOS;
        }
        if (now - nextSampleNanos > SAMPLE_INTERVAL_NANOS * MAX_CATCH_UP_SAMPLES) {
            nextSampleNanos = now + SAMPLE_INTERVAL_NANOS;
        }
    }

    static void reset() {
        nextSampleNanos = 0L;
        lastFrameNanos = 0L;
        frameMillis = 0.0F;
        logFile = null;
    }

    private static void writeSample(long sampleNanos) {
        try {
            if (logFile == null) {
                logFile = YsmRagdollLog.resolveRoot()
                    .resolve("ysm-ragdoll-intensive.log");
            }
            float frameMillis = ClientIntensiveLogger.frameMillis;
            String line = OffsetDateTime.now() + " [INTENSIVE] sampleNanos="
                + sampleNanos
                + " frameTime="
                + Float.toString(frameMillis)
                + " fps="
                + Float.toString(frameMillis <= 0.0F ? 0.0F : 1000.0F / frameMillis)
                + ' '
                + ClientRagdollManager.intensiveState()
                + System.lineSeparator();
            Files.write(
                logFile,
                line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            YsmRagdollLog.warn("无法写入密集测试日志", exception);
            reset();
        }
    }
}
