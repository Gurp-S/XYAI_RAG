<template>
  <div class="dashboard">
    <div class="dashboard__header">
      <h2 class="dashboard__title">仪表盘</h2>
      <p class="dashboard__subtitle">系统运行概览</p>
    </div>

    <!-- Stats Cards -->
    <div class="stats-grid">
      <div
        v-for="(card, idx) in statsCards"
        :key="card.label"
        class="stat-card"
        :style="{ animationDelay: `${idx * 0.1}s` }"
      >
        <div class="stat-card__icon" :style="{ background: card.bg }">
          <el-icon :size="22" :color="card.color">
            <component :is="card.icon" />
          </el-icon>
        </div>
        <div class="stat-card__info">
          <span class="stat-card__label">{{ card.label }}</span>
          <span class="stat-card__value">{{ card.value }}</span>
        </div>
      </div>
    </div>

    <!-- RAG Properties (compact) -->
    <el-card class="rag-section" shadow="never">
      <div class="rag-section__header" @click="ragExpanded = !ragExpanded">
        <span class="rag-section__title">
          <el-icon><SetUp /></el-icon> RAG 属性配置
        </span>
        <el-icon :class="{ 'is-expanded': ragExpanded }"><ArrowDown /></el-icon>
      </div>
      <el-collapse-transition>
        <div v-show="ragExpanded">
          <RagProperties />
        </div>
      </el-collapse-transition>
    </el-card>

    <!-- Charts -->
    <div class="charts-grid">
      <el-card class="chart-card">
        <template #header>
          <div class="chart-card__header">
            <span>Token 使用趋势</span>
            <el-select v-model="trendDays" size="small" style="width: 120px" @change="loadChartData">
              <el-option :value="7" label="近 7 天" />
              <el-option :value="14" label="近 14 天" />
              <el-option :value="30" label="近 30 天" />
            </el-select>
          </div>
        </template>
        <v-chart :option="trendOption" autoresize style="height: 320px" />
      </el-card>

      <el-card class="chart-card">
        <template #header>
          <div class="chart-card__header">
            <span>文件使用趋势</span>
            <el-select v-model="fileDays" size="small" style="width: 120px" @change="loadFileChartData">
              <el-option :value="7" label="近 7 天" />
              <el-option :value="14" label="近 14 天" />
              <el-option :value="30" label="近 30 天" />
            </el-select>
          </div>
        </template>
        <v-chart :option="fileChartOption" autoresize style="height: 320px" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, shallowRef } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import * as dashboardApi from '../api/dashboard'
import { formatNumber, formatDuration } from '../utils/format'

function formatAvgTime(ms) {
  return formatDuration(ms)
}

function formatNumberLabel(v) {
  if (v >= 1000000) return (v / 1000000).toFixed(1) + 'm'
  if (v >= 1000) return (v / 1000).toFixed(1) + 'k'
  return v
}
import RagProperties from './rag/PropertiesManager.vue'

use([CanvasRenderer, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent])

const trendDays = ref(7)
const fileDays = ref(7)
const ragExpanded = ref(true)

const stats = reactive({
  userCount: 0, traceCount: 0, tokenCount: 0,
  evaluateCount: 0, avgCostTime: 0, milvusCollections: 0,
  fileCount: 0, onlineUserCount: 0, defaultModel: '-', latestAnnouncement: '-'
})

const statsCards = computed(() => [
  { label: '用户数', value: formatNumber(stats.userCount), icon: 'User', color: '#5B7FA5', bg: '#E8EFF6' },
  { label: 'Token 消耗', value: formatNumber(stats.tokenCount), icon: 'Coin', color: '#6B8F7A', bg: '#E8F3EC' },
  { label: '评估次数', value: formatNumber(stats.traceCount), icon: 'TrendCharts', color: '#C89B6B', bg: '#F5EDE3' },
  { label: '在线用户', value: formatNumber(stats.onlineUserCount), icon: 'Monitor', color: '#5B7FA5', bg: '#E8EFF6' },
  { label: '文件数', value: formatNumber(stats.fileCount), icon: 'Document', color: '#8E93A6', bg: '#F0F1F4' },
  { label: '集合数', value: formatNumber(stats.milvusCollections), icon: 'FolderOpened', color: '#6B8F7A', bg: '#E8F3EC' },
  { label: '平均耗时', value: formatAvgTime(stats.avgCostTime), icon: 'Timer', color: '#B57C7C', bg: '#F2E6E6' },
  { label: '默认模型', value: stats.defaultModel || '-', icon: 'Cpu', color: '#C89B6B', bg: '#F5EDE3' }
])

