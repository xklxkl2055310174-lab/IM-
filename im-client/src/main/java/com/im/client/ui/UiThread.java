package com.im.client.ui;

import javax.swing.SwingUtilities;

/**
 * 统一 UI 线程调度：所有跨线程的界面更新都必须经过这里。
 *
 * <p>遵守总纲「客户端设计要点」：EDT 上不做网络/磁盘 IO；网络线程不直接操作 Swing 组件。</p>
 */
public final class UiThread {

    private UiThread() {
    }

    /** 在 EDT 上执行（已在 EDT 时同步执行）。 */
    public static void run(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /** 同步在 EDT 上执行并等待完成（仅用于需要返回值的场景，注意避免死锁）。 */
    public static void runAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (Exception e) {
            throw new IllegalStateException("EDT 执行失败", e);
        }
    }
}
