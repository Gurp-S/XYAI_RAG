package com.XYai.myai.rag.mcp;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.*;

@Component
public class ArgumentExtractor {

    // 工具模板配置
    private Map<String, ToolParamTemplate> templates = new HashMap<>();
    // 可复用的词典（key 为词典名）
    private Map<String, Map<String, String>> dictCache = new HashMap<>();

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
        if (t == null) return Collections.emptyMap();

        Map<String, Object> args = new HashMap<>();
        for (var field : t.getParamFields()) {
            Object val = extractField(field, query, sessionContext);
            args.put(field.getParamName(), val != null ? val : field.getDefaultValue());
        }
        return args;
    }

    // ---- 具体工具注册 -----

    private void initWeatherTool() {
        templates.put("weather_query", new ToolParamTemplate("weather_query", List.of(
                new ParamField("city", ParamField.ExtractType.REGEX, "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙|青岛|大连|厦门|福州|合肥|郑州|济南|哈尔滨|昆明|贵阳|南宁|长春|沈阳|石家庄|太原|呼和浩特|乌鲁木齐|拉萨|西宁|银川|兰州|海口|南昌)[市区县]?)", null),
                new ParamField("extensions", ParamField.ExtractType.DICT, "ext_type_dict", "base")
        )));
        dictCache.put("ext_type_dict", Map.of("预报", "all", "天气", "base", "实况", "base"));
        // 城市词典补充
        dictCache.put("city_dict", Map.of("帝都", "北京", "魔都", "上海", "羊城", "广州", "鹏城", "深圳"));
    }

    private void initGeoCodeTool() {
        templates.put("geo_code", new ToolParamTemplate("geo_code", List.of(
                new ParamField("address", ParamField.ExtractType.REGEX, "(?<=地址|位置|地方|在哪|去|查|导航到|搜索|找)([^，。,!！?？\s]{2,})", null),
                new ParamField("city", ParamField.ExtractType.REGEX, "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", null)
        )));
    }

    private void initReverseGeoCodeTool() {
        templates.put("reverse_geo_code", new ToolParamTemplate("reverse_geo_code", List.of(
                new ParamField("location", ParamField.ExtractType.REGEX, "(\\d{2,3}\\.\\d+\\s*,\\s*\\d{2,3}\\.\\d+)", null)
        )));
    }

    private void initKeywordSearchTool() {
        templates.put("keyword_search", new ToolParamTemplate("keyword_search", List.of(
                new ParamField("keywords", ParamField.ExtractType.REGEX, "(?<=搜索|查|找|附近|周边)([^，。\s]{1,})", null),
                new ParamField("types", ParamField.ExtractType.DICT, "poi_type_dict", ""),
                new ParamField("region", ParamField.ExtractType.REGEX, "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", ""),
                new ParamField("city_limit", ParamField.ExtractType.CONSTANT, "true", "true")
        )));
        // 简单的 POI 类型映射
        dictCache.put("poi_type_dict", Map.of(
                "餐饮", "050000", "酒店", "060000", "购物", "070000", "银行", "160100"));
    }

    private void initAroundSearchTool() {
        templates.put("around_search", new ToolParamTemplate("around_search", List.of(
                new ParamField("location", ParamField.ExtractType.REGEX, "(\\d{2,3}\\.\\d+\\s*,\\s*\\d{2,3}\\.\\d+)", null),
                new ParamField("keywords", ParamField.ExtractType.REGEX, "(?<=搜索|找|查)[^，。\s]{1,}", null),
                new ParamField("types", ParamField.ExtractType.DICT, "poi_type_dict", ""),
                new ParamField("radius", ParamField.ExtractType.REGEX, "(\\d+)米", "1000"),
                new ParamField("sortRule", ParamField.ExtractType.CONSTANT, "distance", "distance")
        )));
    }

    private void initEnterPromptTool() {
        templates.put("enterPrompt", new ToolParamTemplate("enterPrompt", List.of(
                new ParamField("keywords", ParamField.ExtractType.REGEX, "(?<=搜索|查|找)([^，。\s]{1,})", null),
                new ParamField("city", ParamField.ExtractType.REGEX, "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", "")
        )));
    }

    private void initDirectionTool() {
        templates.put("direction", new ToolParamTemplate("direction", List.of(
                new ParamField("directionType", ParamField.ExtractType.DICT, "travel_mode_dict", "驾车"),
                new ParamField("origin", ParamField.ExtractType.REGEX, "(?<=从)([^到]+?)(?=到)", null),
                new ParamField("destination", ParamField.ExtractType.REGEX, "(?<=到)([^从]+?)(?=$|[。，\s])", null),
                new ParamField("city1", ParamField.ExtractType.REGEX, "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", ""),
                new ParamField("city2", ParamField.ExtractType.REGEX, "((?:北京|上海|广州|深圳|杭州|成都|武汉|重庆|南京|天津|苏州|西安|长沙)[市区县]?)", "")
        )));
        dictCache.put("travel_mode_dict", Map.of("步行", "步行", "公交", "公共", "地铁", "公共", "骑车", "骑行", "自行车", "骑行", "开车", "驾车", "驾车", "驾车"));
    }

    private void initDistanceTool() {
        templates.put("calculate_distance", new ToolParamTemplate("calculate_distance", List.of(
                new ParamField("startingPoint", ParamField.ExtractType.REGEX, "(?<=从|起点)([0-9.]+,[0-9.]+)", null),
                new ParamField("endingPoint", ParamField.ExtractType.REGEX, "(?<=到|终点)([0-9.]+,[0-9.]+)", null)
        )));
    }

    private void initIPLocationTool() {
        templates.put("ipLocation", new ToolParamTemplate("ipLocation", List.of(
                new ParamField("type", ParamField.ExtractType.CONSTANT, "4", "4"),
                new ParamField("ip", ParamField.ExtractType.REGEX, "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})", null)
        )));
    }

    private void initBilibiliSearchTool() {
        templates.put("bilibili_search", new ToolParamTemplate("bilibili_search", List.of(
                new ParamField("keyword", ParamField.ExtractType.REGEX, "(?<=搜索|查|找|b站|B站)([^，。\s]{1,})", null),
                new ParamField("type", ParamField.ExtractType.DICT, "bili_type_dict", "video")
        )));
        dictCache.put("bili_type_dict", Map.of("视频", "video", "用户", "user", "番剧", "bangumi"));
    }

    private void initFileSearchTool() {
        templates.put("file_search", new ToolParamTemplate("file_search", List.of(
                new ParamField("file_name", ParamField.ExtractType.REGEX, "(?<=打开|文件|查找|搜索)([^，。\s]{1,})", null)
        )));
    }

    private void initMemorySearchTool() {
        templates.put("memery_search", new ToolParamTemplate("memery_search", List.of(
                new ParamField("expr", ParamField.ExtractType.REGEX, "(?<=对话|记忆|历史|之前|关于)([^，。\s]{1,})", null)
        )));
    }

    private void initTimeNowTool() {
        // 无需参数
        templates.put("now_time", new ToolParamTemplate("now_time", List.of()));
    }

    // ----- 底层提取逻辑 -----

    private Object extractField(ParamField field, String query, Map<String, String> session) {
        switch (field.getExtractType()) {
            case REGEX:
                return extractByRegex(field.getPattern(), query);
            case DICT:
                return extractByDict(field, query);
            case SESSION_LOOKUP:
                return session.get(field.getParamName());
            case CONSTANT:
                return field.getDefaultValue();
            default:
                return null;
        }
    }

    private String extractByRegex(String regex, String text) {
        if (regex == null || text == null) return null;
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(m.groupCount() > 0 ? 1 : 0); // 返回第一个捕获组或整个匹配
        }
        return null;
    }

    private String extractByDict(ParamField field, String text) {
        Map<String, String> dict = dictCache.get(field.getPattern());
        if (dict == null) return null;
        for (Map.Entry<String, String> e : dict.entrySet()) {
            if (text.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    // ======= 内部 POJO =======
    public static class ToolParamTemplate {
        private String toolName;
        private List<ParamField> paramFields;

        public ToolParamTemplate(String toolName, List<ParamField> paramFields) {
            this.toolName = toolName;
            this.paramFields = paramFields;
        }
        public List<ParamField> getParamFields() { return paramFields; }
    }

    public static class ParamField {
        private String paramName;
        private ExtractType extractType;
        private String pattern;
        private String defaultValue;

        public enum ExtractType { REGEX, DICT, SESSION_LOOKUP, CONSTANT }

        public ParamField(String paramName, ExtractType extractType, String pattern, String defaultValue) {
            this.paramName = paramName;
            this.extractType = extractType;
            this.pattern = pattern;
            this.defaultValue = defaultValue;
        }
        public String getParamName() { return paramName; }
        public ExtractType getExtractType() { return extractType; }
        public String getPattern() { return pattern; }
        public String getDefaultValue() { return defaultValue; }
    }
}