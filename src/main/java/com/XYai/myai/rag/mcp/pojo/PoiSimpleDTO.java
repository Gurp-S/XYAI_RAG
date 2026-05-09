package com.XYai.myai.rag.mcp.pojo;

import lombok.Data;

@Data
public class PoiSimpleDTO {
    private String name;       // 名称
    private String address;    // 地址
    private String location;   // 经纬度
    private String city;       // 城市
    private String distance;   // 距离（米）
}