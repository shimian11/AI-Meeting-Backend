# 运行时配置索引（自动生成）

该文档从 `application.yaml` 和 `interview-followup-rule.yaml` 自动提取。改配置后请重新运行 `scripts/extract_config_index.py`。

## spring.ai.openai

| 键 | 值 |
| --- | --- |
| `spring.ai.openai.api-key` | `${SPRING_AI_OPENAI_API_KEY:sk-xxxxxxxxxxxxx}` |
| `spring.ai.openai.base-url` | `${SPRING_AI_OPENAI_BASE_URL:https://api.openai.com}` |
| `spring.ai.openai.chat.options.model` | `${SPRING_AI_OPENAI_MODEL:gpt-4o-mini}` |
| `spring.ai.openai.chat.options.temperature` | `${SPRING_AI_OPENAI_TEMPERATURE:0.7}` |
| `spring.ai.openai.embedding.options.model` | `${SPRING_AI_OPENAI_EMBEDDING_MODEL:text-embedding-3-small}` |

## xunfei.lat-key

| 键 | 值 |
| --- | --- |
| `xunfei.lat-key.api-key` | `${XUNFEI_API_KEY:e8565c438f59b301616e0498a86ad95d}` |
| `xunfei.lat-key.api-secret` | `${XUNFEI_API_SECRET:OGZkZGQ5ZDY0Yzc4MTllZWI3ZmU2MDU4}` |
| `xunfei.lat-key.app-id` | `${XUNFEI_APP_ID:96f3a359}` |
| `xunfei.lat-key.rta-api-key` | `${XUNFEI_RTA_API_KEY:}` |

## banma-interview.agent-binding

| 键 | 值 |
| --- | --- |
| `banma-interview.agent-binding.general-agent-chat` | `${BANMA_AGENT_GENERAL_CHAT:通用智能体}` |
| `banma-interview.agent-binding.interview-answer-evaluation` | `${BANMA_AGENT_INTERVIEW_ANSWER_EVALUATION:用户答案评分官}` |
| `banma-interview.agent-binding.interview-demeanor` | `${BANMA_AGENT_INTERVIEW_DEMEANOR:神态分析官}` |
| `banma-interview.agent-binding.interview-question-asking` | `${BANMA_AGENT_INTERVIEW_QUESTION_ASKING:面试提问官}` |
| `banma-interview.agent-binding.interview-question-extraction` | `${BANMA_AGENT_INTERVIEW_QUESTION_EXTRACTION:面试出题官}` |

## banma-interview.flow-limit

| 键 | 值 |
| --- | --- |
| `banma-interview.flow-limit.enable` | `True` |
| `banma-interview.flow-limit.interview-ai-call-max-access-count` | `6` |
| `banma-interview.flow-limit.interview-ai-call-time-window-seconds` | `1` |
| `banma-interview.flow-limit.interview-answer-max-access-count` | `8` |
| `banma-interview.flow-limit.interview-answer-time-window-seconds` | `1` |
| `banma-interview.flow-limit.interview-heavy-max-access-count` | `2` |
| `banma-interview.flow-limit.interview-heavy-time-window-seconds` | `1` |
| `banma-interview.flow-limit.interview-read-max-access-count` | `15` |
| `banma-interview.flow-limit.interview-read-time-window-seconds` | `1` |
| `banma-interview.flow-limit.max-access-count` | `20` |
| `banma-interview.flow-limit.requested-tokens` | `1` |
| `banma-interview.flow-limit.time-window-seconds` | `1` |

## banma-interview.ai-guard

