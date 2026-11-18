package com.dreamshield.app;

import static com.dreamshield.app.AppCopy.message;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Session-bound immutable JPEG previews: saving a preview never takes another photo. */
final class PreviewCache {
    private static final int MAX_IMAGES = 2;
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private long generation;
    private final LinkedHashMap<String, byte[]> images = new LinkedHashMap<>();

    synchronized long generation() { return generation; }
    synchronized boolean isCurrent(long expected) { return generation == expected; }
    synchronized void require(long expected) {
        if (!isCurrent(expected)) throw new IllegalStateException(message("配对已变更，旧请求已取消，请重新操作", "Pairing changed. Please retry."));
    }
    synchronized void invalidate() { generation++; images.clear(); }
    synchronized String put(long expected, byte[] bytes) {
        require(expected);
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException(message("预览图片超过10MB限制", "Preview exceeds 10 MB"));
        String id = UUID.randomUUID().toString();
        images.put(id, bytes.clone());
        while (images.size() > MAX_IMAGES) images.remove(images.keySet().iterator().next());
        return id;
    }
    synchronized byte[] get(long expected, String id) {
        require(expected);
        byte[] bytes = images.get(id);
        if (bytes == null) throw new IllegalStateException(message("此预览已过期，请刷新预览后再次保存；不会重新抓拍替代原图", "Preview expired. Refresh before saving."));
        return bytes.clone();
    }
}
