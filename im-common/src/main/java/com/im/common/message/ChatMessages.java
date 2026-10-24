package com.im.common.message;

import java.util.List;

/**
 * 消息域 DTO：私聊、群聊、确认、离线推送、历史消息。
 *
 * <p>可靠投递约定：每条消息由客户端生成唯一 {@code msgId}（UUID）；服务端落库后回
 * {@code MSG_ACK}，再为每个接收方写收件箱记录并推送；接收方回 {@code DELIVER_ACK}
 * 后服务端删除对应收件箱记录。</p>
 */
public final class ChatMessages {

    private ChatMessages() {
    }

    /**
     * 私聊消息（{@code PRIVATE_MSG}，发送与推送共用）。
     *
     * @param requestId  请求标识
     * @param msgId      客户端生成的 UUID，全局唯一，用于去重与 ACK 关联
     * @param fromUserId 发送者 id（服务端以会话身份覆盖，不信任客户端传值）
     * @param toUserId   接收者 id
     * @param content    文本内容
     * @param timestamp  客户端时间戳（毫秒）
     */
    public record PrivateMsg(String requestId, String msgId, long fromUserId, long toUserId,
                             String content, long timestamp) {
    }

    /**
     * 群聊消息（{@code GROUP_MSG}，发送与推送共用）。
     *
     * @param requestId  请求标识
     * @param msgId      客户端生成的 UUID
     * @param fromUserId 发送者 id（服务端覆盖）
     * @param groupId    群 id
     * @param content    文本内容
     * @param timestamp  客户端时间戳（毫秒）
     */
    public record GroupMsg(String requestId, String msgId, long fromUserId, long groupId,
                           String content, long timestamp) {
    }

    /**
     * 消息落库确认（{@code MSG_ACK}）。
     *
     * @param requestId 对应请求 id
     * @param msgId     被确认的消息 id
     * @param code      错误码，0 表示已落库并完成投递/入离线队列
     * @param message   描述
     */
    public record MsgAck(String requestId, String msgId, int code, String message) {
    }

    /**
     * 接收方投递确认（{@code DELIVER_ACK}），服务端收到后删除收件箱记录。
     *
     * @param requestId 请求标识
     * @param msgId     已收取的消息 id
     */
    public record DeliverAck(String requestId, String msgId) {
    }

    /**
     * 离线消息推送（{@code OFFLINE_MSG_PUSH}）。
     *
     * <p>登录/重登成功后按 id 升序分批推送（每批 100 条），客户端确认一批后服务端再推下一批。</p>
     *
     * @param messages 本批消息
     * @param total    本批条数
     * @param lastId   本批最后一条记录 id；无更多数据时为 0
     */
    public record OfflineMsgPush(List<OfflineMsgItem> messages, int total, long lastId) {
    }

    /**
     * 收件箱中的一条消息。
     *
     * @param id         收件箱记录 id（用户维度自增，历史翻页游标）
     * @param msgId      原消息 UUID
     * @param fromUserId 发送者 id
     * @param content    内容
     * @param timestamp  服务端接收时间（毫秒）
     */
    public record OfflineMsgItem(long id, String msgId, long fromUserId, String content, long timestamp) {
    }

    /**
     * 历史消息拉取（{@code HISTORY_REQ}）：打开会话时拉最近 N 条，向上翻页用 {@code beforeId} 游标。
     *
     * @param requestId 请求标识
     * @param peerId    对方用户 id（私聊）或群 id（群聊）
     * @param peerType  1 = 私聊，2 = 群聊
     * @param beforeId  游标：只取 id 小于该值的记录；首页传 0
     * @param limit     条数，服务端上限 100
     */
    public record HistoryRequest(String requestId, long peerId, int peerType, long beforeId, int limit) {
    }

    /**
     * 历史消息响应（{@code HISTORY_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码
     * @param message   描述
     * @param messages  按 id 倒序（最新在前）的消息列表
     */
    public record HistoryResponse(String requestId, int code, String message, List<OfflineMsgItem> messages) {
    }
}