| 键 | 值 |
| --- | --- |
| `banma-interview.ai-guard.circuit-failure-rate-threshold` | `50` |
| `banma-interview.ai-guard.circuit-open-state-wait-millis` | `30000` |
| `banma-interview.ai-guard.circuit-permitted-calls-in-half-open-state` | `10` |
| `banma-interview.ai-guard.circuit-sliding-window-size` | `50` |
| `banma-interview.ai-guard.enable` | `True` |
| `banma-interview.ai-guard.executor-threads` | `32` |
| `banma-interview.ai-guard.stages.interview-demeanor.max-concurrent-calls` | `6` |
| `banma-interview.ai-guard.stages.interview-demeanor.retry-count` | `0` |
| `banma-interview.ai-guard.stages.interview-demeanor.retry-wait-millis` | `0` |
| `banma-interview.ai-guard.stages.interview-demeanor.timeout-millis` | `20000` |
| `banma-interview.ai-guard.stages.interview-evaluation.max-concurrent-calls` | `30` |
| `banma-interview.ai-guard.stages.interview-evaluation.retry-count` | `1` |
| `banma-interview.ai-guard.stages.interview-evaluation.retry-wait-millis` | `100` |
| `banma-interview.ai-guard.stages.interview-evaluation.timeout-millis` | `20000` |
| `banma-interview.ai-guard.stages.interview-extraction.max-concurrent-calls` | `8` |
| `banma-interview.ai-guard.stages.interview-extraction.retry-count` | `0` |
| `banma-interview.ai-guard.stages.interview-extraction.retry-wait-millis` | `0` |
| `banma-interview.ai-guard.stages.interview-extraction.timeout-millis` | `60000` |
| `banma-interview.ai-guard.stages.interview-followup.max-concurrent-calls` | `20` |
| `banma-interview.ai-guard.stages.interview-followup.retry-count` | `1` |
| `banma-interview.ai-guard.stages.interview-followup.retry-wait-millis` | `100` |
| `banma-interview.ai-guard.stages.interview-followup.timeout-millis` | `20000` |

## banma-interview.ai-singleflight

