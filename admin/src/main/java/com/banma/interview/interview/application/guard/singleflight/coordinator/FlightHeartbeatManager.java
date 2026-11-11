package com.banma.interview.interview.application.guard.singleflight.coordinator;

import com.banma.interview.interview.application.guard.singleflight.model.FlightOwnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * owner 节点的 heartbeat 调度器，负责定时执行续租动作，
 * 保证长耗时 AI 请求在运行期间不会因为 TTL 到期而被误接管。
 */
@Service
@RequiredArgsConstructor
public class FlightHeartbeatManager {

    @Qualifier("scheduledExecutorService")
    /** 定时调度线程池 */
    private final ScheduledExecutorService scheduledExecutorService;

    // 以「requestKey|ownerToken」为键保存续租任务句柄，终态时用于停止
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    /**
     * 启动周期性心跳任务，按策略间隔执行续租动作并登记任务句柄。
     *
     * @param ownerContext owner 上下文，提供 requestKey、令牌与策略
     * @param heartbeatAction 实际续租动作
     * @return 任务标识，可用于后续停止
     */
    public String start(FlightOwnerContext ownerContext, BooleanSupplier heartbeatAction) {
        // （1）解析心跳间隔，最小 500 毫秒
        long intervalMillis = ownerContext.getPolicy() == null || ownerContext.getPolicy().getHeartbeatIntervalMillis() == null
                ? 3000L
                : Math.max(500L, ownerContext.getPolicy().getHeartbeatIntervalMillis());
        // （2）按固定频率调度续租任务并记录句柄
        String taskKey = ownerContext.getRequestKey() + "|" + ownerContext.getOwnerToken();
        ScheduledFuture<?> future = scheduledExecutorService.scheduleAtFixedRate(
                () -> heartbeatAction.getAsBoolean(),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        futures.put(taskKey, future);
        return taskKey;
    }

    /**
     * 停止并移除指定心跳任务，任务不存在时忽略。
     */
    public void stop(String taskKey) {
        // （1）任务标识为空时直接返回
        if (taskKey == null) {
            return;
        }
        // （2）取消仍在运行的任务并从登记表移除
        ScheduledFuture<?> future = futures.remove(taskKey);
        if (future != null) {
            future.cancel(true);
        }
    }
}
