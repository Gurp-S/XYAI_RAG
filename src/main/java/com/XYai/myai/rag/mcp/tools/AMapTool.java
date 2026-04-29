package com.XYai.myai.rag.mcp.tools;

import com.XYai.myai.rag.mcp.POJO.GeoCodeDTO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Objects;

@Slf4j
@Component
public class AMapTool {

    // 固定高德KEY
    private static final String AMAP_KEY = "daceb403e4a6c3d58c283d8a4db885eb";
    @Resource(name = "amapRestTemplate")
    private RestTemplate restTemplate;

    // ============================== 工具方法 ==============================
    private String fail(String msg) {
        log.info("mcp工具失败{}", msg);
        return "{\"status\":0,\"info\":\"" + msg.replace("\"", "'") + "\",\"count\":0}";
    }

    private String filterSuccess(JSONObject data) {
        log.info("mcp工具完成{}", data);
        return "{\"status\":1,\"info\":\"OK\",\"count\":" + data.getOrDefault("count", 1) + ",\"data\":" + data + "}";
    }

    // ============================== 地理编码 ==============================
    @Tool(
            name = "geo_code",
            description = "地址转坐标。参数：address(结构化地址), city(城市名/编码)"
    )
    public String geoCode(
            @ToolParam(description = "结构化地址信息") String address,
            @ToolParam(description = "指定查询的城市") String city
    ) {
        try {
            log.info("geoCode进行中");
            // 纯字符串拼接 + 中文编码
            String url = "https://restapi.amap.com/v3/geocode/geo?address=" + address + "&key=" + AMAP_KEY + "&output=json";

            log.info("geoCodeUrl:{}", url);
            String jsonStr = restTemplate.getForObject(url, String.class);
            JSONObject json = JSON.parseObject(jsonStr);

            String status = json.getString("status");
            log.info("geoCode jsonStr:{}", jsonStr);
            String info = json.getString("info");
            if (!"1".equals(status)) {
                String friendly = switch (info) {
                    case "ENGINE_RESPONSE_DATA_ERROR" -> "高德地图返回数据异常，请稍后重试";
                    case "INVALID_USER_KEY", "INVALID_KEY" -> "高德地图 key 无效或未开通对应服务";
                    case "SERVICE_NOT_EXIST" -> "高德地图服务不存在或未开通";
                    default -> info == null ? "高德地图返回未知错误" : info;
                };
                log.info("mcp工具失败{} - raw: {}", info, jsonStr);
                return fail(friendly);
            }

            JSONArray geocodes = json.getJSONArray("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                return fail("未查询到地址信息");
            }

            JSONObject item = geocodes.getJSONObject(0);
            GeoCodeDTO dto = new GeoCodeDTO();
            dto.setProvince(item.getString("province"));
            dto.setCity(item.getString("city"));
            dto.setDistrict(item.getString("district"));
            dto.setLocation(item.getString("location"));

            return filterSuccess(item);
        } catch (Exception e) {
            return fail("调用失败: " + e.getMessage());
        }
    }

    // ============================== 逆地理编码 ==============================
    @Tool(
            name = "reverse_geo_code",
            description = "坐标转地址。参数：location('经度,纬度'格式)"
    )
    public String reverseGeoCode(@ToolParam(description = "经纬度坐标") String location) {
        if (location == null || !location.matches("^\\d+\\.\\d+,\\d+\\.\\d+$")) {
            return fail("参数格式错误：请输入'经度,纬度'，如 '116.397,39.908'");
        }
        log.info("reverseGeoCode进行中");
        try {
            // 纯字符串拼接
            String url = "https://restapi.amap.com/v3/geocode/regeo?location=" + location + "&key=" + AMAP_KEY + "&output=json";

            String jsonStr = restTemplate.getForObject(url, String.class);
            JSONObject json = JSON.parseObject(jsonStr);
            // 修复：高德官方字段为小写 regeocode
            JSONObject regeocode = json.getJSONObject("regeocode");
            JSONObject addrComp = regeocode.getJSONObject("addressComponent");

            JSONObject res = new JSONObject();
            res.put("formattedAddress", regeocode.getString("formatted_address"));
            res.put("province", addrComp.getString("province"));
            res.put("city", addrComp.getString("city"));
            res.put("district", addrComp.getString("district"));
            res.put("township", addrComp.getString("township"));
            res.put("location", location);

            return filterSuccess(res);
        } catch (Exception e) {
            return fail("调用失败: " + e.getMessage());
        }
    }

