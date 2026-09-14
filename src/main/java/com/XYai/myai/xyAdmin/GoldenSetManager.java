package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.GoldenCaseMapper;
import com.XYai.myai.mapper.GoldenResultMapper;
import com.XYai.myai.rag.evaluate.pojo.GoldenCasePOJO;
import com.XYai.myai.rag.evaluate.pojo.GoldenResultPOJO;
import com.XYai.myai.rag.evaluate.service.GoldenSetService;
import com.XYai.myai.security.annotation.AdminOnly;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 黄金问答集管理（评估闭环：用例 CRUD + 一键跑批 + 结果查询）
 */
@Slf4j
@AdminOnly
@RestController
@RequestMapping("/xyAdmin/evaluate/golden")
public class GoldenSetManager {

    @Resource
    private GoldenCaseMapper goldenCaseMapper;
    @Resource
    private GoldenResultMapper goldenResultMapper;
    @Resource
    private GoldenSetService goldenSetService;

    /** 分页查询用例 */
    @GetMapping("/cases")
    public Result<Map<String, Object>> listCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer enabled) {
        QueryWrapper<GoldenCasePOJO> wrapper = new QueryWrapper<>();
        if (source != null && !source.isBlank()) wrapper.eq("source", source);
        if (enabled != null) wrapper.eq("enabled", enabled);
        wrapper.orderByDesc("id");
        Page<GoldenCasePOJO> p = goldenCaseMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", p.getTotal());
        result.put("page", p.getCurrent());
        result.put("size", p.getSize());
        result.put("records", p.getRecords());
        return Result.success(result);
    }

    /** 新增用例 */
    @PostMapping("/case")
    public Result<String> addCase(@RequestBody GoldenCasePOJO goldenCase) {
        if (goldenCase.getQuestion() == null || goldenCase.getQuestion().isBlank()) {
            return Result.error(400, "question 不能为空");
        }
        goldenCase.setId(null);
        if (goldenCase.getSource() == null) goldenCase.setSource("MANUAL");
        if (goldenCase.getEnabled() == null) goldenCase.setEnabled(1);
        goldenCaseMapper.insert(goldenCase);
        return Result.success("已新增用例 id=" + goldenCase.getId());
    }

    /** 批量导入用例（JSON 数组），返回成功条数与跳过条数（question 为空跳过） */
    @PostMapping("/import")
    public Result<Map<String, Object>> importCases(@RequestBody List<GoldenCasePOJO> cases) {
        int inserted = 0, skipped = 0;
        for (GoldenCasePOJO c : cases) {
            if (c == null || c.getQuestion() == null || c.getQuestion().isBlank()) {
                skipped++;
                continue;
            }
            c.setId(null);
            if (c.getSource() == null || c.getSource().isBlank()) c.setSource("MANUAL");
            if (c.getEnabled() == null) c.setEnabled(1);
            goldenCaseMapper.insert(c);
            inserted++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        return Result.success(result);
    }

    /** 更新用例 */
    @PostMapping("/case/update")
    public Result<String> updateCase(@RequestBody GoldenCasePOJO goldenCase) {
        if (goldenCase.getId() == null) return Result.error(400, "id 不能为空");
        goldenCaseMapper.updateById(goldenCase);
        return Result.success("已更新");
    }

    /** 删除用例 */
    @PostMapping("/case/delete")
    public Result<String> deleteCase(@RequestParam Long id) {
        goldenCaseMapper.deleteById(id);
        return Result.success("已删除");
    }

    /** 一键跑批：对全部启用用例执行检索评估 */
    @PostMapping("/run")
    public Result<GoldenSetService.RunSummary> run() {
        return Result.success(goldenSetService.runAll());
    }

    /** 查询跑批结果（默认最近一批） */
    @GetMapping("/results")
    public Result<Map<String, Object>> results(
            @RequestParam(required = false) Long runId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long targetRunId = runId;
        if (targetRunId == null) {
            GoldenResultPOJO latest = goldenResultMapper.selectOne(
                    new QueryWrapper<GoldenResultPOJO>().orderByDesc("run_id").last("LIMIT 1"));
            targetRunId = latest == null ? 0L : latest.getRunId();
        }
        Page<GoldenResultPOJO> p = goldenResultMapper.selectPage(
                new Page<>(page, size),
                new QueryWrapper<GoldenResultPOJO>().eq("run_id", targetRunId).orderByAsc("case_id"));
        long passed = goldenResultMapper.selectCount(
                new QueryWrapper<GoldenResultPOJO>().eq("run_id", targetRunId).eq("passed", 1));
        long total = goldenResultMapper.selectCount(
                new QueryWrapper<GoldenResultPOJO>().eq("run_id", targetRunId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", targetRunId);
        result.put("total", total);
        result.put("passed", passed);
        result.put("passRate", total == 0 ? 0.0 : (double) passed / total);
        result.put("page", p.getCurrent());
        result.put("size", p.getSize());
        result.put("records", p.getRecords());
        return Result.success(result);
    }
}
