package com.banma.interview.interview.shared;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI 响应解析器：负责从模型返回内容中提取正文、结构化对象与评分等数据。
 */
@Component
@Slf4j
public class InterviewResponseParser {

    /**
     * 解析 AI 评估结果：优先解析 choices 中的正文，失败时回退解析原始响应。
     */
    public Map<String, Object> parseEvaluationResult(String aiResponseStr) {
        // （1）先提取正文并尝试解析为对象
        String contentStr = extractContentFromInterviewResponse(aiResponseStr);
        Map<String, Object> parsedFromContent = tryParseObject(contentStr);
        if (parsedFromContent != null) {
            return parsedFromContent;
        }
        // （2）正文解析失败时回退解析原始响应
        return tryParseObject(aiResponseStr);
    }

    /**
     * 提取首个包含指定键的结构化对象，未命中时返回根对象。
     *
     * @param aiResponseStr AI 原始响应
     * @param targetKeys 目标字段名
     * @return 结构化对象，无法解析时返回 null
     */
    public Map<String, Object> extractStructuredResult(String aiResponseStr, String... targetKeys) {
        // （1）先解析评估结果并查找包含目标键的对象
        Map<String, Object> parsed = parseEvaluationResult(aiResponseStr);
        Map<String, Object> matched = findFirstObjectContainingKeys(parsed, targetKeys);
        if (matched != null) {
            return matched;
        }

        // （2）未命中时解析原始响应，仍无命中则返回根对象
        Map<String, Object> root = tryParseObject(aiResponseStr);
        matched = findFirstObjectContainingKeys(root, targetKeys);
        return matched != null ? matched : root;
    }

    /**
     * 提取工作流错误信息：响应 code 非 0 时返回 code 与 message。
     *
     * @param aiResponseStr AI 原始响应
     * @return 错误描述，无错误时返回 null
     */
    public String extractWorkflowErrorMessage(String aiResponseStr) {
        // （1）解析根对象，空结果视为无错误
        Map<String, Object> root = tryParseObject(aiResponseStr);
        if (root == null || root.isEmpty()) {
            return null;
        }

        // （2）code 为空或 0 视为正常返回
        Integer responseCode = parseInteger(root.get("code"));
        if (responseCode == null || responseCode == 0) {
            return null;
        }

        // （3）拼接 code 与 message 作为错误描述
        String message = asString(root.get("message"));
        if (StrUtil.isBlank(message)) {
            return "workflow response code=" + responseCode;
        }
        return "workflow response code=" + responseCode + ", message=" + message;
    }

