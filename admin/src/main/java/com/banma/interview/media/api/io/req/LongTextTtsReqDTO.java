package com.banma.interview.media.api.io.req;

import lombok.Data;

/**
 * 长文本语音合成（TTS）请求参数。
 */
@Data
public class LongTextTtsReqDTO {

    /**
     * 需要合成的文本。
     */
    private String text;

    /**
     * 音色名称，默认 `x4_mingge`。
     */
    private String vcn;

    /**
     * 语言代码，默认 `zh`。
     */
    private String language;

    /**
     * 语速，取值范围 `[0, 100]`。
     */
    private Integer speed;

    /**
     * 音量，取值范围 `[0, 100]`。
     */
    private Integer volume;

    /**
     * 音调，取值范围 `[0, 100]`。
     */
    private Integer pitch;

    /**
     * 是否返回拼音内容，`0` 或 `1`。
     */
    private Integer rhy;

    /**
     * 音频编码，默认 `lame`（mp3）。
     */
    private String audioEncoding;

    /**
     * 采样率，默认 `16000`。
     */
    private Integer sampleRate;

    /**
     * 同步等待时的超时时间（秒）。
     */
    private Integer timeoutSeconds;

    /**
     * 轮询间隔（毫秒）。
     */
    private Integer pollIntervalMs;
}