    // ============================== 关键字搜索 ==============================
    @Tool(
            name = "keyword_search",
            description = "按关键词搜索POI。参数：keywords(地点名), types(POI类型), region(区划), city_limit(城市编码)"
    )
    public String keywordSearch(
            @ToolParam(description = "地点关键字指定地点类型") String keywords,
            @ToolParam(description = "指定地点类型") String types,
            @ToolParam(description = "搜索区划") String region,
            @ToolParam(description = "指定城市数据召回限制") String city_limit
    ) {
        log.info("keywordSearch进行中");
        try {
            // 纯字符串拼接
            String url = "https://restapi.amap.com/v3/place/text?keywords=" +
                    keywords + "&types=" +
                    (types == null ? "" : types) + "&region=" + (region == null ? "" : region) + "&city_limit=" +
                    (city_limit == null ? "" : city_limit) + "&key=" + AMAP_KEY + "&output=json";

            String jsonStr = restTemplate.getForObject(url, String.class);
            JSONObject json = JSON.parseObject(jsonStr);
            JSONArray pois = json.getJSONArray("pois");

            JSONArray arr = new JSONArray();
            if (pois != null && !pois.isEmpty()) {
                pois.stream().limit(3).forEach(o -> {
                    JSONObject p = (JSONObject) o;
                    JSONObject item = new JSONObject();
                    item.put("name", p.getString("name"));
                    item.put("address", p.getString("address"));
                    item.put("location", p.getString("location"));
                    arr.add(item);
                });
            }

            JSONObject res = new JSONObject();
            res.put("pois", arr);
            return filterSuccess(res);
        } catch (Exception e) {
            return fail("搜索失败: " + e.getMessage());
        }
    }

    // ============================== 周边搜索 ==============================
    @Tool(
            name = "around_search",
            description = "按坐标+半径搜索周边POI。参数：location(中心点'经度,纬度'), keywords(关键字), types(类型), radius(米), sortRule(排序)"
    )
    public String aroundSearch(
            @ToolParam(description = "圆形区域检索中心点（必填）") String location,
            @ToolParam(description = "地点关键字指定地点类型") String keywords,
            @ToolParam(description = "指定地点类型") String types,
            @ToolParam(description = "搜索区划") String region,
            @ToolParam(description = "指定城市数据召回限制") String city_limit,
            @ToolParam(description = "搜索半径") String radius,
            @ToolParam(description = "排序规则") String sortRule
    ) {
        log.info("aroundSearch进行中");
        try {
            // 纯字符串拼接
            String url = "https://restapi.amap.com/v3/place/around?location=" + (location == null ? "" : location) + "&types=" +
                    (types == null ? "" : types) + "&region=" + (region == null ? "" : region) +
                    "&radius=" + (radius == null ? "" : radius) + "&sortRule=" + (sortRule == null ? "" : sortRule) +
                    "&city_limit=" + (city_limit == null ? "" : city_limit) +
                    "&keywords=" + (keywords == null ? "" : keywords) +
                    "&key=" + AMAP_KEY + "&output=json";

            String jsonStr = restTemplate.getForObject(url, String.class);
            JSONObject json = JSON.parseObject(jsonStr);
            JSONArray pois = json.getJSONArray("pois");

            JSONArray arr = new JSONArray();
            if (pois != null && !pois.isEmpty()) {
                pois.stream().limit(3).forEach(o -> {
                    JSONObject p = (JSONObject) o;
                    JSONObject item = new JSONObject();
                    item.put("name", p.getString("name"));
                    item.put("address", p.getString("address"));
                    item.put("location", p.getString("location"));
                    item.put("distance", p.getString("distance"));
                    arr.add(item);
                });
            }

            JSONObject res = new JSONObject();
            res.put("pois", arr);
            return filterSuccess(res);
        } catch (Exception e) {
            return fail("搜索失败: " + e.getMessage());
        }
    }

