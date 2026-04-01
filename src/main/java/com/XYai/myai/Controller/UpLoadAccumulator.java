package com.XYai.myai.Controller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;


import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpLoadAccumulator {
    private List<Document> allChunks;
    private List<String> uploadedUrls;
    private List<String> failedFiles;
}