| 键 | 值 |
| --- | --- |
| `banma-interview.ai-singleflight.cleanup-threshold` | `256` |
| `banma-interview.ai-singleflight.distributed-enabled` | `True` |
| `banma-interview.ai-singleflight.enable` | `True` |
| `banma-interview.ai-singleflight.follower-max-wait-millis` | `20000` |
| `banma-interview.ai-singleflight.heavy-lock-expire-seconds` | `90` |
| `banma-interview.ai-singleflight.heavy-lock-wait-millis` | `0` |
| `banma-interview.ai-singleflight.l1-cache-max-size` | `1000` |
| `banma-interview.ai-singleflight.mode` | `hybrid` |
| `banma-interview.ai-singleflight.poll-fallback-interval-millis` | `2000` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.compression-codec` | `gzip` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.compression-threshold-bytes` | `2048` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.failed-result-ttl-millis` | `60000` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.heartbeat-interval-millis` | `5000` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.l1-cache-enabled` | `False` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.result-ttl-millis` | `900000` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.running-ttl-millis` | `30000` |
| `banma-interview.ai-singleflight.stage-policies.interview-demeanor.takeover-detect-millis` | `15000` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.compression-codec` | `gzip` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.compression-threshold-bytes` | `4096` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.failed-result-ttl-millis` | `60000` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.heartbeat-interval-millis` | `3000` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.l1-cache-enabled` | `True` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.l1-cache-ttl-millis` | `30000` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.result-ttl-millis` | `600000` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.running-ttl-millis` | `15000` |
| `banma-interview.ai-singleflight.stage-policies.interview-evaluation.takeover-detect-millis` | `10000` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.compression-codec` | `gzip` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.compression-threshold-bytes` | `1024` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.failed-result-ttl-millis` | `60000` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.heartbeat-interval-millis` | `4000` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.l1-cache-enabled` | `True` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.l1-cache-ttl-millis` | `60000` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.result-ttl-millis` | `1800000` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.running-ttl-millis` | `20000` |
| `banma-interview.ai-singleflight.stage-policies.interview-extraction.takeover-detect-millis` | `12000` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.compression-codec` | `gzip` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.compression-threshold-bytes` | `2048` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.failed-result-ttl-millis` | `30000` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.heartbeat-interval-millis` | `2500` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.l1-cache-enabled` | `True` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.l1-cache-ttl-millis` | `15000` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.result-ttl-millis` | `180000` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.running-ttl-millis` | `12000` |
| `banma-interview.ai-singleflight.stage-policies.interview-followup.takeover-detect-millis` | `8000` |
| `banma-interview.ai-singleflight.stream-block-timeout-millis` | `3000` |
| `banma-interview.ai-singleflight.ttl-millis` | `65000` |
| `banma-interview.ai-singleflight.wait-timeout-millis` | `65000` |

## banma-interview.thread-pool

| 键 | 值 |
| --- | --- |
| `banma-interview.thread-pool.ai-io.core-pool-size` | `24` |
| `banma-interview.thread-pool.ai-io.keep-alive-seconds` | `120` |
| `banma-interview.thread-pool.ai-io.max-pool-size` | `64` |
| `banma-interview.thread-pool.ai-io.queue-capacity` | `400` |
| `banma-interview.thread-pool.ai-io.thread-name-prefix` | `banma-ai-io-` |
| `banma-interview.thread-pool.cpu.core-pool-size` | `8` |
| `banma-interview.thread-pool.cpu.keep-alive-seconds` | `60` |
| `banma-interview.thread-pool.cpu.max-pool-size` | `16` |
| `banma-interview.thread-pool.cpu.queue-capacity` | `256` |
| `banma-interview.thread-pool.cpu.thread-name-prefix` | `banma-cpu-` |
| `banma-interview.thread-pool.general.core-pool-size` | `50` |
| `banma-interview.thread-pool.general.keep-alive-seconds` | `300` |
| `banma-interview.thread-pool.general.max-pool-size` | `200` |
| `banma-interview.thread-pool.general.queue-capacity` | `1000` |
| `banma-interview.thread-pool.general.thread-name-prefix` | `banma-async-` |
| `banma-interview.thread-pool.query.core-pool-size` | `16` |
| `banma-interview.thread-pool.query.keep-alive-seconds` | `120` |
| `banma-interview.thread-pool.query.max-pool-size` | `48` |
| `banma-interview.thread-pool.query.queue-capacity` | `600` |
| `banma-interview.thread-pool.query.thread-name-prefix` | `banma-query-` |
| `banma-interview.thread-pool.scheduled-pool-size` | `8` |
| `banma-interview.thread-pool.scheduled-thread-name-prefix` | `banma-schedule-` |

## banma-interview.interview.answer-guard

| 键 | 值 |
| --- | --- |
| `banma-interview.interview.answer-guard.lock-expire-seconds` | `-1` |
| `banma-interview.interview.answer-guard.lock-wait-millis` | `0` |
| `banma-interview.interview.answer-guard.lock-watchdog-enabled` | `True` |
| `banma-interview.interview.answer-guard.processing-expire-seconds` | `120` |
| `banma-interview.interview.answer-guard.processing-long-tail-expire-seconds` | `300` |
| `banma-interview.interview.answer-guard.replay-expire-hours` | `24` |

## banma-interview.interview.turn-repair

| 键 | 值 |
| --- | --- |
| `banma-interview.interview.turn-repair.batch-size` | `50` |
| `banma-interview.interview.turn-repair.enable` | `True` |
| `banma-interview.interview.turn-repair.fixed-delay-millis` | `3000` |
| `banma-interview.interview.turn-repair.max-retries` | `6` |

## banma-interview.redis-session

| 键 | 值 |
| --- | --- |
| `banma-interview.redis-session.batch-sync-size` | `100` |
| `banma-interview.redis-session.clean-interval-seconds` | `300` |
| `banma-interview.redis-session.enable` | `True` |
| `banma-interview.redis-session.max-queue-size` | `10000` |
| `banma-interview.redis-session.message-expire-seconds` | `604800` |
| `banma-interview.redis-session.sync-delay-seconds` | `30` |

## banma-interview.interview.rule-engine

| 键 | 值 |
| --- | --- |
| `banma-interview.interview.rule-engine.default-chain-id` | `default_followup_chain` |
| `banma-interview.interview.rule-engine.default-low-score-threshold` | `60` |
| `banma-interview.interview.rule-engine.default-max-follow-up` | `2` |
| `banma-interview.interview.rule-engine.enable` | `True` |
| `banma-interview.interview.rule-engine.fail-open` | `True` |
| `banma-interview.interview.rule-engine.rule-version` | `v1.0.0` |

## collection.vector

| 键 | 值 |
| --- | --- |
| `collection.vector.similarity-threshold` | `0.9` |

## liteflow.rule-source

| 键 | 值 |
| --- | --- |
| `liteflow.rule-source` | `classpath:liteflow/interview-followup-chain.xml` |

