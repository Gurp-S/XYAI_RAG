package com.XYai.myai.Config;

import lombok.Data;

@Data
public class Result<T> {
    /**
     * 通用接口返回结果封装，包含状态码、消息与泛型数据。
     *
     * @param <T> 返回的数据类型
     */
    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        /**
         * 创建一个成功的 Result 包装器，code=200, msg="success"
         *
         * @param data 返回的数据（可为 null）
         * @param <T> 数据类型
         * @return 成功结果的 Result 实例
         */
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMsg("success");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(Integer code, String msg) {
        /**
         * 创建一个错误的 Result 包装器。
         *
         * @param code 错误状态码
         * @param msg 错误消息
         * @param <T> 数据类型
         * @return 包含错误信息的 Result 实例
         */
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
}

