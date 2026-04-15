package com.XYai.myai.rag.etlpipeline.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeResult implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private boolean success;
    private String message;

    public static NodeResult ok(String message) {
        return NodeResult.builder().success(true).message(message).build();
    }

    public static NodeResult fail(String message) {
        return NodeResult.builder().success(false).message(message).build();
    }
}

