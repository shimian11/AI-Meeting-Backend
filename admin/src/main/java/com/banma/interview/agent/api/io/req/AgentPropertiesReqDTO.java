package com.banma.interview.agent.api.io.req;

import lombok.Data;
import java.util.List;

@Data
public class AgentPropertiesReqDTO {

    private Long id;

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