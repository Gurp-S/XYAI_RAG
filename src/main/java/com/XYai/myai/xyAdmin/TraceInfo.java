package com.XYai.myai.xyAdmin;

import com.XYai.myai.security.annotation.AdminOnly;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.NodeRecordMapper;
import com.XYai.myai.mapper.TraceRecordMapper;
import com.XYai.myai.rag.aop.annotation.NodeRecord;
import com.XYai.myai.rag.aop.annotation.TraceRecord;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@AdminOnly
@RequestMapping("/xyAdmin/traceInfo")
@RestController
public class TraceInfo {

    @Resource
    private TraceRecordMapper traceRecordMapper;
    @Resource
    private NodeRecordMapper nodeRecordMapper;

    /**
     * 获取所有链路节点名称（DISTINCT node_name）
     */
    @GetMapping("/nodeNames")
    public Result<List<String>> getAllTraceNodeName() {
        List<NodeRecord> records = nodeRecordMapper.selectList(
                new QueryWrapper<NodeRecord>()
                        .select("DISTINCT node_name")
                        .isNotNull("node_name"));
        List<String> names = records.stream()
                .map(NodeRecord::getNodeName)
                .filter(java.util.Objects::nonNull)
                .toList();
        return Result.success(names);
    }

    /**
     * 获取所有链路根名称（DISTINCT name）
     */
    @GetMapping("/rootNames")
    public Result<List<String>> getAllTraceRootName() {
        List<TraceRecord> records = traceRecordMapper.selectList(
                new QueryWrapper<TraceRecord>()
                        .select("DISTINCT name")
                        .isNotNull("name"));
        List<String> names = records.stream()
                .map(TraceRecord::getName)
                .filter(java.util.Objects::nonNull)
                .toList();
        return Result.success(names);
    }

    /**
     * 分页获取Trace记录（支持搜索、排序）
     * @param page 页码
     * @param size 每页大小
     * @param name 搜索名称（模糊）
     * @param sortBy 排序字段：cost_ms, start_time
     * @param sortOrder asc/desc
     */
    @GetMapping("/traces")
    public Result<Map<String, Object>> getTracesPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "start_time") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime) {
        QueryWrapper<TraceRecord> wrapper = new QueryWrapper<>();

        if (name != null && !name.isBlank()) {
            wrapper.like("name", name);
        }
        if (startTime != null) {
            wrapper.ge("start_time", startTime);
        }
        if (endTime != null) {
            wrapper.le("start_time", endTime);
        }

        // 排序：使用解析后的列名
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        String column = resolveTraceSortColumn(sortBy);
        wrapper.orderBy(true, isAsc, column);

        Page<TraceRecord> tracePage = traceRecordMapper.selectPage(
                new Page<>(page, size), wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", tracePage.getTotal());
        result.put("page", tracePage.getCurrent());
        result.put("size", tracePage.getSize());
        result.put("records", tracePage.getRecords());
        return Result.success(result);
    }

    /**
     * 分页获取Node记录（支持搜索、排序）
     * @param page 页码
     * @param size 每页大小
     * @param nodeName 搜索节点名称
     * @param traceId 搜索traceId
     * @param sortBy 排序字段：cost_ms, start_time
     * @param sortOrder asc/desc
     */
    @GetMapping("/nodes")
    public Result<Map<String, Object>> getNodesPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String nodeName,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "start_time") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime) {
        QueryWrapper<NodeRecord> wrapper = new QueryWrapper<>();

        if (nodeName != null && !nodeName.isBlank()) {
            wrapper.like("node_name", nodeName);
        }
        if (traceId != null && !traceId.isBlank()) {
            wrapper.eq("trace_id", traceId);
        }
        if (startTime != null) {
            wrapper.ge("start_time", startTime);
        }
        if (endTime != null) {
            wrapper.le("start_time", endTime);
        }

        // 排序：使用解析后的列名
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        String column = resolveNodeSortColumn(sortBy);
        wrapper.orderBy(true, isAsc, column);

        Page<NodeRecord> nodePage = nodeRecordMapper.selectPage(
                new Page<>(page, size), wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", nodePage.getTotal());
        result.put("page", nodePage.getCurrent());
        result.put("size", nodePage.getSize());
        result.put("records", nodePage.getRecords());
        return Result.success(result);
    }

    /**
     * 按traceId获取完整链路详情（包含所有节点）
     */
    @GetMapping("/detail")
    public Result<Map<String, Object>> getTraceDetail(@RequestParam String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // 获取Trace记录
        QueryWrapper<TraceRecord> traceWrapper = new QueryWrapper<>();
        traceWrapper.eq("trace_id", traceId);
        TraceRecord trace = traceRecordMapper.selectOne(traceWrapper);
        result.put("trace", trace);
        
        // 获取所有节点
        QueryWrapper<NodeRecord> nodeWrapper = new QueryWrapper<>();
        nodeWrapper.eq("trace_id", traceId).orderByAsc("start_time");
        List<NodeRecord> nodes = nodeRecordMapper.selectList(nodeWrapper);
        result.put("nodes", nodes);
        
        return Result.success(result);
    }

    /**
     * 解析排序字段为数据库列名，兼容驼峰与下划线
     */
    private String resolveTraceSortColumn(String sortBy) {
        if (sortBy == null) return "start_time";
        return switch (sortBy.toLowerCase()) {
            case "starttime", "start_time" -> "start_time";
            case "costms", "cost_ms", "cost", "cosetime", "cose_time" -> "cost_ms";
            default -> "start_time";
        };
    }

    private String resolveNodeSortColumn(String sortBy) {
        if (sortBy == null) return "start_time";
        return switch (sortBy.toLowerCase()) {
            case "nodename", "node_name" -> "node_name";
            case "costms", "cost_ms", "cost_time", "costtime" -> "cost_time";
            case "starttime", "start_time" -> "start_time";
            case "traceid", "trace_id" -> "trace_id";
            default -> "start_time";
        };
    }
}
