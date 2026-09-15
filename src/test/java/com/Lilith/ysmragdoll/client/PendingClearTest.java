package com.Lilith.ysmragdoll.client;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 验证网络线程登记清理请求时不会丢失。
 *
 * <p>
 * 退出世界时 Forge 在 Netty 的 IO 线程派发
 * {@code ClientDisconnectionFromServerEvent}；若在那里直接释放 JBullet 刚体，就会与
 * 客户端线程正在进行的物理步进竞争并破坏宽相位缓存。因此清理被改成“网络线程登记、
 * 客户端 tick 处理”，这里只验证登记侧不会丢请求。
 * </p>
 */
public class PendingClearTest {

    @Test
    public void concurrentRequestsAreAllObserved() throws Exception {
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int call = 0; call < perThread; call++) {
                        ClientRagdollManager.requestClear("测试线程");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();
        assertTrue("登记线程未在预期时间内完成", done.await(30, TimeUnit.SECONDS));
        assertTrue("并发登记后必须仍有待处理请求", ClientRagdollManager.hasPendingClear());
    }

    @Test
    public void requestClearOnlyQueuesWithoutTouchingPhysics() {
        // clear() 需要真实的 Minecraft 实例，因此这里只确认登记本身是安全的：
        // 真正的释放由客户端 tick 调用 clear() 完成，不在网络线程发生。
        ClientRagdollManager.requestClear("单元测试");
        assertTrue(ClientRagdollManager.hasPendingClear());
    }
}
