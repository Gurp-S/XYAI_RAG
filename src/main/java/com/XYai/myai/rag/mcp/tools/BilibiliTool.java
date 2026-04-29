package com.XYai.myai.rag.mcp.tools;


import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class BilibiliTool{


    @Tool(name = "bilibili",description = "b站视频搜索")
    public String bilibiliService(
            @ToolParam(description = "操作类型") String action,
            @ToolParam(description = "请求参数") String query
    ){
        return "b站视频搜索";
    }
}
