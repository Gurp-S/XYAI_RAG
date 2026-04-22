package com.XYai.myai.rag.etlpipeline.POJO;

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
    public static final Long SKIP_FILE = 1L;
    public static final Long UP_FILE = 0L;
    public static final Long COPY_FILE = 2L;
    public static final Long SKIP_ERROR = 3L;
    public static final Long COPY_CHUNK = 4L;
    private List<Long> copyChunks = new ArrayList<>();
    private Long skipStatus;
}