package com.XYai.myai.rag.mcp.pojo;

import lombok.Data;

@Data
public class ReGeoCodeDTO {
    private String formattedAddress; // 格式化地址
    private String province;         // 省
    private String city;             // 市
    private String district;         // 区
    private String township;         // 乡镇/街道
    private String location;         // 经纬度
}