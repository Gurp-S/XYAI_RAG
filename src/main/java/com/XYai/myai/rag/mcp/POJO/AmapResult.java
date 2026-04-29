package com.XYai.myai.rag.mcp.POJO;

import lombok.Data;

@Data
public class AmapResult<T> {
    private Integer status;
    private String info;
    private String infoCode;
    private Integer count;
    private T data;

    public static <T> AmapResult<T> success(T data, int count) {
        AmapResult<T> res = new AmapResult<>();
        res.setStatus(1);
        res.setInfo("OK");
        res.setInfoCode("10000");
        res.setCount(count);
        res.setData(data);
        return res;
    }

    public static <T> AmapResult<T> fail(String msg) {
        AmapResult<T> res = new AmapResult<>();
        res.setStatus(0);
        res.setInfo(msg);
        res.setInfoCode("0");
        res.setCount(0);
        res.setData(null);
        return res;
    }
}