package com.im.client.net;

import com.im.common.MessageType;
import com.im.common.Packet;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 推送消息分发器：按 {@link MessageType} 注册监听器。
 *
 * <p>运行在读线程中；监听器实现必须快速返回，耗时操作请自行切到其它线程。</p>
 */
public final class EventDispatcher {

    private final Map<MessageType, CopyOnWriteArrayList<Consumer<Packet>>> listeners = new ConcurrentHashMap<>();

    /** 注册监听器，返回注销句柄。 */
    public Runnable on(MessageType type, Consumer<Packet> listener) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            List<Consumer<Packet>> list = listeners.get(type);
            if (list != null) {
                list.remove(listener);
            }
        };
    }

    /** 分发一条推送；监听器异常不会中断其它监听器。 */
    public void dispatch(Packet packet) {
        List<Consumer<Packet>> list = listeners.get(packet.type());
        if (list == null) {
            return;
        }
        for (Consumer<Packet> listener : list) {
            try {
                listener.accept(packet);
            } catch (RuntimeException ignored) {
                // 单个监听器异常不影响分发
            }
        }
    }
}
