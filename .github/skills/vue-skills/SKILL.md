---
name: vue-skills
description: "Vue 动画与布局性能优化工作流（面向动画顺滑、主题切换与侧栏收缩场景）。"
argument-hint: "简述要优化的目标（组件/页面/场景）、关键症状、优先级、目标设备/浏览器。"
---

# Vue Animation & Layout Performance — SKILL

## 概述

该 Skill 汇总并复用了一套在 Vue 应用中定位、修复并验证动画与布局卡顿（jank）的工作流。目标是在限定的性能预算下，优先提升“感知流畅度”（perceived smoothness），同时避免引入布局回归或视觉错位。

## 作用域

- 默认：工作区范围（workspace-scoped），定位 `frontend/` 下的 Vue 前端。
- 可配置为仅针对单个组件或目录运行（component-scoped）。

## 适用场景

- 主题切换（dark/light 或主题色）存在明显卡顿或白屏闪烁。
- 侧栏（sidebar）收缩/展开触发页面回流导致帧率下降或头像错位。
- 流式渲染（chat streaming）导致频繁 DOM 写入、频繁重绘。
- 任何因 width/height/box-shadow/filter 等触发布局回流的 CSS 动画。

## 前提条件

- 能在本地运行 Production 构建（`npm run build` / `yarn build` / `pnpm build`）。
- 可访问 Chrome/Edge DevTools 用于性能分析，或能够运行 Lighthouse / Puppeteer 自动化测量（可选）。
- 推荐：项目使用 Vue 2 或 Vue 3（技能支持两者，分支策略见“决策点”）。

## 工作流（逐步）

1. 收集与复现
   - 记录症状、复现步骤、受影响的页面/组件、出现频率和设备/浏览器信息。
   - 拍摄短屏幕录制或保存 DevTools trace（Performance -> Record）。

2. 建立基线度量
   - 在 DevTools 收集 FPS / Main Thread 活动 / Layout/Paint/Composite 时间。
   - 用 Lighthouse 或自定义 Puppeteer 脚本收集 First Contentful Paint / Largest Contentful Paint / Cumulative Layout Shift（可选）。

3. 快速代码定位（静态搜索）
   - 在代码中搜索关键字：`transition`、`animation`、`box-shadow`、`filter`、`width:`、`height:`、`offsetWidth`、`clientWidth`。
   - 查找 JS 中频繁的 DOM 读写（getBoundingClientRect / offsetWidth / offsetHeight / scrollTop 等）。

4. 低成本修复尝试（优先）
   - 优先将会触发布局的属性（width/height/top/left/margin）改为 transform（translate/scale）与 opacity。
   - 对主题切换：优先使用 view-transition API（若可用）；否则使用短时全屏遮罩（overlay）做 cross-fade，避免瞬时重绘闪烁。
   - 限定 `will-change` 的使用范围（仅在动画短期生效的元素上），并尽量避免在大量子项上添加。
   - 使用 `contain: paint` / `contain: layout`（谨慎）隔离区域，减少回流传播。
   - 在动画中减少或移除 expensive filters/shadows（box-shadow, filter: blur 等）。

5. JS 层面限频与去抖（Debounce / Timebox）
   - 对切换类（sidebar collapse、theme toggle）引入短时标记（例如 `body.sidebar-animating`），并用定时器防止重复触发。
   - 对流式消息（streaming）采用批量追加或 requestAnimationFrame 批量更新，避免逐条 DOM 写入。

6. 小步实验与回滚策略
   - 每次改动保持原子性（单一文件/单一功能），并立即做生产构建验证。
   - 若改动导致布局回归（如绝对定位整体布局引起错位），立即回退并记录为“不可接受方案”。

7. 验证
   - 运行 Production build 并在构建产物中复现场景。
   - 使用 DevTools 再次采样 Performance trace，比较基线和改进后的指标（减少 Layout/Compositing 时间、提高 FPS）。
   - 手工检查关键断点（手机/桌面），并确认没有可见错位或功能缺失。

8. 文档化与提交
   - 在提交信息或 PR 描述中列出：问题、度量基线、变更点、验证方法、回退说明。
   - 若自动修复，附带 `.patch` 或说明如何本地复现与检验。

