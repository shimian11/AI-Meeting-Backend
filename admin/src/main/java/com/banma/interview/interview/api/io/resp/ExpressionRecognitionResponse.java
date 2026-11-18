package com.banma.interview.interview.api.io.resp;

import lombok.Data;
import java.util.List;

/**
 * 表情识别响应实体类
 */
@Data
public class ExpressionRecognitionResponse {

    /**
     * 返回码，标识本次请求的处理结果。
     */
    private Integer code;

    /**
     * 表情识别数据。
     */
    private ExpressionData data;

    /**
     * 描述信息。
     */
    private String desc;

    /**
     * 会话唯一标识 ID。
     */
    private String sid;

    @Data
    public static class ExpressionData {

        /**
         * 文件结果列表。
         */
        private List<FileResult> fileList;

        /**
         * 复核数量。
         */
        private Integer reviewCount;

        /**
         * 统计数据。
         */
        private List<Integer> statistic;
    }

    @Data
    public static class FileResult {

        /**
         * 标签。
         */
        private Integer label;

        /**
         * 文件名称。
         */
        private String name;

        /**
         * 识别置信度/比率。
         */
        private Double rate;

        /**
         * 是否复核。
         */
        private Boolean review;
    }
}