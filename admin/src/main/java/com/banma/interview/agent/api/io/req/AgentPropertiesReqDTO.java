package com.banma.interview.agent.api.io.req;

import lombok.Data;
import java.util.List;

@Data
public class AgentPropertiesReqDTO {

    /**
     * Agent 唯一标识 ID，为空表示新增，非空表示编辑。
     */
    private Long id;

    /**
     * Agent 名称。
     */
    private String agentName;

    /**
     * 标签代码列表，对应AgentTagType枚举的code值
     */
    private List<Integer> tagCodes;

    /**
     * 当前页码
     */
    private Integer pageNum ;

    /**
     * 每页数量
     */
    private Integer pageSize;
}