    // ============================== 输入提示 ==============================
    @Tool(
            name = "enterPrompt",
            description = "关键词搜索建议。参数：keywords(查询词), types(分类), location(坐标), city(城市), datatype(数据类型)"
    )
    public String enterPrompt(
            @ToolParam(description = "查询关键词") String keywords,
            @ToolParam(description = "POI 分类") String types,
            @ToolParam(description = "坐标") String location,
            @ToolParam(description = "搜索城市") String city,
            @ToolParam(description = "仅返回指定城市数据") String cityLimit,
            @ToolParam(description = "返回的数据类型") String datatype
    ) {
        log.info("enterPrompt进行中");
        try {
            // 纯字符串拼接
            String url = "https://restapi.amap.com/v3/assistant/inputtips?keywords=" +
                    keywords + "&types=" +
                    (types == null ? "" : types) + "&location=" + (location == null ? "" : location) +
                    "&city=" + (city == null ? "" : city) + "&cityLimit=" + (cityLimit == null ? "" : cityLimit) +
                    "&datatype=" + (datatype == null ? "" : datatype) + "&key=" + AMAP_KEY + "&output=json";

            String jsonStr = restTemplate.getForObject(url, String.class);
            JSONObject json = JSON.parseObject(jsonStr);
            JSONArray tips = json.getJSONArray("tips");

            JSONArray arr = new JSONArray();
            if (tips != null && !tips.isEmpty()) {
                tips.stream().limit(3).forEach(o -> {
                    JSONObject t = (JSONObject) o;
                    JSONObject item = new JSONObject();
                    item.put("name", t.getString("name"));
                    item.put("address", t.getString("address"));
                    item.put("location", t.getString("location"));
                    arr.add(item);
                });
            }

            JSONObject res = new JSONObject();
            res.put("tips", arr);
            return filterSuccess(res);
        } catch (Exception e) {
            return fail("搜索失败: " + e.getMessage());
        }
    }

    // ============================== 路径规划 ==============================
    @Tool(
            name = "direction",
            description = "路线规划(步行/公交/驾车/骑行)。参数：directionType(类型), origin/destination(坐标), city1/city2(城市编码)"
    )
    public String direction(
            @ToolParam(description = "路径规划类型") String directionType,
            @ToolParam(description = "起点经纬度，格式'经度,纬度'") String origin,
            @ToolParam(description = "终点经纬度，格式'经度,纬度'") String destination,
            @ToolParam(description = "起点所在城市") String city1,
            @ToolParam(description = "目的地所在城市") String city2
    ) {
        if (origin == null || destination == null) {
            return fail("缺少参数：需提供起点和终点坐标");
        }
        if (!origin.matches("^\\d+\\.\\d+,\\d+\\.\\d+$") || !destination.matches("^\\d+\\.\\d+,\\d+\\.\\d+$")) {
            return fail("坐标格式错误：请使用'经度,纬度'格式");
        }
        // 修复：补全缺失的 try 大括号
        try {
            // 基础路径
            String url = switch (directionType.trim()) {
                case "步行" -> "https://restapi.amap.com/v5/direction/walking";
                case "公共" -> "https://restapi.amap.com/v5/direction/transit/integrated";
                case "驾车" -> "https://restapi.amap.com/v5/direction/driving";
                case "骑行" -> "https://restapi.amap.com/v5/direction/bicycling";
                default -> throw new IllegalArgumentException("不支持的路线类型");
            };
            // 纯字符串拼接最终URL
            String urlResult = url + "?origin=" + origin + "&destination=" + destination;
            if (directionType.trim().equals("公共")) urlResult += "&city1=" + city1 + "&city2=" + city2;
            urlResult += "&key=" + AMAP_KEY + "&output=json&show_fields=cost";
            String jsonStr = restTemplate.getForObject(urlResult, String.class);
            JSONObject json = JSON.parseObject(jsonStr);
            JSONObject route = json.getJSONObject("route");

            JSONArray paths;
            if ("公共".equals(directionType.trim())) {
                paths = route.getJSONArray("transits");
            } else {
                paths = route.getJSONArray("paths");
            }

            if (paths == null || paths.isEmpty()) {
                return fail("未规划到路线");
            }

            JSONObject path = paths.getJSONObject(0);
            JSONObject res = new JSONObject();
            res.put("routeType", directionType);
            res.put("origin", origin);
            res.put("destination", destination);
            res.put("distance", path.getString("distance"));

            // 修复4：骑行/步行 没有cost字段，直接从path取时长
            JSONObject cost = path.getJSONObject("cost");
            res.put("duration", Objects.requireNonNullElse(cost, path).getString("duration"));

            // 驾车独有参数
            if ("驾车".equals(directionType.trim())) {
                res.put("taxiCost", route.getString("taxi_cost"));
                res.put("tolls", cost != null ? cost.getString("tolls") : "0");
                res.put("trafficLights", path.getString("traffic_lights"));
                res.put("restriction", path.getString("restriction"));
            }

            return filterSuccess(res);
        } catch (Exception e) {
            return fail("路线规划失败: " + e.getMessage());
        }
    }

