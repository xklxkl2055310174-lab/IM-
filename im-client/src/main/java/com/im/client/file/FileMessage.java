package com.im.client.file;

import com.im.common.json.JsonUtil;

/**
 * 聊天消息中的文件引用（客户端约定，不改协议）：
 * 私聊内容形如 {@code [[FILE]]{"fileId":"...","fileName":"a.txt","size":12,"md5":"..."}}。
 */
public final class FileMessage {

    private static final String PREFIX = "[[FILE]]";

    public record Ref(String fileId, String fileName, long size, String md5) {
    }

    private FileMessage() {
    }

    /** 生成文件消息内容。 */
    public static String encode(String fileId, String fileName, long size, String md5) {
        return PREFIX + JsonUtil.toJson(new Ref(fileId, fileName, size, md5));
    }

    /** 解析文件消息；不是文件消息返回 null。 */
    public static Ref parse(String content) {
        if (content == null || !content.startsWith(PREFIX)) {
            return null;
        }
        try {
            return JsonUtil.fromJson(content.substring(PREFIX.length()), Ref.class);
        } catch (Exception e) {
            return null;
        }
    }
}
