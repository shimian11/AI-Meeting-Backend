package com.banma.interview.agent.api.io.resp;

import lombok.Data;
import java.util.List;

@Data
public class AgentPropertiesRespDTO {

    private Long id;

    private String agentName;

    // ── 为什么这里没有 apiKey / apiSecret / apiFlowId（2026-11-13 移除）──
    // ① 三者此前**从未被任何调用逻辑读取**（只被 update 接口写入），却原样出现在本 DTO 里，
    //    导致任何登录用户 GET /agent/properties 即可拿到明文密钥。
    // ② 对应数据库列已随 ADR-017 一并 DROP，凭证统一存 .env（BANMA_DEEPSEEK_API_KEY 等），
    //    Java 侧不持有、也不返回任何模型密钥。
    // ③ 将来若要做"多租户自带 key"，正确做法是 DB 只存 KMS 引用 + 返回掩码。

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