package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SkipFileInfo {
    public static final int SKIP_FILE = 1;
    public static final int UP_FILE = 0;
    public static final int COPY_FILE = 2;
    public static final int SKIP_ERROR = 3;
    public static final int UP_CHUNK = 4;
    private List<Integer> upChunks = new ArrayList<>();
    private Integer skipStatus;
}