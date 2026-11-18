package com.banma.interview.media.api.io.resp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 长文本语音合成（TTS）任务响应参数。
 */
@Data
public class LongTextTtsTaskRespDTO {

    /**
     * 讯飞会话 ID。
     */
    private String sid;

    /**
     * 任务 ID。
     */
    private String taskId;

    /**
     * 任务状态。
     * 1: 任务已创建
     * 2: 任务创建失败
     * 3: 任务执行中
     * 4: 任务执行失败
     * 5: 任务已完成
     */
    private String taskStatus;

    /**
     * 平台状态码，`0` 表示成功。
     */
    private Integer code;

    /**
     * 平台返回的消息。
     */
    private String message;

    /**
     * 解码后合成音频的下载地址。
     */
    @JsonIgnore
    private String audioUrl;

    /**
     * Base64 编码的合成音频内容。
     */
    private String audioBase64;

    /**
     * 解码后拼音内容的下载地址。
     */
    @JsonIgnore
    private String pybufUrl;

    /**
     * 拼音内容文本。
     */
    private String pybufContent;

    /**
     * 任务是否已完成。
     */
    private Boolean completed;

    /**
     * 任务是否成功。
     */
    private Boolean success;
}