//package com.XYai.myai.RAG.intent;
//
//import com.XYai.myai.mapper.IntentNodeMapper;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.ApplicationArguments;
//import org.springframework.boot.ApplicationRunner;
//import org.springframework.stereotype.Component;
//
//import java.time.LocalDateTime;
//import java.util.LinkedHashMap;
//import java.util.Map;
//
///**
// * 应用启动时将意图树写入数据库（幂等）。
// */
//@Component
//public class IntentNodeInitializer implements ApplicationRunner {
//
//    private static final Logger log = LoggerFactory.getLogger(IntentNodeInitializer.class);
//
//    private final IntentNodeMapper intentNodeMapper;
//
//    @Autowired
//    public IntentNodeInitializer(IntentNodeMapper intentNodeMapper) {
//        this.intentNodeMapper = intentNodeMapper;
//    }
//
//    @Override
//    public void run(ApplicationArguments args) {
//        log.info("开始初始化数据库中的意图节点（如果尚未存在则写入/否则更新）");
//
//        try {
//            // 使用 LinkedHashMap 保持顺序，保证首个出现的 display name 成为 nodeId 的主名（用于 parent 解析）
//            Map<String, String> nameToNodeId = new LinkedHashMap<>();
//
//            // 基本节点
//            nameToNodeId.put("根节点", "root");
//            nameToNodeId.put("提问", "root-query");
//            nameToNodeId.put("公司", "root-query-company");
//            nameToNodeId.put("人事", "root-query-company-personnel");
//            nameToNodeId.put("请假", "root-query-company-personnel-leave");
//            nameToNodeId.put("闲聊", "root-chat");
//            nameToNodeId.put("用户", "root-chat-user");
//
//            // 公司信息
//            nameToNodeId.put("公司简介", "root-query-company-intro");
//            nameToNodeId.put("公司地址", "root-query-company-location");
//            nameToNodeId.put("公司电话", "root-query-company-contact");
//            nameToNodeId.put("营业时间", "root-query-company-hours");
//
//            // 人事/考勤/薪酬/报销
//            nameToNodeId.put("考勤", "root-query-company-personnel-attendance");
//            nameToNodeId.put("打卡", "root-query-company-personnel-attendance-punch");
//            nameToNodeId.put("迟到早退", "root-query-company-personnel-attendance-exceptions");
//            nameToNodeId.put("薪酬", "root-query-company-payroll");
//            nameToNodeId.put("工资", "root-query-company-payroll");
//            nameToNodeId.put("报销", "root-query-company-reimbursement");
//            nameToNodeId.put("报销流程", "root-query-company-reimbursement-process");
//
//            // 请假细分
//            nameToNodeId.put("请假流程", "root-query-company-personnel-leave-process");
//            nameToNodeId.put("请假审批", "root-query-company-personnel-leave-approval");
//            nameToNodeId.put("病假", "root-query-company-personnel-leave-sick");
//            nameToNodeId.put("事假", "root-query-company-personnel-leave-personal");
//            nameToNodeId.put("年假", "root-query-company-personnel-leave-annual");
//            nameToNodeId.put("婚假", "root-query-company-personnel-leave-marriage");
//            nameToNodeId.put("产假", "root-query-company-personnel-leave-maternity");
//            nameToNodeId.put("陪产假", "root-query-company-personnel-leave-paternity");
//            nameToNodeId.put("调休", "root-query-company-personnel-leave-compensatory");
//
//            // 招聘/入职
//            nameToNodeId.put("招聘", "root-query-company-recruit");
//            nameToNodeId.put("入职", "root-query-company-onboarding");
//            nameToNodeId.put("入职流程", "root-query-company-onboarding-process");
//            nameToNodeId.put("离职流程", "root-query-company-offboarding");
//
//            // IT 支持
//            nameToNodeId.put("IT 支持", "root-query-company-it");
//            nameToNodeId.put("电脑故障", "root-query-company-it-hardware");
//            nameToNodeId.put("网络问题", "root-query-company-it-network");
//            nameToNodeId.put("无法上网", "root-query-company-it-network");
//            nameToNodeId.put("VPN", "root-query-company-it-vpn");
//            nameToNodeId.put("邮箱问题", "root-query-company-it-email");
//            nameToNodeId.put("重置密码", "root-query-company-it-password-reset");
//
//            // 文档检索
//            nameToNodeId.put("员工手册", "root-query-company-doc-handbook");
//            nameToNodeId.put("政策文件", "root-query-company-doc-policy");
//            nameToNodeId.put("财务报表", "root-query-company-doc-finance");
//            nameToNodeId.put("合同模板", "root-query-company-doc-contracts");
//
//            // 审批
//            nameToNodeId.put("审批流程", "root-query-company-approval");
//            nameToNodeId.put("请假审批流程", "root-query-company-approval-leave");
//            nameToNodeId.put("报销审批流程", "root-query-company-approval-reimbursement");
//
//            // 产品/售后
//            nameToNodeId.put("产品介绍", "root-query-company-product");
//            nameToNodeId.put("产品价格", "root-query-company-product-price");
//            nameToNodeId.put("售后服务", "root-query-company-support");
//
//            // 闲聊子节点
//            nameToNodeId.put("问候", "root-chat-greeting");
//            nameToNodeId.put("你好", "root-chat-greeting");
//            nameToNodeId.put("早上好", "root-chat-greeting");
//            nameToNodeId.put("再见", "root-chat-farewell");
//            nameToNodeId.put("谢谢", "root-chat-acknowledge");
//            nameToNodeId.put("笑话", "root-chat-joke");
//            nameToNodeId.put("天气", "root-chat-weather");
//
//            // 同义词示例
//            nameToNodeId.put("请假怎么填", "root-query-company-personnel-leave-process");
//            nameToNodeId.put("如何请假", "root-query-company-personnel-leave-process");
//            nameToNodeId.put("怎么报销", "root-query-company-reimbursement-process");
//
//            // 1) 生成 nodeId -> 首个 display name 的映射，用于解析 parent
//            Map<String, String> nodeIdToPrimaryName = new LinkedHashMap<>();
//            for (Map.Entry<String, String> e : nameToNodeId.entrySet()) {
//                String name = e.getKey();
//                String nodeId = e.getValue();
//                if (!nodeIdToPrimaryName.containsKey(nodeId)) {
//                    nodeIdToPrimaryName.put(nodeId, name);
//                }
//            }
//
//            // 2) 插入或更新每个显示名记录（主键为 name）
//            LocalDateTime now = LocalDateTime.now();
//            for (Map.Entry<String, String> e : nameToNodeId.entrySet()) {
//                String displayName = e.getKey();
//                String nodeId = e.getValue();
//
//                // 推断 parent 的 nodeId（例如 root-query-company-personnel-leave-annual -> parent root-query-company-personnel-leave）
//                String parentName = null;
//                if (nodeId != null && !"root".equals(nodeId)) {
//                    int idx = nodeId.lastIndexOf('-');
//                    if (idx > 0) {
//                        String parentNodeId = nodeId.substring(0, idx);
//                        parentName = nodeIdToPrimaryName.get(parentNodeId);
//                    }
//                }
//
//                IntentNode node = IntentNode.builder()
//                        .name(displayName)
//                        .nodeId(nodeId)
//                        .parentName(parentName)
//                        .createdAt(now)
//                        .updatedAt(now)
//                        .build();
//
//                // 幂等写入：根据主键 name 判断插入或更新
//                IntentNode exist = intentNodeMapper.selectById(displayName);
//                if (exist == null) {
//                    intentNodeMapper.insert(node);
//                } else {
//                    // 保留原 createdAt，当其它字段在新对象为 null 时也保留原值，避免覆盖为 null
//                    node.setCreatedAt(exist.getCreatedAt() == null ? now : exist.getCreatedAt());
//
//                    if (node.getKbId() == null) node.setKbId(exist.getKbId());
//                    if (node.getDescription() == null) node.setDescription(exist.getDescription());
//                    if (node.getExamples() == null) node.setExamples(exist.getExamples());
//                    if (node.getCollectionName() == null) node.setCollectionName(exist.getCollectionName());
//                    if (node.getMcpToolId() == null) node.setMcpToolId(exist.getMcpToolId());
//                    if (node.getTopK() == null) node.setTopK(exist.getTopK());
//                    if (node.getPromptTemplate() == null) node.setPromptTemplate(exist.getPromptTemplate());
//                    if (node.getChildrenCount() == null) node.setChildrenCount(exist.getChildrenCount());
//
//                    intentNodeMapper.updateById(node);
//                }
//            }
//
//            // 3) 计算 children_count 并批量更新
//            Map<String, Integer> childrenCount = new LinkedHashMap<>();
//            for (Map.Entry<String, String> e : nameToNodeId.entrySet()) {
//                String displayName = e.getKey();
//                IntentNode saved = intentNodeMapper.selectById(displayName);
//                if (saved != null && saved.getParentName() != null) {
//                    childrenCount.put(saved.getParentName(), childrenCount.getOrDefault(saved.getParentName(), 0) + 1);
//                }
//            }
//
//            for (Map.Entry<String, Integer> cc : childrenCount.entrySet()) {
//                String parentDisplay = cc.getKey();
//                Integer cnt = cc.getValue();
//                IntentNode parent = intentNodeMapper.selectById(parentDisplay);
//                if (parent != null) {
//                    parent.setChildrenCount(cnt);
//                    parent.setUpdatedAt(LocalDateTime.now());
//                    intentNodeMapper.updateById(parent);
//                }
//            }
//
//            log.info("意图节点初始化完成，处理 {} 条 display-name 条目，更新 {} 个 parent children_count", nameToNodeId.size(), childrenCount.size());
//        } catch (Exception ex) {
//            log.error("初始化意图节点时发生错误：{}", ex.getMessage(), ex);
//        }
//    }
//}
//
//