    // ============================== 直线距离 ==============================
    @Tool(
            name = "calculate_distance",
            description = "计算两点直线距离(米)。参数：startingPoint/endingPoint('经度,纬度'格式)"
    )
    public String calculateDistance(
            @ToolParam(description = "起点经纬度，格式'经度,纬度'", required = true) String startingPoint,
            @ToolParam(description = "终点经纬度，格式'经度,纬度'", required = true) String endingPoint
    ) {
        if (startingPoint == null || endingPoint == null) {
            return fail("缺少参数：需提供起点和终点坐标");
        }
        if (!startingPoint.matches("^\\d+\\.\\d+,\\d+\\.\\d+$") || !endingPoint.matches("^\\d+\\.\\d+,\\d+\\.\\d+$")) {
            return fail("坐标格式错误：请使用'经度,纬度'格式");
        }
        log.info("calculateDistance进行中");
        try {
            // 纯字符串拼接
            String url = "https://restapi.amap.com/v3/distance?origins=" + startingPoint + "&destination=" + endingPoint + "&key=" + AMAP_KEY + "&output=json";

            String jsonStr = restTemplate.getForObject(url, String.class);
            JSONObject json = JSON.parseObject(jsonStr);
            JSONArray results = json.getJSONArray("results");
            String distance = results.getJSONObject(0).getString("distance");

            JSONObject res = new JSONObject();
            res.put("distance", distance);
            res.put("origin", startingPoint);
            res.put("destination", endingPoint);
            return filterSuccess(res);
        } catch (Exception e) {
            return fail("距离计算失败: " + e.getMessage());
        }
    }

    // ============================== 天气 ==============================
    @Tool(
            name = "weather_query",
            description = "查询城市天气(实况/预报)。参数：city(城市adcode), extensions(base=实况/all=预报)"
    )
    public String weather(
            @ToolParam(description = "城市编码") String city,
            @ToolParam(description = "气象类型") String extensions
    ) {
        log.info("weather查询：city={}, extensions={}", city, extensions);
        try {
            // 1. 安全默认值
            if (extensions == null || extensions.isBlank()) {
                extensions = "base";
            }
            String url = "https://restapi.amap.com/v3/weather/weatherInfo?city="
                    + city + "&extensions=" + extensions
                    + "&key=" + AMAP_KEY + "&output=json";
            String jsonStr = restTemplate.getForObject(url, String.class);
            log.info("高德返回：{}", jsonStr);
            JSONObject json = JSON.parseObject(jsonStr);

            // 2. 状态判断
            if (!"1".equals(json.getString("status"))) {
                return fail("天气查询失败：" + json.getString("info"));
            }
            JSONObject res = new JSONObject();
            if ("base".equals(extensions)) {
                JSONArray lives = json.getJSONArray("lives");
                if (lives == null || lives.isEmpty()) {
                    return fail("未获取到实况天气");
                }
                JSONObject w = lives.getJSONObject(0);
                res.put("city", w.getString("city"));
                res.put("weather", w.getString("weather"));
                res.put("temperature", w.getString("temperature"));
                res.put("wind", w.getString("winddirection") + w.getString("windpower") + "级");
                res.put("humidity", w.getString("humidity") + "%");
            }
            else if ("all".equals(extensions)) {
                JSONArray forecasts = json.getJSONArray("forecasts");
                if (forecasts == null || forecasts.isEmpty()) {
                    return fail("未获取到天气预报");
                }
                JSONObject forecast = forecasts.getJSONObject(0);
                res.put("city", forecast.getString("city"));
                res.put("reportTime", forecast.getString("reporttime"));
                res.put("forecasts", forecast.getJSONArray("casts"));
            }
            return filterSuccess(res);
        } catch (Exception e) {
            log.error("天气异常", e);
            return fail("天气服务异常：" + e.getMessage());
        }
    }

    // ============================== IP定位 ==============================
    @Tool(
            name = "ipLocation",
            description = "IP转地理位置。参数：type(4=IPv4/6=IPv6), ip(IP地址)"
    )
    public String ipLocation(
            @ToolParam(description = "ip 类型") String type,
            @ToolParam(description = "ip 地址") String ip
    ) {
        log.info("ipLocation进行中");
        try {
            // 纯字符串拼接
            String url = "https://restapi.amap.com/v5/ip/location?ip=" + ip + "&type=" + type + "&key=" + AMAP_KEY + "&output=json";

            String jsonStr = restTemplate.getForObject(url, String.class);
            JSONObject json = JSON.parseObject(jsonStr);

            JSONObject res = new JSONObject();
            res.put("ip", ip);
            res.put("country", json.getString("country"));
            res.put("province", json.getString("province"));
            res.put("city", json.getString("city"));
            res.put("location", json.getString("location"));
            return filterSuccess(res);
        } catch (Exception e) {
            return fail("IP定位失败: " + e.getMessage());
        }
    }
}