    /**
     * 从响应 Map 的 choices 中提取 delta 或 message 的 content。
     *
     * @param responseMap 响应 Map
     * @return 正文内容，无法提取时返回 null
     */
    public String extractContentFromResponse(Map<String, Object> responseMap) {
        try {
            // （1）校验 choices 是否存在且为列表
            if (responseMap == null || !responseMap.containsKey("choices")) {
                return null;
            }

            Object choicesObj = responseMap.get("choices");
            if (!(choicesObj instanceof List)) {
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) choicesObj;
            if (choices.isEmpty()) {
                return null;
            }

            Map<String, Object> firstChoice = choices.get(0);
            if (firstChoice == null) {
                return null;
            }

            // （2）优先取流式 delta.content，其次取 message.content
            if (firstChoice.containsKey("delta")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> delta = (Map<String, Object>) firstChoice.get("delta");
                if (delta != null && delta.containsKey("content")) {
                    return String.valueOf(delta.get("content"));
                }
            }
            if (firstChoice.containsKey("message")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
                if (message != null && message.containsKey("content")) {
                    return String.valueOf(message.get("content"));
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to extract content: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从原始响应 JSON 中提取正文，解析失败时返回原文。
     */
    public String extractContentFromInterviewResponse(String aiResponse) {
        try {
            // （1）解析响应 JSON，失败时视为纯文本正文
            JSONObject jsonObject = JSON.parseObject(aiResponse);
            if (jsonObject == null) {
                return aiResponse;
            }

            // （2）优先从 choices 的 delta 或 message 中取 content
            if (jsonObject.containsKey("choices")) {
                JSONArray choices = jsonObject.getJSONArray("choices");
                if (choices != null && !choices.isEmpty()) {
                    JSONObject firstChoice = choices.getJSONObject(0);
                    if (firstChoice != null) {
                        if (firstChoice.containsKey("delta")) {
                            JSONObject delta = firstChoice.getJSONObject("delta");
                            if (delta != null && delta.containsKey("content")) {
                                return delta.getString("content");
                            }
                        }
                        if (firstChoice.containsKey("message")) {
                            JSONObject message = firstChoice.getJSONObject("message");
                            if (message != null && message.containsKey("content")) {
                                return message.getString("content");
                            }
                        }
                    }
                }
            }

            // （3）兼容直接返回 content 字段的结构
            if (jsonObject.containsKey("content")) {
                return jsonObject.getString("content");
            }
            return aiResponse;
        } catch (Exception e) {
            return aiResponse;
        }
    }

    /**
     * 解析指定字段的分数并截断到 0-100，字段缺失或非法时返回 null。
     *
     * @param responseMap 响应 Map
     * @param scoreKey 分数字段名
     * @return 截断后的分数
     */
    public Integer parseScoreFromResponse(Map<String, Object> responseMap, String scoreKey) {
        // （1）字段不存在时返回 null
        if (responseMap == null || !responseMap.containsKey(scoreKey)) {
            return null;
        }

        // （2）兼容数字与字符串两种分数形式
        Object scoreObj = responseMap.get(scoreKey);
        Integer score;
        if (scoreObj instanceof Number) {
            score = (int) Math.round(((Number) scoreObj).doubleValue());
        } else if (scoreObj instanceof String) {
            try {
                score = (int) Math.round(Double.parseDouble(((String) scoreObj).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }

        // （3）负数归 0，超过 100 归 100
        if (score < 0) {
            return 0;
        }
        if (score > 100) {
            return 100;
        }
        return score;
    }

    /**
     * 将值转换为布尔值，兼容 true、1、yes 等文本写法。
     */
    public boolean asBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    /**
     * 将值转换为去空白的字符串，null 返回 null。
     */
    public String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }

    /**
     * 将值转换为字符串列表，兼容数组、JSON 数组字符串与分隔符文本。
     */
    public List<String> asStringList(Object value) {
        // （1）空值返回空列表
        if (value == null) {
            return Collections.emptyList();
        }

        // （2）List 类型逐项转换并过滤空白
        if (value instanceof List) {
            List<?> rawList = (List<?>) value;
            return rawList.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }

        // （3）空字符串返回空列表
        String item = value.toString().trim();
        if (StrUtil.isBlank(item)) {
            return Collections.emptyList();
        }

        // （4）形如 JSON 数组时优先按 JSON 解析
        if (item.startsWith("[") && item.endsWith("]")) {
            try {
                JSONArray jsonArray = JSON.parseArray(item);
                if (jsonArray != null) {
                    return jsonArray.stream()
                            .filter(Objects::nonNull)
                            .map(String::valueOf)
                            .map(String::trim)
                            .filter(StrUtil::isNotBlank)
                            .collect(Collectors.toList());
                }
            } catch (Exception ignored) {
                // Fall through.
            }
        }

        // （5）按中英文逗号、分号或换行切分
        if (item.contains(",") || item.contains(";") || item.contains("\uFF0C") || item.contains("\uFF1B") || item.contains("\n")) {
            String[] parts = item.split("[,;\\uFF0C\\uFF1B\\n]+");
            return java.util.Arrays.stream(parts)
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toList());
        }

        return Collections.singletonList(item);
    }

    /**
     * 将值解析为整数，兼容数字与数值字符串，失败返回 null。
     */
    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 尝试解析对象 JSON：先剥离代码块标记，再提取 JSON 片段，最后解包 json 字段。
     */
    private Map<String, Object> tryParseObject(String text) {
        // （1）文本为空时返回 null
        if (StrUtil.isBlank(text)) {
            return null;
        }

        // （2）去除 Markdown 代码块标记后直接解析
        String normalized = stripMarkdownCodeFence(text);
        JSONObject parsed = tryParseJsonObject(normalized);
        // （3）直接解析失败时截取首个 JSON 片段再解析
        if (parsed == null) {
            String jsonBody = extractFirstJsonObject(normalized);
            parsed = tryParseJsonObject(jsonBody);
        }
        if (parsed == null) {
            return null;
        }

        // （4）规范化为普通 Map，并在存在 json 字段时解包内层对象
        Map<String, Object> map = InterviewJsonValueNormalizer.asMap(parsed);
        if (map == null) {
            return null;
        }
        Map<String, Object> unwrapped = unwrapJsonFieldMap(map);
        return unwrapped == null ? map : unwrapped;
    }

    /**
     * 尝试解析 JSON 对象，解析失败返回 null。
     */
    private JSONObject tryParseJsonObject(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return JSON.parseObject(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 去除文本首尾的 Markdown 代码块标记。
     */
    private String stripMarkdownCodeFence(String text) {
        // （1）文本为空时直接返回
        if (StrUtil.isBlank(text)) {
            return text;
        }
        // （2）以 ``` 包裹时去掉首尾标记
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    /**
     * 截取文本中第一个 { 到最后一个 } 之间的 JSON 片段。
     */
    private String extractFirstJsonObject(String text) {
        // （1）文本为空时返回 null
        if (StrUtil.isBlank(text)) {
            return null;
        }
        // （2）定位首尾大括号，无法构成片段时返回 null
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /**
     * 解包外层 json 字段：支持对象、Map 与 JSON 字符串三种形式。
     */
    private Map<String, Object> unwrapJsonFieldMap(Map<String, Object> parsed) {
        // （1）解析结果为空时无需解包
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }

        // （2）json 字段为对象或 Map 时直接转为 Map 返回
        Object jsonField = parsed.get("json");
        if (jsonField instanceof JSONObject) {
            return InterviewJsonValueNormalizer.asMap(jsonField);
        }
        if (jsonField instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapField = (Map<String, Object>) jsonField;
            return new LinkedHashMap<>(mapField);
        }
        // （3）json 字段为字符串时再次解析为对象
        if (!(jsonField instanceof String) || StrUtil.isBlank((String) jsonField)) {
            return null;
        }

        try {
            JSONObject inner = JSON.parseObject((String) jsonField);
            return inner == null ? null : InterviewJsonValueNormalizer.asMap(inner);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 深度优先查找首个包含任一目标键的对象，兼容 Map、List、JSON 与字符串。
     */
    private Map<String, Object> findFirstObjectContainingKeys(Object candidate, String... targetKeys) {
        // （1）空值直接返回 null
        if (candidate == null) {
            return null;
        }

        // （2）Map 命中目标键即返回，否则递归查找各字段值
        if (candidate instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) rawMap);
            if (containsAnyKey(map, targetKeys)) {
                return map;
            }
            for (Object value : map.values()) {
                Map<String, Object> nested = findFirstObjectContainingKeys(value, targetKeys);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        // （3）List 逐项递归查找
        if (candidate instanceof List<?> rawList) {
            for (Object item : rawList) {
                Map<String, Object> nested = findFirstObjectContainingKeys(item, targetKeys);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        // （4）Fastjson 类型先规范化后再递归
        if (candidate instanceof JSONObject jsonObject) {
            return findFirstObjectContainingKeys(InterviewJsonValueNormalizer.asMap(jsonObject), targetKeys);
        }

        if (candidate instanceof JSONArray jsonArray) {
            return findFirstObjectContainingKeys(InterviewJsonValueNormalizer.asList(jsonArray), targetKeys);
        }

        // （5）字符串尝试解析为对象后再递归
        if (candidate instanceof String text) {
            Map<String, Object> nested = tryParseObject(text);
            return nested == null ? null : findFirstObjectContainingKeys(nested, targetKeys);
        }

        return null;
    }

    /**
     * 判断 Map 是否包含任一目标键。
     */
    private boolean containsAnyKey(Map<String, Object> map, String... targetKeys) {
        if (map == null || map.isEmpty() || targetKeys == null) {
            return false;
        }
        for (String key : targetKeys) {
            if (map.containsKey(key)) {
                return true;
            }
        }
        return false;
    }
}
