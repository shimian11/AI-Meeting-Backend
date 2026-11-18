package com.banma.interview.interview.api.io.req;

import lombok.Data;

@Data
public class InterviewConversationPageReqDTO {

    /**
     * 当前页码，默认从第 1 页开始。
     */
    private Integer current = 1;

    /**
     * 每页条数，默认每页 10 条。
     */
    private Integer size = 10;

    /**
     * 会话状态，用于按状态筛选会话。
     */
    private String status;

    /**
     * 搜索关键词，用于模糊匹配会话内容或标题。
     */
    private String keyword;
}