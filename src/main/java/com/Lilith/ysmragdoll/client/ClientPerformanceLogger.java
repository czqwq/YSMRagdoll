package com.Lilith.ysmragdoll.client;

import java.util.Locale;

import com.Lilith.ysmragdoll.YsmRagdollLog;

/** 低频汇总客户端帧率和本模组渲染耗时，供后续性能优化对比。 */
final class ClientPerformanceLogger {

    private static final long WINDOW_NANOS = 10_000_000_000L;

    private static long windowStartedAt;
    private static long renderedFrames;
    private static long ragdollRenderNanos;
    private static long maximumRagdollRenderNanos;
    private static int minimumFps = Integer.MAX_VALUE;
    private static int maximumFps;
    private static long framesInSecond;
    private static long secondStartedAt;

    private ClientPerformanceLogger() {}

    static void recordFrame(long renderNanos, int ragdollCount, int physicsRagdollCount) {
        long now = System.nanoTime();
        if (windowStartedAt == 0L) {
            windowStartedAt = now;
        }
        // 1.7.10 的 Minecraft.debugFPS 是 private，这里按整秒窗口自行统计帧率。
        if (secondStartedAt == 0L) {
            secondStartedAt = now;
        }
        renderedFrames++;
        framesInSecond++;
        ragdollRenderNanos += Math.max(0L, renderNanos);
        maximumRagdollRenderNanos = Math.max(maximumRagdollRenderNanos, renderNanos);
        if (now - secondStartedAt >= 1_000_000_000L) {
            int measuredFps = (int) Math
                .min(Integer.MAX_VALUE, framesInSecond * 1_000_000_000L / Math.max(1L, now - secondStartedAt));
            framesInSecond = 0L;
            secondStartedAt = now;
            minimumFps = Math.min(minimumFps, measuredFps);
            maximumFps = Math.max(maximumFps, measuredFps);
        }

        long elapsedNanos = now - windowStartedAt;
        if (elapsedNanos < WINDOW_NANOS || renderedFrames == 0L) {
            return;
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double observedFps = renderedFrames / elapsedSeconds;
        double averageRenderMillis = ragdollRenderNanos / 1_000_000.0 / renderedFrames;
        double maximumRenderMillis = maximumRagdollRenderNanos / 1_000_000.0;
        YsmRagdollLog.performance(
            String.format(
                Locale.ROOT,
                "FPS窗口 %.1fs: 实测平均=%.1f, 游戏最低=%d, 游戏最高=%d, " + "布娃娃=%d, 物理布娃娃=%d, 模组渲染平均=%.3fms, 模组渲染峰值=%.3fms",
                elapsedSeconds,
                observedFps,
                minimumFps == Integer.MAX_VALUE ? 0 : minimumFps,
                maximumFps,
                ragdollCount,
                physicsRagdollCount,
                averageRenderMillis,
                maximumRenderMillis));
        resetAt(now);
    }

    static void reset() {
        resetAt(0L);
    }

    private static void resetAt(long startedAt) {
        windowStartedAt = startedAt;
        renderedFrames = 0L;
        ragdollRenderNanos = 0L;
        maximumRagdollRenderNanos = 0L;
        minimumFps = Integer.MAX_VALUE;
        maximumFps = 0;
        framesInSecond = 0L;
        secondStartedAt = 0L;
    }
}
