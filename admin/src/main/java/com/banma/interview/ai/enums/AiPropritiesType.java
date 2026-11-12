package com.banma.interview.ai.enums;

import lombok.Getter;

/**
 * AI模型类型枚举
 * 用于定义支持的AI模型及其默认配置
 */
@Getter
public enum AiPropritiesType {

    DOUBAO(2, "doubao", "豆包", "https://ark.cn-beijing.volces.com/api/v3"),
    DEEPSEEK(4, "deepseek", "DeepSeek", "https://api.deepseek.com"),
    OTHER(99, "other", "其他", "");

    private final Integer code;
    private final String type;
    private final String desc;
    private final String defaultBaseUrl;

    AiPropritiesType(Integer code, String type, String desc, String defaultBaseUrl) {
        this.code = code;
        this.type = type;
        this.desc = desc;
        this.defaultBaseUrl = defaultBaseUrl;
    }
    
    /**
     * 根据code获取类型
     */
    public static AiPropritiesType getByCode(Integer code) {
        for (AiPropritiesType type : AiPropritiesType.values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return OTHER;
    }
    
    /**
     * 根据type字符串获取类型
     */
    public static AiPropritiesType getByType(String type) {
        if (type == null) {
            return OTHER;
        }
        for (AiPropritiesType t : AiPropritiesType.values()) {
            if (t.getType().equalsIgnoreCase(type)) {
                return t;
            }
        }
        // 旧数据兼容：generalv3.5 是讯飞星火模型名，SPARK 类型已于 2026-11-12 随讯飞链路删除，
        // 此处统一归到 OTHER（后续 createChatClient 会明确拒绝并提示走 Python Agent）
        if ("generalv3.5".equalsIgnoreCase(type)) {
            return OTHER;
        }
        return OTHER;
    }

    /**
     * 检查是否支持该类型
     */
    public static boolean isSupported(String type) {
        return getByType(type) != OTHER;
    }
}