const trendOption = shallowRef({})
const fileChartOption = shallowRef({})

function buildLineOption(dates, values) {
  return {
    tooltip: { trigger: 'axis', backgroundColor: 'var(--bg-card)', borderColor: 'var(--border-color)', borderWidth: 1, textStyle: { color: 'var(--text-regular)', fontSize: 12 } },
    grid: { left: 50, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: dates, axisLine: { lineStyle: { color: 'var(--border-color)' } }, axisLabel: { color: 'var(--text-secondary)', fontSize: 11 } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: 'var(--border-light)', type: 'dashed' } }, axisLabel: { color: 'var(--text-secondary)', fontSize: 11, formatter: (v) => formatNumberLabel(v) } },
    series: [{
      type: 'line', smooth: true, data: values,
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(91, 127, 165, 0.25)' }, { offset: 1, color: 'rgba(91, 127, 165, 0.02)' }] } },
      lineStyle: { color: '#5B7FA5', width: 2 },
      itemStyle: { color: '#5B7FA5' },
      symbol: 'circle', symbolSize: 6
    }]
  }
}

function buildBarOption(dates, values) {
  return {
    tooltip: { trigger: 'axis', backgroundColor: 'var(--bg-card)', borderColor: 'var(--border-color)', borderWidth: 1, textStyle: { color: 'var(--text-regular)', fontSize: 12 } },
    grid: { left: 50, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: dates, axisLine: { lineStyle: { color: 'var(--border-color)' } }, axisLabel: { color: 'var(--text-secondary)', fontSize: 11 } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: 'var(--border-light)', type: 'dashed' } }, axisLabel: { color: 'var(--text-secondary)', fontSize: 11, formatter: (v) => formatNumberLabel(v) } },
    series: [{
      type: 'bar', data: values, barWidth: '40%',
      itemStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#7B9BBF' }, { offset: 1, color: '#5B7FA5' }] }, borderRadius: [4, 4, 0, 0] }
    }]
  }
}

async function loadStats() {
  try {
    const data = await dashboardApi.getStats()
    Object.assign(stats, data)
  } catch (e) { /* stats default */ }
}

async function loadChartData() {
  try {
    const data = await dashboardApi.getChartData(trendDays.value)
    const dates = data.map(d => d.date)
    const values = data.map(d => d.tokens || d.count || 0)
    trendOption.value = buildLineOption(dates, values)
  } catch (e) { /* empty */ }
}

async function loadFileChartData() {
  try {
    const data = await dashboardApi.getFileChartData(fileDays.value)
    const dates = data.map(d => d.date)
    const values = data.map(d => d.fileCount || 0)
    fileChartOption.value = buildBarOption(dates, values)
  } catch (e) { /* empty */ }
}

onMounted(() => {
  loadStats()
  loadChartData()
  loadFileChartData()
})
</script>

<style scoped>
.dashboard__header {
  margin-bottom: 24px;
}
.dashboard__title {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.dashboard__subtitle {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 4px 0 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.3s ease, transform 0.3s ease;
  animation: cardIn 0.5s ease both;
}
.stat-card:hover {
  box-shadow: var(--shadow-hover);
  transform: translateY(-2px);
}

@keyframes cardIn {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}

.stat-card__icon {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-card__info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-card__label {
  font-size: 12px;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.stat-card__value {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.2;
}

/* RAG Section */
.rag-section {
  margin-bottom: 16px;
}
.rag-section__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
}
.rag-section__header:hover {
  color: var(--primary);
}
.rag-section__title {
  display: flex;
  align-items: center;
  gap: 6px;
}
.rag-section__header .el-icon.is-expanded {
  transform: rotate(180deg);
  transition: transform 0.3s ease;
}
.rag-section__header .el-icon:not(.is-expanded) {
  transition: transform 0.3s ease;
}

/* Charts */
.charts-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 1200px) {
  .charts-grid { grid-template-columns: 1fr; }
}

.chart-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}
</style>
