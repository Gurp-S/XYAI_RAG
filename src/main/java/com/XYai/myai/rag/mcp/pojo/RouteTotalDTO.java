package com.XYai.myai.rag.mcp.pojo;

import lombok.Data;
import java.util.List;

@Data
public class RouteTotalDTO {

    /** 出行类型：驾车/步行/骑行/电动车/公共交通 */
    private String routeType;
    /** 起点经纬度 */
    private String origin;
    /** 终点经纬度 */
    private String destination;
    /** 方案总距离，单位：米 */
    private String totalDistance;
    /** 方案总耗时，单位：秒 */
    private String totalDuration;

    // ========== 驾车专属字段 ==========
    /** 驾车算路策略编码 */
    private String strategy;
    /** 预估出租车费用(元) */
    private String taxiCost;
    /** 道路过路费(元) */
    private String tolls;
    /** 收费路段里程(米) */
    private String tollDistance;
    /** 红绿灯个数 */
    private String trafficLights;
    /** 限行标识 0=无限行 1=限行无法规避 */
    private String restriction;

    // ========== 公交/公共交通专属字段 ==========
    /** 公交方案总票价 */
    private String transitFee;
    /** 换乘次数 */
    private String transferNum;
    /** 是否夜班公交 0否 1是 */
    private String nightFlag;

    // ========== 步行/骑行/电动车 通用补充 ==========
    /** 道路类型说明 */
    private String walkType;

    // ========== 极简路段摘要（不返回详细steps，只留摘要） ==========
    /** 精简路线分段描述，纯文本摘要，无嵌套大对象 */
    private List<String> routeBrief;
}