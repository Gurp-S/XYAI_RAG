package com.XYai.myai.Controller;


import com.XYai.myai.Config.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/upload")
public class UploadController {

    // 这里通常使用 PostMapping 处理文件上传
    @PostMapping("up")
    public Result<String> upLoad(@RequestParam("file") MultipartFile file){
        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        // 1. 上传文件 (获取输入流)
        // String fileName = file.getOriginalFilename();
        
        // 2. 解析文件内容 (核心步骤缺失：需要将 PDF/Word/等流转换为纯文本)
        // Use Tika

        // 3. 文本分块 (Splitter)

        // 4. 文本向量化 (Embedding Model)

        // 5. 存入向量数据库 (Vector Store / Milvus)

        return Result.success("上传并处理成功");
    }
}