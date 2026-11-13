package com.banma.interview.agent.api.io.resp;

import lombok.Data;
import java.util.List;

@Data
public class AgentPropertiesRespDTO {

    private Long id;

    private String agentName;

    /**
     * 调用链类型：PYTHON（自研出题官）| XINGCHEN（讯飞星辰）
     * <p>控制台需要展示"这个 agent 走哪条链路"，是非敏感开关，可以返回。</p>
     */
    private String agentType;

    /**
     * 讯飞工作流 id。
     *
     * <p>不是密钥（泄漏也调不动接口，还要 apiKey），7A 清列时一并移除。</p>
     */
    private String apiFlowId;

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