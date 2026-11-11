package com.banma.interview.interview.application.guard.singleflight.coordinator;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.application.guard.singleflight.model.FlightErrorType;
import com.banma.interview.interview.application.guard.singleflight.model.FlightStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分布式 flight 的通知服务，负责把 owner 的终态结果写入 Redis Stream，
 * 并为 follower 提供阻塞等待通知的能力。
 */
@Service
@RequiredArgsConstructor
public class FlightNotificationService {

    private static final String STREAM_KEY_PREFIX = "ai:flight:stream:";

    /** Redis 模板，用于缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 把 owner 的终态事件写入 Redis Stream，通知正在等待的 follower。
     *
     * @param eventType 事件类型（如 owner_succeeded、owner_failed）
     * @param status 终态状态
     * @param errorType 失败时的错误类型，可为空
     * @param retryable 失败时是否允许接管重试
     */
    public void publish(String requestKey, String eventType, FlightStatus status, Long ownerToken,
                        FlightErrorType errorType, boolean retryable) {
        // （1）组装事件消息体，空值以空串占位
        Map<String, String> body = new HashMap<>();
        body.put("eventType", StrUtil.blankToDefault(eventType, "updated"));
        body.put("status", status == null ? "" : status.name());
        body.put("ownerToken", ownerToken == null ? "" : String.valueOf(ownerToken));
        body.put("errorType", errorType == null ? "" : errorType.name());
        body.put("retryable", retryable ? "1" : "0");
        body.put("finishedAt", String.valueOf(System.currentTimeMillis()));
        // （2）写入该请求对应的 Stream
        stringRedisTemplate.opsForStream().add(StreamRecords.mapBacked(body).withStreamKey(streamKey(requestKey)));
    }

    /**
     * 阻塞等待最新一条终态事件，超时或无可读记录时返回 null。
     */
    public FlightStatus waitForTerminalEvent(String requestKey, long blockTimeoutMillis) {
        // （1）以 BLOCK 方式读取一条最新记录
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(1).block(Duration.ofMillis(Math.max(1L, blockTimeoutMillis))),
                StreamOffset.create(streamKey(requestKey), ReadOffset.latest())
        );
        if (records == null || records.isEmpty()) {
            return null;
        }
        // （2）从消息体解析终态状态
        Object status = records.get(0).getValue().get("status");
        return FlightStatus.from(status == null ? null : status.toString());
    }

    private String streamKey(String requestKey) {
        return STREAM_KEY_PREFIX + requestKey;
    }
}