## 决策点与分支逻辑

- 主题切换：
  - 若浏览器支持 view-transition 且能稳定工作 → 使用 view-transition。
  - 否则 → 使用短时全屏遮罩 overlay（半透明或取自 `--bg-base`）做 cross-fade，随后切换 class。

- 侧栏布局：
  - 侧栏若为主布局的一部分且切换会引起大量重排 → 优先使用 transform（translateX）或 overlay 模式（仅在移动端或特定断点）。
  - 若尝试将侧栏做绝对浮层导致整体错位或样式破坏 → 回滚到流式布局并尝试 narrower will-change + contain 调整。

- 自动化 vs 建议：
  - 风险较低的自动修复（ESLint --fix、简单的 CSS 替换）可选自动应用。
  - 结构性/布局性变更（如把布局从 flow 改成 absolute）应只给出建议与手动 PR。

## 质量准则 / 完成检查

- `npm run build` 成功（exit code 0）。
- 关键交互（主题切换、侧栏收缩、流式渲染）在目标设备上无明显卡顿（感知上至少平滑许多）。
- DevTools Performance trace 显示 Layout / Paint 时间下降或 Main-thread 空闲时间增加。
- 没有引入新的可见布局错位或功能回归（手工检查通过）。

## 模板代码片段（示例）

- `runAppearanceTransition`（store 内 helper，示例）

```js
// store/helpers.js (示例)
export function runAppearanceTransition(
  updateFn,
  overlayColor = "rgba(255,255,255,0.9)",
) {
  const overlay = document.createElement("div");
  overlay.className = "theme-transition-overlay";
  overlay.style.background = overlayColor;
  document.body.appendChild(overlay);
  requestAnimationFrame(() => {
    overlay.classList.add("visible");
    setTimeout(() => {
      try {
        updateFn();
      } catch (e) {
        console.error(e);
      }
      overlay.classList.remove("visible");
      setTimeout(() => document.body.removeChild(overlay), 300);
    }, 80);
  });
}
```

- CSS overlay（示例，放入全局样式）

```css
.theme-transition-overlay {
  position: fixed;
  inset: 0;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.25s linear;
  z-index: 99999;
}
.theme-transition-overlay.visible {
  opacity: 1;
}
```

## 示例触发提示（Prompts）

- "优化 `frontend/src/components/Sidebar.vue` 的收缩动画，首选 transform 替代 width。"
- "为主题切换生成 `runAppearanceTransition` helper 并在 `frontend/src/store/index.js` 中集成。"
- "扫描 `frontend/src` 中所有使用 `transition` 的规则并列出会触发布局回流的那些。"

## 默认决策（已应用）

- **作用域**：默认以工作区 `frontend/` 为目标；支持可选参数 `--scope=component` 指定单文件或子目录以缩小检查范围。
- **自动化等级**：默认允许自动应用低风险修复（`apply-fixes`）。技能将在临时分支上自动运行并应用低风险修复（例如 `eslint --fix`、格式化修正），同时生成补丁与变更摘要供审阅。结构性或会改变布局流的重大改动仍然不会被自动应用，需显式批准或由用户手动创建 PR。
- **基线测量**：默认不自动运行 Lighthouse 或 Puppeteer（已禁用）。如果需要基线测量，技能会提供可选的示例脚本与说明，用户可手动运行并上载结果用于报告。
- **Vue 版本支持**：优先兼容 Vue 3（Composition API）；若检测到 Vue 2，技能将采用对应建议路径并在报告中标注迁移建议与风险。

## 相关扩展技能建议

- `vue-highperf-toggle`：生成并统一管理 `highPerf` 或 `reduced-motion` 开关，自动注入到 store。
- `vue-perf-ci`：在 CI 中运行 Lighthouse 报告并在 PR 中注入性能回归检测。

---

保存位置建议：`.github/skills/vue-skills/SKILL.md`（workspace-scoped）

使用说明：向助手发送上面示例提示之一并附带 `--apply-fixes=false`（默认）或 `--apply-fixes=true`（会申请修改）即可触发本 Skill。
