package com.XYai.myai.rag.mcp.pojo;

import lombok.Data;

@Data
public class GeoCodeDTO {
    private String province;     // 省
    private String city;         // 市
    private String district;     // 区
    private String address;      // 地址
    private String location;     // 经纬度
}