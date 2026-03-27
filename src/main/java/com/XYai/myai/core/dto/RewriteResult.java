package com.XYai.myai.core.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RewriteResult {

    String standardizedQuery; // 标准化

    String refinedQuery; // 优化后
}
