package com.Lilith.ysmragdoll.client;

/** 共用的移除/显示规则；返回 -1 表示没有限时到期。 */
final class RagdollLifetime {

    private RagdollLifetime() {}

    static long remainingMillis(long createdAtMillis, long now, int lifetimeSeconds, boolean manual) {
        if (manual || lifetimeSeconds > 10000) {
            return -1;
        }
        return Math.max(0L, lifetimeSeconds * 1000L - Math.max(0L, now - createdAtMillis));
    }

    static boolean belowVoid(double worldY) {
        return worldY < -64.0;
    }
}
