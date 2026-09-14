package com.XYai.myai.xyAdmin;

import com.XYai.myai.security.annotation.AdminOnly;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.IntentNodeMapper;
import com.XYai.myai.rag.intent.pojo.IntentNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@AdminOnly
@RestController
@RequestMapping("/xyAdmin/intent")
public class IntentTreeManager {

    @Resource
    private IntentNodeMapper intentNodeMapper;

    /**
     * 获取完整意图树（全部节点，按父子关系组织）
     */
    @GetMapping("/tree")
    public Result<List<IntentNode>> getIntentTree() {
        List<IntentNode> allNodes = intentNodeMapper.selectList(null);
        List<IntentNode> roots = new ArrayList<>();

        // 构建树：找根节点（parent_name 为 null）
        for (IntentNode node : allNodes) {
            if (node.getParentName() == null || node.getParentName().isBlank()) {
                roots.add(buildSubTree(node, allNodes));
            }
        }

        log.info("获取完整意图树成功，根节点: {} 个，总节点: {} 个", roots.size(), allNodes.size());
        return Result.success(roots);
    }

    /**
     * 获取单个意图节点及其子树
     */
    @GetMapping("/node")
    public Result<IntentNode> getSingleIntentTree(@RequestParam String nodeName) {
        IntentNode node = intentNodeMapper.selectById(nodeName);
        if (node == null) {
            return Result.error(404, "意图节点不存在: " + nodeName);
        }
        // 获取所有节点用于构建子树
        List<IntentNode> allNodes = intentNodeMapper.selectList(null);
        node.setChildren(buildChildren(node.getName(), allNodes));
        return Result.success(node);
    }

    /**
     * 获取所有叶子节点（children_count = 0）
     */
    @GetMapping("/leaves")
    public Result<List<IntentNode>> getLeafNodes() {
        List<IntentNode> leaves = intentNodeMapper.selectList(
                new QueryWrapper<IntentNode>().eq("children_count", 0));
        return Result.success(leaves);
    }

    /**
     * 递归构建子树
     */
    private IntentNode buildSubTree(IntentNode parent, List<IntentNode> allNodes) {
        List<IntentNode> children = buildChildren(parent.getName(), allNodes);
        parent.setChildren(children);
        return parent;
    }

    /**
     * 查找某节点的所有直接子节点
     */
    private List<IntentNode> buildChildren(String parentName, List<IntentNode> allNodes) {
        List<IntentNode> children = new ArrayList<>();
        for (IntentNode node : allNodes) {
            if (parentName.equals(node.getParentName())) {
                children.add(buildSubTree(node, allNodes));
            }
        }
        return children;
    }
}
