package com.banma.interview.interview.shared;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将Fastjson值安全地规范化为简单的Java MapList结构
 */
public final class InterviewJsonValueNormalizer {

    private InterviewJsonValueNormalizer() {
    }

    /**
     * 将值规范化为 key 为字符串的 Map，非 Map 类型返回 null。
     */
    public static Map<String, Object> asMap(Object value) {
        Object normalized = normalize(value);
        if (!(normalized instanceof Map<?, ?> rawMap)) {
            return null;
        }
        // （1）统一把 Map 的 key 转为字符串，并保留插入顺序
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    /**
     * 将值规范化为 List，非 List 类型返回 null。
     */
    public static List<Object> asList(Object value) {
        Object normalized = normalize(value);
        if (!(normalized instanceof List<?> rawList)) {
            return null;
        }
        return new ArrayList<>(rawList);
    }

    /**
     * 将 Fastjson 值递归规范化为普通 Java Map/List 结构，用于隔离第三方类型。
     */
    public static Object normalize(Object value) {
        return normalize(value, new IdentityHashMap<>());
    }

    /**
     * 带访问标记的递归规范化，避免循环引用导致无限递归。
     */
    private static Object normalize(Object value, IdentityHashMap<Object, Boolean> visiting) {
        // （1）空值与标量直接返回；已访问对象视为循环引用，返回 null
        if (value == null || isScalar(value)) {
            return value;
        }

        if (visiting.containsKey(value)) {
            return null;
        }

        // （2）JSONObject 转为 LinkedHashMap 并递归处理字段值
        if (value instanceof JSONObject jsonObject) {
            visiting.put(value, Boolean.TRUE);
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
                map.put(entry.getKey(), normalize(entry.getValue(), visiting));
            }
            visiting.remove(value);
            return map;
        }

        // （3）JSONArray 转为 ArrayList 并递归处理元素
        if (value instanceof JSONArray jsonArray) {
            visiting.put(value, Boolean.TRUE);
            List<Object> list = new ArrayList<>(jsonArray.size());
            for (Object item : jsonArray) {
                list.add(normalize(item, visiting));
            }
            visiting.remove(value);
            return list;
        }

        // （4）普通 Map 统一 key 为字符串后递归处理
        if (value instanceof Map<?, ?> rawMap) {
            visiting.put(value, Boolean.TRUE);
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                map.put(String.valueOf(entry.getKey()), normalize(entry.getValue(), visiting));
            }
            visiting.remove(value);
            return map;
        }

        // （5）普通 List 转为 ArrayList 后递归处理
        if (value instanceof List<?> rawList) {
            visiting.put(value, Boolean.TRUE);
            List<Object> list = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                list.add(normalize(item, visiting));
            }
            visiting.remove(value);
            return list;
        }

        return value;
    }

    /**
     * 判断是否为无需递归处理的标量类型（字符串、数字、布尔、字符、枚举）。
     */
    private static boolean isScalar(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isEnum();
    }
}
