package com.XYai.myai.xyAdmin;

import com.XYai.myai.mapper.NodeRecordMapper;
import com.XYai.myai.mapper.TraceRecordMapper;
import com.XYai.myai.rag.aop.annotation.NodeRecord;
import com.XYai.myai.rag.aop.annotation.TraceRecord;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@Slf4j
@RequestMapping("/traceInfo")
@RestController
public class TraceInfo {


    @Resource
    private TraceRecordMapper traceRecordMapper;
    @Resource
    private NodeRecordMapper nodeRecordMapper;


    public List<String> getAllTraceNodeName() {
        return nodeRecordMapper.selectList(
                new QueryWrapper<NodeRecord>()
                        .select("DISTINCT name")
                        .eq("delete",0)
        ).stream().map(Objects::toString).toList();
    }

    public List<String> getAllTraceRootName() {
        return traceRecordMapper.selectList(
                new QueryWrapper<TraceRecord>()
                        .select("DISTINCT name")
                        .eq("delete",0)
        ).stream().map(Objects::toString).toList();
    }

    public List<NodeRecord> getTraceNodesWithName(String traceNodeName){
        QueryWrapper<NodeRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("traceName",traceNodeName);
        return nodeRecordMapper.selectList(queryWrapper);

    }

    public List<TraceRecord> getTraceRootWithName(String traceRootName){
        QueryWrapper<TraceRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("traceName",traceRootName);
        return traceRecordMapper.selectList(queryWrapper);
    }

    public List<NodeRecord> getTraceNodesWithTraceId(String traceRootId){
        QueryWrapper<NodeRecord> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("traceName",traceRootId);
        return nodeRecordMapper.selectList(queryWrapper);
    }


}

