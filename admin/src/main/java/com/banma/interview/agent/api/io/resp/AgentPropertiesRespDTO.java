package com.banma.interview.agent.api.io.resp;

import lombok.Data;
import java.util.List;

@Data
public class AgentPropertiesRespDTO {

    /**
     * Agent 唯一标识 ID。
     */
    private Long id;

    /**
     * Agent 名称。
     */
    private String agentName;

    /**
     * 标签信息列表
     */
    private List<TagInfo> tags;

    @Data
    public static class TagInfo {
        /**
         * 标签代码
         */
        private Integer code;
        
        /**
         * 标签名称
         */
        private String name;
        
        /**
         * 标签颜色
         */
        private String color;
    }
}