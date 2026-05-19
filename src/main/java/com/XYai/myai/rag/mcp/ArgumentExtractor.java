package com.XYai.myai.rag.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ArgumentExtractor {

    @Resource(name = "dictCache")
    private Cache<String, Object> dictCache;

    // 工具模板配置
    private static final Map<String, ToolParamTemplate> templates = new HashMap<>();

    @PostConstruct
    public void init() {
        initWeatherTool();
        initGeoCodeTool();
        initReverseGeoCodeTool();
        initKeywordSearchTool();
        initAroundSearchTool();
        initEnterPromptTool();
        initDirectionTool();
        initDistanceTool();
        initIPLocationTool();
        initBilibiliSearchTool();
        initFileSearchTool();
        initMemorySearchTool();
        initTimeNowTool();
    }

    /**
     * 核心方法：根据工具名和用户输入提取参数字典
     */
    public Map<String, Object> extract(String toolName, String query,
            Map<String, String> sessionContext) {
        ToolParamTemplate t = templates.get(toolName);
        if (t == null)
            return Collections.emptyMap();

        Map<String, Object> args = new HashMap<>();
        for (var field : t.getParamFields()) {
            Object val = extractField(field, query, sessionContext);
            args.put(field.paramName(), val != null ? val : field.defaultValue());
        }
        return args;
    }

    // ---- 具体工具注册 -----

    private void initWeatherTool() {
        templates.put("weather_query", new ToolParamTemplate("weather_query", List.of(
                new ParamField("city", ParamField.ExtractType.REGEX,
                        "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙|青岛|大连|厦门|福州|合肥|郑州|济南|哈尔滨|昆明|贵阳|南宁|长春|沈阳|石家庄|太原|呼和浩特|乌鲁木齐|拉萨|西宁|银川|兰州|海口|南昌)[市区县]?)",
                        null),
                new ParamField("extensions", ParamField.ExtractType.DICT, "ext_type_dict", "base"))));
        dictCache.put("ext_type_dict", Map.of("预报", "all", "天气", "base", "实况", "base"));
        // 城市词典补充
        dictCache.put("city_dict", Map.of("帝都", "北京", "魔都", "上海", "羊城", "广州", "鹏城", "深圳"));
    }

    private void initGeoCodeTool() {
        templates.put("geo_code", new ToolParamTemplate("geo_code", List.of(
                new ParamField("address", ParamField.ExtractType.REGEX,
                        "(?<=地址|位置|地方|在哪|去|查|导航到|搜索|找)([^，。,!！?？ ]{2,})", null),
                new ParamField("city", ParamField.ExtractType.REGEX,
                        "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", null))));
    }

    private void initReverseGeoCodeTool() {
        templates.put("reverse_geo_code", new ToolParamTemplate("reverse_geo_code", List.of(
                new ParamField("location", ParamField.ExtractType.REGEX, "(\\d{2,3}\\.\\d+\\s*,\\s*\\d{2,3}\\.\\d+)",
                        null))));
    }

    private void initKeywordSearchTool() {
        templates.put("keyword_search", new ToolParamTemplate("keyword_search", List.of(
                new ParamField("keywords", ParamField.ExtractType.REGEX, "(?<=搜索|查|找|附近|周边)([^，。 ]{1,})", null),
                new ParamField("types", ParamField.ExtractType.DICT, "poi_type_dict", ""),
                new ParamField("region", ParamField.ExtractType.REGEX,
                        "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", ""),
                new ParamField("city_limit", ParamField.ExtractType.CONSTANT, "true", "true"))));
        // 简单的 POI 类型映射
        dictCache.put("poi_type_dict", Map.of(
                "餐饮", "050000", "酒店", "060000", "购物", "070000", "银行", "160100"));
    }

    private void initAroundSearchTool() {
        templates.put("around_search", new ToolParamTemplate("around_search", List.of(
                new ParamField("location", ParamField.ExtractType.REGEX, "(\\d{2,3}\\.\\d+\\s*,\\s*\\d{2,3}\\.\\d+)",
                        null),
                new ParamField("keywords", ParamField.ExtractType.REGEX, "(?<=搜索|找|查)[^，。 ]{1,}", null),
                new ParamField("types", ParamField.ExtractType.DICT, "poi_type_dict", ""),
                new ParamField("radius", ParamField.ExtractType.REGEX, "(\\d+)米", "1000"),
                new ParamField("sortRule", ParamField.ExtractType.CONSTANT, "distance", "distance"))));
    }

    private void initEnterPromptTool() {
        templates.put("enterPrompt", new ToolParamTemplate("enterPrompt", List.of(
                new ParamField("keywords", ParamField.ExtractType.REGEX, "(?<=搜索|查|找)([^，。 ]{1,})", null),
                new ParamField("city", ParamField.ExtractType.REGEX,
                        "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", ""))));
    }

    private void initDirectionTool() {
        templates.put("direction", new ToolParamTemplate("direction", List.of(
                new ParamField("directionType", ParamField.ExtractType.DICT, "travel_mode_dict", "驾车"),
                new ParamField("origin", ParamField.ExtractType.REGEX, "(?<=从)([^到]+?)(?=到)", null),
                new ParamField("destination", ParamField.ExtractType.REGEX, "(?<=到)([^从]+?)(?=$|[。， ])", null),
                new ParamField("city1", ParamField.ExtractType.REGEX,
                        "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", ""),
                new ParamField("city2", ParamField.ExtractType.REGEX,
                        "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", ""))));
        dictCache.put("travel_mode_dict",
                Map.of("步行", "步行", "公交", "公共", "地铁", "公共", "骑车", "骑行", "自行车", "骑行", "开车", "驾车", "驾车", "驾车"));
    }

    private void initDistanceTool() {
        templates.put("calculate_distance", new ToolParamTemplate("calculate_distance", List.of(
                new ParamField("startingPoint", ParamField.ExtractType.REGEX, "(?<=从|起点)([0-9.]+,[0-9.]+)", null),
                new ParamField("endingPoint", ParamField.ExtractType.REGEX, "(?<=到|终点)([0-9.]+,[0-9.]+)", null))));
    }

    private void initIPLocationTool() {
        templates.put("ipLocation", new ToolParamTemplate("ipLocation", List.of(
                new ParamField("type", ParamField.ExtractType.CONSTANT, "4", "4"),
                new ParamField("ip", ParamField.ExtractType.REGEX, "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})",
                        null))));
    }

    private void initBilibiliSearchTool() {
        templates.put("bilibili_search", new ToolParamTemplate("bilibili_search", List.of(
                new ParamField("keyword", ParamField.ExtractType.REGEX, "(?<=搜索|查|找|b站|B站)([^，。 ]{1,})", null),
                new ParamField("type", ParamField.ExtractType.DICT, "bili_type_dict", "video"))));
        dictCache.put("bili_type_dict", Map.of("视频", "video", "用户", "user", "番剧", "Bangumi"));
    }

    private void initFileSearchTool() {
        templates.put("file_search", new ToolParamTemplate("file_search", List.of(
                new ParamField("file_name", ParamField.ExtractType.REGEX, "(?<=打开|文件|查找|搜索)([^，。 ]{1,})", null))));
    }

    private void initMemorySearchTool() {
        templates.put("memery_search", new ToolParamTemplate("memery_search", List.of(
                new ParamField("expr", ParamField.ExtractType.REGEX, "(?<=对话|记忆|历史|之前|关于)([^，。 ]{1,})", null))));
    }

    private void initTimeNowTool() {
        // 无需参数
        templates.put("now_time", new ToolParamTemplate("now_time", List.of(
                new ParamField("expr", ParamField.ExtractType.REGEX, "(?<=你好|现在|早上|中午|时间|晚上|凌晨|白天)([^，。 ]{1,})", null))));
    }

    // ----- 底层提取逻辑 -----

    private Object extractField(ParamField field, String query, Map<String, String> session) {
        return switch (field.extractType()) {
            case REGEX -> extractByRegex(field.pattern(), query);
            case DICT -> extractByDict(field, query);
            case SESSION_LOOKUP -> session.get(field.paramName());
            case CONSTANT -> field.defaultValue();
        };
    }

    private String extractByRegex(String regex, String text) {
        if (regex == null || text == null)
            return null;
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(m.groupCount() > 0 ? 1 : 0); // 返回第一个捕获组或整个匹配
        }
        return null;
    }

    private String extractByDict(ParamField field, String text) {
        Object raw = dictCache.getIfPresent(field.pattern());
        if (!(raw instanceof Map<?, ?> dict))
            return null;
        for (Map.Entry<?, ?> e : dict.entrySet()) {
            if (e.getKey() instanceof String key && e.getValue() instanceof String val) {
                if (text.contains(key)) {
                    return val;
                }
            }
        }
        return null;
    }

    // ======= 内部 POJO =======
    @Getter
    public static class ToolParamTemplate {
        @Setter
        private String toolName;
        private final List<ParamField> paramFields;

        public ToolParamTemplate(String toolName, List<ParamField> paramFields) {
            this.toolName = toolName;
            this.paramFields = paramFields;
        }

    }

    public record ParamField(String paramName, ExtractType extractType, String pattern, String defaultValue) {
            public enum ExtractType {
                REGEX, DICT, SESSION_LOOKUP, CONSTANT
            }

    }
}