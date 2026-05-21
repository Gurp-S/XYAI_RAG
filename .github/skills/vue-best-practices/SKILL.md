---
name: vue-best-practices
description: "Design and implement intentional, bold, production-ready frontend UI in Vue/Vite projects. Use for page redesign, component styling, visual direction, responsive layout, typography, color systems, motion, and UX polish."
argument-hint: "What should be designed or redesigned? Include screen/component, brand mood, constraints, and target devices."
---

# Vue Best Practices — SKILL

## 概要

该技能（Skill）用于在工作区内对 Vue 项目进行基于实践的审查与改进建议，覆盖架构、组件设计、性能、可访问性、状态管理与构建管道等方面。目标是生成可执行的检查清单（checklist）、自动可选修复（可选）与逐步改进建议，便于在代码评审或重构中复用。

## 作用域（Scope）

- 默认：工作区范围（workspace-scoped），定位 `frontend/` 或 `package.json` 所在目录。
- 可配置为个人偏好（personal-scope），例如只生成风格建议而不修改代码。

## 前提条件

- Node.js 与包管理器（npm / yarn / pnpm）可用于在工作区执行脚本。
- 项目包含 Vue（2.x 或 3.x）项目结构（存在 `package.json` 且依赖中包含 `vue`）。
- 可选：项目有 `npm run lint` / `npm run build` / `npm test` 脚本以便自动化校验。

## 输入触发示例

- 简洁触发："Run Vue Best Practices scan"
- 文件级触发："Review `frontend/src/components/MainChat.vue` for Vue best practices"
- 批量触发："Apply auto-fixes for lint issues in frontend"

## 输出

- 人类可读的检查清单（包含每项问题、严重程度、建议修复）
- 可选的自动修复补丁（单文件或小范围），以 git patch 或直接修改文件返回
- 若请求，生成 PR/变更摘要（需用户授权创建分支/提交）

## 工作流程（逐步）

1. 探测与准备
   - 检查 `package.json`、识别 Vue 版本、检测包管理器（npm/yarn/pnpm）、检测类型脚本（TypeScript）和 monorepo 结构。
2. 静态检查（可选自动）
   - 执行 `npm install`（如需要）并运行 `npm run lint` 与 `npm run test`（若存在）。
   - 收集 ESLint/TypeScript 错误与 Warning。
3. 组件审查
   - 检查大型 SFC（文件 > 300 行）并建议拆分。
   - 检查是否滥用 `v-html`、非受控 DOM 更新、或在模板中嵌入长逻辑。
   - 建议 Composition API（Vue3）或保持一致的 API 风格（若需迁移则记录为建议）。
4. 状态与路由
   - 验证状态管理（Pinia / Vuex）使用是否合理：是否存在过度集中状态、缺少模块化、重复 API 调用。
   - 检查路由懒加载与权限守卫实现。
5. 网络请求与安全
   - 审查 fetch/axios 用法：避免在组件中重复创建请求、统一错误处理、注意 CSRF/凭证传递。
   - 标注需要从后端改动（如不安全 HTTP 方法、缺乏验证）的点。
6. 性能与资源
   - 检查大资源（图片、字体）、未使用依赖、未懒加载组件、bundle 分割建议。
   - 建议动态 import 路由或组件以减小初始包体量。
7. 可访问性（A11y）
   - 检查语义标签、焦点管理、键盘导航、ARIA 属性的合理性。
8. 可维护性与风格
   - 检查是否遵循样式和命名约定、是否有重复逻辑、事件与 prop 的约束是否明确。
9. 报告与补丁
   - 生成检查清单并按优先级排序（critical / major / minor）。
   - 若用户允许，尝试对 ESLint 可自动修复的问题执行 `--fix` 并产生补丁。
10. 验证

- 运行 `npm run build` 与 `npm test`（若存在）以验证修复是否引入回归。

## 决策点与分支逻辑

- Vue 版本：
  - Vue 2 → 侧重 Options API 优化与迁移策略；建议使用 Composition API 作为可选迁移目标。
  - Vue 3 → 推荐 Composition API、Pinia、并检查 Suspense / Teleport 用法。
- 包管理器：根据检测结果使用 `npm` / `yarn` / `pnpm` 对应命令。
- 自动化修复策略：
  - 用户选择 `suggest-only`：只生成建议与补丁，不修改工作树。
  - 用户选择 `apply-fixes`：在临时分支上应用自动修复并生成补丁/提交（需授权）。
- SSR / Nuxt：若检测到 Nuxt 或 SSR，启用服务端渲染相关检查（如数据获取模式、hydrate 问题）。

## 完成标准（质量准则）

- ESLint 错误数为 0（或根据用户阈值）
- `npm run build` 成功退出（0）
- 关键页面的 Lighthouse 性能可达可接受阈值（可选）
- 无明显安全风险（如未经清洗的 `v-html`、敏感凭证硬编码）
- 可访问性（基本）无严重阻断问题（如缺失可聚焦元素或键盘不可达）

## 示例提示（可直接发送给助手以触发本 Skill）

- "Run Vue Best Practices scan and produce checklist for frontend"
- "Generate auto-fix patch for ESLint issues in `frontend/src` (apply-fixes=false)"
- "Refactor `frontend/src/components/MainChat.vue` to Composition API suggestions"
- "Optimize bundle: analyze and suggest top-5 dependency removals"

## 输出示例

- 检查清单（JSON + 可读文本）：包含文件、行号、问题描述、建议、严重性。
- 补丁（.patch）或直接文件修改（需用户同意）。
- 可选：创建分支并提交、或生成 PR 模板供用户手动创建。

## 需要澄清的点（放在这里便于用户快速回答）

- 希望技能以工作区范围（默认）运行，还是只对单个目录/组件运行？
- 是否允许自动在代码库上直接应用 `--fix` 更改？还是仅生成补丁供审阅？
- 优先级关注点：性能、可访问性、安全、还是风格一致性？

## 建议的后续技能（关联）

- `vue-ci-checks`：在 CI 中运行 lint、test、build 并生成失败原因摘要。
- `vue-a11y-enforcer`：自动检测并建议 a11y 修复点。
- `vue-performance-tuner`：生成按路由的 bundle 分析报告并推荐拆分点。

---

保存位置建议：`.github/skills/vue-best-practices/SKILL.md`（workspace-scoped）

使用说明：将上述短触发语发送给助手即可激活本 Skill，或在触发时附加 `--apply-fixes` 与 `--scope=frontend` 等参数以控制行为。
