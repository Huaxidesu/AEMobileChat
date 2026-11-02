package com.huaxidesu.gtnchat.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.huaxidesu.gtnchat.GTNHChat;

/**
 * 把需要主线程执行的事情排队，由服务端 tick 统一取走。
 *
 * <p>玩家聊天事件在网络线程触发，而广播、发包、读写世界都必须在主线程。
 * 每条聊天单独投递一个任务会带来额外的调度开销，这里改成队列 + 每 tick 批量取，
 * 一次 tick 最多处理 {@link #BATCH} 条，避免大量消息涌入时占满整帧预算。
 */
public final class MainThread {

    private static final int BATCH = 256;
    private static final Queue<Runnable> QUEUE = new ConcurrentLinkedQueue<Runnable>();

    private MainThread() {}

    public static void submit(Runnable task) {
        if (task != null) QUEUE.add(task);
    }

    public static void drain() {
        int n = 0;
        Runnable task;
        while (n++ < BATCH && (task = QUEUE.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                GTNHChat.LOG.warn("主线程任务执行失败", t);
            }
        }
    }
}
