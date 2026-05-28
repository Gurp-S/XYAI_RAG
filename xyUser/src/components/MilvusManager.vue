<template>
  <div class="db-view">
    <!-- 顶部状态栏 -->
    <header class="app-glass-header">
      <div class="header-content">
        <div class="title-group">
          <button
            class="icon-pulse sidebar-entry-btn"
            type="button"
            @click="toggleSidebar"
          >
            <svg
              v-if="ui.isSidebarCollapsed"
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="8" y1="12" x2="16" y2="12"></line>
              <line x1="12" y1="8" x2="12" y2="16"></line>
            </svg>
            <svg
              v-else
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2.1"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <line x1="4" y1="7" x2="20" y2="7"></line>
              <line x1="4" y1="12" x2="20" y2="12"></line>
              <line x1="4" y1="17" x2="20" y2="17"></line>
            </svg>
          </button>
          <h2>向量数据库管理</h2>
        </div>

        <div ref="searchDropdownRef" class="control-group">
          <!-- 模糊搜索与选择组合框 -->
          <div class="search-select-wrapper">
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              class="search-icon"
            >
              <circle cx="11" cy="11" r="8"></circle>
              <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
            <input
              v-model="searchQuery"
              type="text"
              class="glass-input"
              placeholder="搜索或选择集合..."
              @input="onSearchInput"
              @focus="handleFocus"
            />
            <button
              v-if="searchQuery"
              class="clear-search-btn"
              @click.stop="clearSearch"
            >
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
            <div
              class="arrow-down"
              :class="{ open: isDropdownOpen }"
              @click.stop="toggleDropdown"
            >
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
              >
                <polyline points="6 9 12 15 18 9"></polyline>
              </svg>
            </div>

            <transition name="fade-down">
              <ul v-if="isDropdownOpen" class="glass-dropdown">
                <li v-if="collections.length === 0" class="dropdown-item empty">
                  未找到集合
                </li>
                <li
                  v-for="name in collections"
                  :key="name"
                  class="dropdown-item"
                  :class="{ active: selectedCollection === name }"
                  @click="selectCollection(name)"
                >
                  <span class="collection-name">{{ name }}</span>
                  <div class="dropdown-badges">
                    <span v-if="selectedCollection === name" class="check-icon">
                      <svg
                        width="14"
                        height="14"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                      >
                        <polyline points="20 6 9 17 4 12"></polyline>
                      </svg>
                    </span>
                  </div>
                </li>
              </ul>
            </transition>
          </div>

          <button
            class="glass-btn icon-btn"
            :class="{ spinning: loading }"
            @click="fetchCollections(true)"
          >
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
            >
              <path d="M23 4v6h-6"></path>
              <path d="M1 20v-6h6"></path>
              <path
                d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"
              ></path>
            </svg>
          </button>

          <!-- 重新摆放的“新建合集”按钮，仅保留加号 -->
          <button
            class="glass-btn icon-btn create-btn-top"
            @click="openCreateModal"
          >
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
            >
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
          </button>
        </div>
      </div>
    </header>

    <!-- 主展示区 -->
    <main class="db-workspace">
      <div class="glass-panel">
        <div class="panel-header">
          <div v-if="selectedCollection" class="dataset-stats">
            <span class="stat-badge">
              <span
                class="dot"
                :style="{
                  backgroundColor: isCollectionLoaded ? '#22c55e' : '#ef4444',
                  boxShadow: isCollectionLoaded
                    ? '0 0 8px #22c55e'
                    : '0 0 8px #ef4444',
                }"
              ></span>
              当前合集：<strong class="highlight-text">{{
                selectedCollection
              }}</strong>
            </span>
            <span v-if="isCollectionLoaded" class="stat-badge outline">
              有效文档：<strong class="highlight-text">{{
                collectionFileTotal
              }}</strong>
            </span>
            <span
              v-else
              class="stat-badge outline"
              style="color: #ef4444; border-color: rgba(239, 68, 68, 0.3)"
            >
              集合未加载
            </span>
          </div>
          <div v-else class="dataset-stats">
            <span
              class="stat-badge outline"
              style="border-color: var(--text-muted); color: var(--text-muted)"
            >
              未选择合集
            </span>
          </div>

          <div class="action-group">
            <template v-if="selectedCollection">
              <!-- 把新增改成上传文档到当前集合 -->
              <button
                class="action-btn upload-btn"
                :disabled="ui.isUploading"
                :style="{
                  opacity: ui.isUploading ? 0.8 : 1,
                  cursor: ui.isUploading ? 'not-allowed' : 'pointer',
                }"
                @click="handleUpload"
              >
                <svg
                  v-if="!ui.isUploading"
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  stroke-linecap="round"
                >
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                  <polyline points="17 8 12 3 7 8"></polyline>
                  <line x1="12" y1="3" x2="12" y2="15"></line>
                </svg>

                <svg
                  v-else
                  class="spin-icon"
                  style="animation: spin 1s linear infinite"
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  stroke-linecap="round"
                >
                  <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
                </svg>

                <span v-if="!ui.isUploading">上传库</span>
                <span v-else>{{ ui.uploadStatusText }}</span>
              </button>

              <!-- 加载/卸载按钮 -->
              <button
                class="action-btn"
                :class="isCollectionLoaded ? 'warning-btn' : 'success-btn'"
                :disabled="loadStateActionLoading"
                @click="toggleLoadState"
              >
                <svg
                  v-if="!loadStateActionLoading"
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  stroke-linecap="round"
                >
                  <path
                    d="M21 12V7a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h7"
                  ></path>
                  <line x1="16" y1="5" x2="16" y2="19"></line>
                  <circle
                    v-if="isCollectionLoaded"
                    cx="18"
                    cy="18"
                    r="3"
                  ></circle>
                  <path v-else d="M14 18l2 2 4-4"></path>
                </svg>
                <svg
                  v-else
                  class="spin-icon"
                  style="animation: spin 1s linear infinite"
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  stroke-linecap="round"
                >
                  <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
                </svg>
                <span>{{ isCollectionLoaded ? "卸载" : "加载" }}</span>
              </button>

              <button class="action-btn warning-btn" @click="rebuildCurrent">
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  stroke-linecap="round"
                >
                  <path
                    d="M21.5 2v6h-6M2.5 22v-6h6M2 11.5a10 10 0 0 1 18.8-4.3M22 12.5a10 10 0 0 1-18.8 4.2"
                  />
                </svg>
                <span>重建</span>
              </button>
              <button class="action-btn danger-btn" @click="dropCurrent">
                <svg
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  stroke-linecap="round"
                >
                  <polyline points="3 6 5 6 21 6"></polyline>
                  <path
                    d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
                  ></path>
                </svg>
                <span>删除</span>
              </button>
            </template>
          </div>
        </div>

        <div class="table-scroll-area">
          <table class="hero-table">
            <thead>
              <tr>
                <th class="th-id">Doc ID</th>
                <th class="th-content">内容块</th>
                <th class="th-meta">扩展信息</th>
                <th class="th-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              <!-- 空状态/加载态 -->
              <tr v-if="loading && metadataList.length === 0">
                <td colspan="4" class="state-cell">
                  <div class="loading-fx">
                    <div class="magic-ring"></div>
                    <p class="animate-pulse">正在读取向量空间...</p>
                    <span class="text-xs text-slate-500"
                      >正在与 Milvus 建立同步连接</span
                    >
                  </div>
                </td>
              </tr>
              <tr v-else-if="isCollectionLoaded && metadataList.length === 0">
                <td colspan="4" class="state-cell">
                  <div class="empty-fx">
                    <div class="ambient-icon">
                      <svg
                        width="64"
                        height="64"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="1.5"
                        class="text-slate-600"
                      >
                        <path
                          d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"
                        ></path>
                        <polyline points="14 2 14 8 20 8"></polyline>
                        <line x1="12" y1="18" x2="12" y2="12"></line>
                        <line x1="9" y1="15" x2="15" y2="15"></line>
                      </svg>
                    </div>
                    <h3>知识库空空如也</h3>
                    <p>
                      集合已就绪，点击右上角“上传库”开始构建您的本地向量知识
                    </p>
                    <button
                      class="state-action-btn"
                      :disabled="ui.isUploading"
                      @click="handleUpload"
                    >
                      立即上传文档
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-else-if="!isCollectionLoaded">
                <td colspan="4" class="state-cell">
                  <div class="empty-fx">
                    <div class="ambient-icon">
                      <svg
                        width="64"
                        height="64"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="1.5"
                      >
                        <path
                          d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"
                        ></path>
                        <polyline
                          points="3.27 6.96 12 12.01 20.73 6.96"
                        ></polyline>
                        <line x1="12" y1="22.08" x2="12" y2="12"></line>
                      </svg>
                    </div>
                    <h3>集合未加载</h3>
                    <p>点击“加载”按钮以查看数据和执行检索</p>
                    <button
                      class="state-action-btn"
                      :disabled="loadStateActionLoading || !selectedCollection"
                      @click="toggleLoadState"
                    >
                      {{ loadStateActionLoading ? "处理中..." : "立即加载" }}
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-else-if="!selectedCollection">
                <td colspan="4" class="state-cell">
                  <div class="empty-fx">
                    <div class="ambient-icon">
                      <svg
                        width="64"
                        height="64"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="1.5"
                      >
                        <path
                          d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"
                        ></path>
                        <polyline
                          points="3.27 6.96 12 12.01 20.73 6.96"
                        ></polyline>
                        <line x1="12" y1="22.08" x2="12" y2="12"></line>
                      </svg>
                    </div>
                    <h3>请选定向量集合</h3>
                    <p>在此处查看和管理深度检索文档</p>
                    <button class="state-action-btn" @click="openCreateModal">
                      新建集合
                    </button>
                  </div>
                </td>
              </tr>

              <!-- 真实数据 -->
              <tr
                v-for="item in visibleMetadataList"
                :key="item.__rowKey"
                v-memo="[item.__rowKey, expandedRows.has(item.__rowKey)]"
                class="hero-row"
              >
                <td class="td-id">
                  <div class="hash-tag">
                    {{ formatDocIdDisplay(item.__docInfo, item.__meta) }}
                  </div>
                </td>
                <td
                  class="td-content"
                  :class="{ expanded: expandedRows.has(item.__rowKey) }"
                >
                  <div
                    class="text-block"
                    v-html="expandedRows.has(item.__rowKey) ? renderContent(item.__fullContent) : escapeHtmlText(item.__contentPreview)"
                  ></div>
                  <button
                    class="expand-btn"
                    @click="toggleExpand(item.__rowKey)"
                  >
                    <svg
                      width="14"
                      height="14"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2.5"
                      stroke-linecap="round"
                    >
                      <polyline points="9 18 15 12 9 6"></polyline>
                    </svg>
                  </button>
                </td>
                <td class="td-meta">
                  <div class="tag-group">
                    <div
                      v-for="(val, key) in item.__meta"
                      :key="key"
                      class="glass-tag"
                    >
                      <span class="tag-label">{{ key }}</span>
                      <span class="tag-value">{{ val }}</span>
                    </div>
                  </div>
                </td>
                <td class="td-actions">
                  <button
                    class="action-btn-delete"
                    aria-label="删除当前文档分块"
                    @click="handleDeleteDoc(item)"
                  >
                    <svg
                      width="18"
                      height="18"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    >
                      <polyline points="3 6 5 6 21 6"></polyline>
                      <path
                        d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
                      ></path>
                      <line x1="10" y1="11" x2="10" y2="17"></line>
                      <line x1="14" y1="11" x2="14" y2="17"></line>
                    </svg>
                  </button>
                </td>
              </tr>
              <tr v-if="hasMoreMetadataRows" class="load-more-row" ref="sentinelEl">
                <td colspan="4" class="state-cell">
                  <div class="loading-fx" style="padding: 16px 0;">
                    <div class="magic-ring" style="width: 24px; height: 24px; border-width: 2px;"></div>
                    <span style="font-size: 12px;">滚动加载更多...</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </main>

    <!-- 新增合集弹窗 -->
    <transition name="fade-scale">
      <div
        v-if="showCreateModal"
        class="modal-backdrop"
        @click="closeCreateModal"
      >
        <div class="modal-card" @click.stop>
          <div class="modal-header">
            <h3>创建向量集合</h3>
            <button class="close-btn" @click="closeCreateModal">&times;</button>
          </div>
          <div class="modal-body">
            <p class="modal-desc">
              请输入新集合的名称，仅支持字母、数字和下划线且不可重名。
            </p>
            <input
              v-model="newCollectionName"
              type="text"
              class="glass-input create-input"
              placeholder="例如: tech_docs_2025"
              autofocus
              @keyup.enter="confirmCreate"
            />
            <p v-if="createError" class="error-msg">{{ createError }}</p>
          </div>
          <div class="modal-footer">
            <button class="glass-btn cancel-btn" @click="closeCreateModal">
              取消
            </button>
            <button
              class="glass-btn confirm-btn"
              :disabled="creating"
              @click="confirmCreate"
            >
              {{ creating ? "创建中..." : "确认创建" }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 操作确认二次验证弹窗 -->
    <transition name="fade-scale">
      <div
        v-if="showConfirmModal"
        class="modal-backdrop"
        @click="closeConfirmModal"
      >
        <div class="modal-card" @click.stop>
          <div class="modal-header">
            <h3 v-if="confirmType === 'delete'">删除集合</h3>
            <h3 v-else-if="confirmType === 'delete-doc'">确认删除文档分块</h3>
            <h3 v-else>重建索引</h3>
            <button class="close-btn" @click="closeConfirmModal">
              &times;
            </button>
          </div>
          <div class="modal-body">
            <template v-if="confirmType === 'delete'">
              <p class="modal-desc">
                你正在试图删除集合 <strong>{{ selectedCollection }}</strong
                >。此操作无法撤销。<br />
                输入 <strong>删除</strong> 以确认。
              </p>
            </template>
            <template v-else-if="confirmType === 'delete-doc'">
              <p class="modal-desc">
                你正在试图从集合
                <strong>{{ selectedCollection }}</strong> 中删除：<br />
                文件：<strong>{{ pendingDeleteDoc?.meta?.fileName }}</strong
                ><br />
                此操作将永久移除该数据。<br />
                输入 <strong>确认删除</strong> 以确认。
              </p>
            </template>
            <template v-else>
              <p class="modal-desc">
                你正在试图重建集合
                <strong>{{ selectedCollection }}</strong>
                的索引。重建期间可能造成短时检索停顿。<br />
                输入 <strong>重建</strong> 以确认。
              </p>
            </template>
            <input
              v-model="confirmInput"
              type="text"
              class="glass-input create-input"
              :placeholder="getConfirmPlaceholder()"
              autofocus
              @keyup.enter="executeConfirm"
            />
          </div>
          <div class="modal-footer">
            <button class="glass-btn cancel-btn" @click="closeConfirmModal">
              取消
            </button>
            <button
              class="glass-btn confirm-btn action-execute-btn"
              :class="
                confirmType === 'delete' || confirmType === 'delete-doc'
                  ? 'danger-fill-btn'
                  : 'warning-fill-btn'
              "
              :disabled="!canConfirm || isConfirming"
              @click="executeConfirm"
            >
              {{ isConfirming ? "执行中..." : getConfirmButtonText() }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 分享文件弹窗（已移除，该功能移至聊天界面加号菜单） -->
  </div>
</template>

<script setup>
/* eslint-disable no-console -- error reporting requires console */
import { ref, shallowRef, onMounted, watch, onUnmounted, computed, reactive, nextTick } from "vue";
import { useRoute } from "vue-router";
import { useUiStore } from "../store/index";
import {
  createCollection as apiCreateCollection,
  deleteCollection as apiDeleteCollection,
  deleteDocument as apiDeleteDocument,
  getCollectionMetadata,
  getCollectionFileNumber,
  getCollectionStatus,
  listCollections,
  loadOrUnloadCollection as apiLoadOrUnloadCollection,
  rebuildCollection as apiRebuildCollection,
  searchCollections,
} from "../services/milvusApi";
import { renderAssistantMarkdown } from "../services/markdown";
import { useToast } from "../composables/useToast.js";

const toast = useToast();

const ui = useUiStore();
const route = useRoute();
const collections = ref([]);
const selectedCollection = ref("");
const metadataList = shallowRef([]);
const loading = ref(false);
const searchQuery = ref("");
const originalCollections = ref([]); // 用于清空搜索时恢复列表

const showCreateModal = ref(false);
const newCollectionName = ref("");
const creating = ref(false);
const createError = ref("");

// 新增：集合加载状态
const isCollectionLoaded = ref(false);
const loadStateActionLoading = ref(false);

// 新增：操作确认弹窗状态
const showConfirmModal = ref(false);
const confirmType = ref(""); // 'rebuild' | 'delete'
const confirmInput = ref("");
const isConfirming = ref(false);
const pendingDeleteDoc = ref(null);
const collectionsCache = ref([]);
const collectionStatusCache = new Map();
const metadataCache = new Map();
const searchResultCache = new Map();
const lastSearchQuery = ref("");
const lastCollectionsFetchAt = ref(0);
const COLLECTIONS_CACHE_TTL = 60000;
const METADATA_PAGE_SIZE = 30;
const MAX_CONTENT_PREVIEW_LENGTH = 320;
const visibleRowCount = ref(METADATA_PAGE_SIZE);
const metadataLoading = ref(false);
const collectionFileTotal = ref(0);
let metadataAbortController = null;

const canConfirm = computed(() => {
  if (confirmType.value === "delete")
    return confirmInput.value.trim() === "删除";
  if (confirmType.value === "rebuild")
    return confirmInput.value.trim() === "重建";
  if (confirmType.value === "delete-doc")
    return confirmInput.value.trim() === "确认删除";
  return false;
});

const visibleMetadataList = computed(() =>
  metadataList.value.slice(0, visibleRowCount.value),
);

const hasMoreMetadataRows = computed(() => {
  const cursorData = pageCursor.get(selectedCollection.value);
  return cursorData ? cursorData.hasMore : false;
});

const expandedRows = reactive(new Set());

function toggleExpand(rowKey) {
  if (expandedRows.has(rowKey)) {
    expandedRows.delete(rowKey);
  } else {
    expandedRows.add(rowKey);
  }
}

function getConfirmPlaceholder() {
  if (confirmType.value === "delete") return "输入 删除 确认";
  if (confirmType.value === "rebuild") return "输入 重建 确认";
  if (confirmType.value === "delete-doc") return "输入 确认删除";
  return "";
}

function getConfirmButtonText() {
  if (confirmType.value === "delete") return "确认删除集合";
  if (confirmType.value === "rebuild") return "确认重建索引";
  if (confirmType.value === "delete-doc") return "确认删除文档";
  return "确认执行";
}

const isDropdownOpen = ref(false);
const searchDropdownRef = ref(null);
const isDbRoute = computed(() => route.path.startsWith("/db"));

// 监听上传状态，上传完毕后自动刷新当前合集数据
watch(
  () => ui.isUploading,
  (newVal, oldVal) => {
    if (oldVal === true && newVal === false && selectedCollection.value) {
      fetchMetadata(true);
    }
  },
);

const sentinelEl = ref(null);
let sentinelObserver = null;

function setupSentinelObserver() {
  if (sentinelObserver) sentinelObserver.disconnect();
  if (!sentinelEl.value) return;
  sentinelObserver = new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting) {
      if (hasMoreMetadataRows.value && !loading.value) {
        fetchNextPage();
      }
    }
  }, { rootMargin: "200px" });
  sentinelObserver.observe(sentinelEl.value);
}

onMounted(() => {
  if (isDbRoute.value) {
    fetchCollections();
  }
  document.addEventListener("click", handleClickOutside);
});
onUnmounted(() => {
  if (sentinelObserver) sentinelObserver.disconnect();
  document.removeEventListener("click", handleClickOutside);
  if (metadataAbortController) {
    metadataAbortController.abort();
    metadataAbortController = null;
  }
  if (searchAbort.value) {
    searchAbort.value.abort();
    searchAbort.value = null;
  }
  if (searchTimeout) {
    clearTimeout(searchTimeout);
    searchTimeout = null;
  }
});

function handleClickOutside(e) {
  if (searchDropdownRef.value && !searchDropdownRef.value.contains(e.target)) {
    isDropdownOpen.value = false;
    // 移除这里：点击外部时不应该把选中的集合名强制塞回搜索框（如果它被清空了）
    // if (selectedCollection.value && !searchQuery.value) {
    //   searchQuery.value = selectedCollection.value
    // }
  }
}

function handleFocus() {
  isDropdownOpen.value = true;
  if (selectedCollection.value === searchQuery.value) {
    searchQuery.value = "";
  }
  handleSearch();
}

function toggleDropdown() {
  if (isDropdownOpen.value) {
    isDropdownOpen.value = false;
  } else {
    handleFocus();
  }
}

function toggleSidebar() {
  ui.toggleSidebar();
}

let searchTimeout = null;
function onSearchInput() {
  isDropdownOpen.value = true;
  if (searchTimeout) clearTimeout(searchTimeout);
  searchTimeout = setTimeout(() => {
    handleSearch();
  }, 300);
}

async function selectCollection(name, options = {}) {
  const { forceRefresh = true } = options;
  selectedCollection.value = name;
  searchQuery.value = "";
  isDropdownOpen.value = false;
  visibleRowCount.value = 0;

  // 先查询状态，状态查询内部会决定是否查询元数据
  await fetchCollectionStatus(name, { forceRefresh });
}

async function fetchCollectionStatus(name, options = {}) {
  const { forceRefresh = false } = options;
  if (!name) return;
  if (!forceRefresh && collectionStatusCache.has(name)) {
    const cachedStatus = collectionStatusCache.get(name);
    isCollectionLoaded.value = !!cachedStatus.loaded;
    if (isCollectionLoaded.value) {
      if (metadataCache.has(name)) {
        metadataList.value = [...metadataCache.get(name)];
        visibleRowCount.value = metadataList.value.length;
      } else {
        await fetchMetadata(true);
      }
    } else if (!isCollectionLoaded.value) {
      metadataList.value = [];
    }
    return;
  }
  try {
    const status = await getCollectionStatus(name);
    isCollectionLoaded.value = status === true || status === "true";
    collectionStatusCache.set(name, { loaded: isCollectionLoaded.value });

    // 如果已加载，则查询元数据；如果未加载，清空列表
    if (isCollectionLoaded.value) {
      if (forceRefresh || !metadataCache.has(name)) {
        await fetchMetadata(true);
      } else {
        metadataList.value = [...metadataCache.get(name)];
        visibleRowCount.value = metadataList.value.length;
      }
    } else {
      metadataList.value = [];
    }
  } catch (err) {
    console.error("获取集合状态失败:", err);
  }
}

async function toggleLoadState() {
  if (!selectedCollection.value || loadStateActionLoading.value) return;

  loadStateActionLoading.value = true;
  try {
    await apiLoadOrUnloadCollection(selectedCollection.value);
    await fetchCollectionStatus(selectedCollection.value, {
      forceRefresh: true,
    });
    // 处理完加载/卸载后，如果是卸载操作，按钮就不存在了（因为按钮是根据 isCollectionLoaded 切换的），但用户要求“使用后就关闭”
    // 这里如果指的是弹窗或者某种状态，由于 load/unload 是直接点击按钮，我们保持现状。
    // 但对于 rebuild 和 delete，我们在 executeConfirm 中处理。
  } catch (err) {
    console.error("切换加载状态失败:", err);
  } finally {
    loadStateActionLoading.value = false;
  }
}

function handleUpload() {
  ui.openModal("upload");
  // 简易黑科技：如果 ModalManager中绑定的是独立的，可以借助全局 localStorage/pinia 状态 或者通过给 UI 库增加暂存字段传递
  // 这里为顺畅体验，我们在打开时存一个临时标，因为上传是需要集合名
  if (selectedCollection.value) {
    ui.uploadTargetCollection = selectedCollection.value;
  }
}

async function fetchCollections(forceRefresh = false) {
  if (loading.value) return; // 防止并发刷新
  const hasCachedCollections = collectionsCache.value.length > 0;
  const cacheIsFresh =
    Date.now() - lastCollectionsFetchAt.value < COLLECTIONS_CACHE_TTL;
  if (!forceRefresh && hasCachedCollections && cacheIsFresh) {
    collections.value = [...collectionsCache.value];
    originalCollections.value = [...collectionsCache.value];
    return;
  }
  loading.value = true;
  try {
    const nextCollections = await listCollections();
    const arr = Array.isArray(nextCollections) ? nextCollections : [];
    collections.value = arr;
    originalCollections.value = [...arr]; // 备份全量列表
    collectionsCache.value = [...arr];
    lastCollectionsFetchAt.value = Date.now();
    if (arr.length > 0 && !selectedCollection.value) {
      selectCollection(arr[0]);
    }
  } catch (err) {
    console.error("获取集合列表失败:", err);
  } finally {
    loading.value = false;
  }
}

const searchAbort = ref(null)

async function handleSearch() {
  const query = searchQuery.value.trim();
  if (!query) {
    collections.value = [...originalCollections.value];
    lastSearchQuery.value = "";
    return;
  }
  if (query === lastSearchQuery.value && searchResultCache.has(query)) {
    collections.value = [...searchResultCache.get(query)];
    return;
  }
  // 取消上一次搜索请求
  if (searchAbort.value) searchAbort.value.abort();
  const controller = new AbortController();
  searchAbort.value = controller;
  try {
    const nextCollections = await searchCollections(query, controller.signal);
    collections.value = Array.isArray(nextCollections) ? nextCollections : [];
    searchResultCache.set(query, [...collections.value]);
    lastSearchQuery.value = query;
  } catch (err) {
    if (err.name !== 'AbortError') console.error('[MilvusManager] 搜索失败:', err);
  }
}

function clearSearch() {
  searchQuery.value = "";
  collections.value = [...originalCollections.value];
  isDropdownOpen.value = true;
  if (
    collections.value.length > 0 &&
    !collections.value.includes(selectedCollection.value)
  ) {
    selectedCollection.value = "";
    metadataList.value = [];
  }
}

const pageCursor = reactive(new Map());

function loadMoreMetadataRows() {
  const cursorData = pageCursor.get(selectedCollection.value);
  if (!cursorData || !cursorData.hasMore) return;
  fetchNextPage();
}

async function fetchMetadata(forceRefresh = false) {
  if (!selectedCollection.value) return;
  if (metadataLoading.value) {
    if (!forceRefresh) return;
    if (metadataAbortController) {
      metadataAbortController.abort();
      metadataAbortController = null;
    }
    metadataLoading.value = false;
    loading.value = false;
  }
  if (!forceRefresh && metadataCache.has(selectedCollection.value)) {
    metadataList.value = [...metadataCache.get(selectedCollection.value)];
    visibleRowCount.value = metadataList.value.length;
    return;
  }
  if (forceRefresh) {
    pageCursor.delete(selectedCollection.value);
    metadataCache.delete(selectedCollection.value);
    collectionFileTotal.value = 0;
  }
  metadataLoading.value = true;
  loading.value = true;
  try {
    if (metadataAbortController) {
      metadataAbortController.abort();
    }
    metadataAbortController = new AbortController();

    const result = await getCollectionMetadata(selectedCollection.value, {
      cursor: null,
      pageSize: METADATA_PAGE_SIZE,
      signal: metadataAbortController.signal,
    });

    const items = result && result.items ? result.items : [];
    const normalized = normalizeMetadataItems(items);

    metadataList.value = normalized;
    visibleRowCount.value = normalized.length;

    metadataCache.set(selectedCollection.value, normalized);
    collectionStatusCache.set(selectedCollection.value, { loaded: true });
    pageCursor.set(selectedCollection.value, {
      nextCursor: result ? result.nextCursor : null,
      hasMore: result ? result.hasMore : false,
    });

    // 并行获取文件总数
    getCollectionFileNumber(selectedCollection.value)
      .then((num) => { collectionFileTotal.value = typeof num === "number" ? num : 0; })
      .catch(() => {});
  } catch (err) {
    if (err?.name !== "AbortError") {
      console.error(err);
    }
  } finally {
    metadataLoading.value = false;
    loading.value = false;
    nextTick(setupSentinelObserver);
  }
}

async function fetchNextPage() {
  const cursorData = pageCursor.get(selectedCollection.value);
  if (!cursorData || !cursorData.nextCursor) return;

  loading.value = true;
  try {
    const result = await getCollectionMetadata(selectedCollection.value, {
      cursor: cursorData.nextCursor,
      pageSize: METADATA_PAGE_SIZE,
    });

    if (!result || !result.items || result.items.length === 0) return;

    const normalized = normalizeMetadataItems(result.items);
    const combined = [...metadataList.value, ...normalized];
    metadataList.value = combined;
    visibleRowCount.value += normalized.length;

    metadataCache.set(selectedCollection.value, combined);
    pageCursor.set(selectedCollection.value, {
      nextCursor: result.nextCursor,
      hasMore: result.hasMore,
    });
  } catch (err) {
    if (err?.name !== "AbortError") {
      console.error(err);
    }
  } finally {
    loading.value = false;
  }
}

function normalizeMetadataItems(items) {
  return (Array.isArray(items) ? items : []).map((item, index) => {
    const rawMeta = getMetadataSafe(item);
    const docId = getDocIdFromItem(item);
    const docInfo = parseDocId(docId);
    const normalizedMeta = sortMetadataFields(rawMeta, docInfo);
    const stableKey =
      docId || `${rawMeta?.kbId || "kb"}-${docInfo?.chunkId ?? "chunk"}-${index}`;
    const rawContent =
      item && item.content !== undefined && item.content !== null
        ? item.content
        : "";
    let fullContentStr = "";
    try {
      fullContentStr =
        typeof rawContent === "string"
          ? rawContent
          : JSON.stringify(rawContent);
    } catch (e) {
      fullContentStr = String(rawContent);
    }
    return {
      ...item,
      __docId: docId,
      __docInfo: docInfo,
      __rawMeta: rawMeta,
      __fullContent: fullContentStr,
      __contentPreview: clipContentPreview(fullContentStr),
      __meta: normalizedMeta,
      __rowKey: stableKey,
      __contentLength: fullContentStr.length,
    };
  });
}

/** 辅助方法：安全获取元数据对象 */
function getMetadataSafe(item) {
  if (!item) return {};
  if (item.__rawMeta && typeof item.__rawMeta === "object") {
    return item.__rawMeta;
  }
  // 支持多种后端可能传回 metadata 的字段名
  let meta = item.metadata ?? item.meta ?? null;
  if (!meta) return {};
  if (typeof meta === "string") {
    // 尝试直接解析 JSON
    try {
      return JSON.parse(meta);
    } catch (e) {
      // 如果字符串中包含额外前缀（如 metadata=...），尝试抽取第一个 JSON 对象子串
      const braceMatch = meta.match(/\{[\s\S]*\}/);
      if (braceMatch && braceMatch[0]) {
        try {
          return JSON.parse(braceMatch[0]);
        } catch (e2) {
          /* ignore */
        }
      }
      // 尝试从第一个 { 到最后一个 } 截取并解析
      const first = meta.indexOf("{");
      const last = meta.lastIndexOf("}");
      if (first >= 0 && last > first) {
        const sub = meta.substring(first, last + 1);
        try {
          return JSON.parse(sub);
        } catch (e3) {
          /* ignore */
        }
      }
      return {};
    }
  }
  if (typeof meta === "object") {
    return meta;
  }
  return {};
}

function getDocIdFromItem(item) {
  if (!item) return "";
  const v = item.doc_id ?? item.docId ?? item.id ?? "";
  if (v === null || v === undefined) return "";
  const s = String(v).trim();
  return s;
}

function parseDocId(docId) {
  const s = String(docId || "").trim();
  if (!s) return null;
  const idx = s.lastIndexOf(":");
  if (idx <= 0 || idx >= s.length - 1) return null;
  const fileId = s.slice(0, idx);
  const chunkStr = s.slice(idx + 1);
  const n = Number(chunkStr);
  const chunkId = Number.isFinite(n) ? Math.floor(n) : NaN;
  if (!fileId || !Number.isFinite(chunkId)) return null;
  return { docId: s, fileId, chunkId };
}

function sortMetadataFields(meta, docInfo) {
  // 仅显示并按指定顺序返回：fileName, chunkId, chunkSize, createTime
  const sortedKeys = ["fileName", "chunkId", "chunkSize", "createTime"];

  const result = {};
  // 仅保留并按顺序返回 sortedKeys 中的字段，忽略其它权限/内部字段
  sortedKeys.forEach((key) => {
    if (meta && meta[key] !== undefined) {
      result[key] = formatMetadataValue(meta[key]);
      return;
    }
    // chunkId 不再从 metadata 落库，改为从 doc_id 推导
    if (key === "chunkId" && docInfo && docInfo.chunkId !== undefined) {
      result[key] = formatMetadataValue(docInfo.chunkId);
    }
  });

  return result;
}

function formatMetadataValue(value) {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean")
    return String(value);
  if (Array.isArray(value)) {
    try {
      return JSON.stringify(value);
    } catch (_) {
      return String(value);
    }
  }
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch (_) {
      return String(value);
    }
  }
  return String(value);
}

function formatDocIdDisplay(docInfo, meta) {
  if (!docInfo) return '-';
  const { chunkId } = docInfo;
  if (chunkId === undefined || chunkId === null || Number.isNaN(chunkId)) return '-';
  const fileName = meta?.fileName;
  if (fileName) return `${fileName}:${chunkId}`;
  return `...${chunkId}`;
}

const _renderCache = new Map();
const RENDER_CACHE_MAX = 50;
function renderContent(content) {
  if (!content) return "-";
  const cached = _renderCache.get(content);
  if (cached) return cached;
  const html = renderAssistantMarkdown(content);
  if (_renderCache.size >= RENDER_CACHE_MAX) {
    const firstKey = _renderCache.keys().next().value;
    _renderCache.delete(firstKey);
  }
  _renderCache.set(content, html);
  return html;
}

function escapeHtmlText(str) {
  return String(str || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function clipContentPreview(content) {
  const text = String(content || "");
  if (text.length <= MAX_CONTENT_PREVIEW_LENGTH) {
    return text;
  }
  return `${text.slice(0, MAX_CONTENT_PREVIEW_LENGTH)}...`;
}

function openCreateModal() {
  newCollectionName.value = "";
  createError.value = "";
  showCreateModal.value = true;
}

function closeCreateModal() {
  if (!creating.value) {
    showCreateModal.value = false;
  }
}

async function confirmCreate() {
  const name = newCollectionName.value.trim();
  if (!name) {
    createError.value = "集合名不能为空";
    return;
  }
  if (!/^[a-zA-Z0-9_]+$/.test(name)) {
    createError.value = "只能包含字母、数字和下划线";
    return;
  }

  creating.value = true;
  createError.value = "";
  try {
    await apiCreateCollection(name);
    collectionsCache.value = [];
    lastCollectionsFetchAt.value = 0;
    searchResultCache.clear();
    await fetchCollections(); // 刷新列表
    selectCollection(name); // 默认选中刚建的集合
    showCreateModal.value = false;
    newCollectionName.value = ""; // 清空输入框以便下次使用
  } catch (err) {
    console.error(err);
    createError.value = err?.message || "网络请求错误";
  } finally {
    creating.value = false;
  }
}

function closeConfirmModal() {
  if (!isConfirming.value) {
    showConfirmModal.value = false;
    confirmInput.value = "";
  }
}

async function rebuildCurrent() {
  if (!selectedCollection.value) return;
  confirmType.value = "rebuild";
  confirmInput.value = "";
  showConfirmModal.value = true;
}

function dropCurrent() {
  if (!selectedCollection.value) return;
  confirmType.value = "delete";
  confirmInput.value = "";
  showConfirmModal.value = true;
}

async function handleDeleteDoc(item) {
  const rawMeta = getMetadataSafe(item);
  // doc_id = fileId + ':' + 6位 chunkId，删除所需信息优先从 doc_id 推导
  const docId = getDocIdFromItem(item);
  const docInfo = parseDocId(docId);

  let parsedChunkId = docInfo?.chunkId;
  if (parsedChunkId === undefined) {
    // 兼容旧数据：若 metadata 里仍带 chunkId，则兜底使用
    if (rawMeta.chunkId !== undefined && rawMeta.chunkId !== null) {
      const n = Number(rawMeta.chunkId);
      if (Number.isFinite(n) && n >= 0) parsedChunkId = Math.floor(n);
    }
  }

  let fileId = docInfo?.fileId;
  if (!fileId) {
    // 兼容旧数据：若 metadata 里仍带 fileId，则兜底使用
    fileId = rawMeta.fileId || rawMeta.file_id;
  }

  if (parsedChunkId === undefined || !fileId) {
    alert("该文档缺失必要标识(doc_id/fileId/chunkId)，无法执行删除");
    return;
  }

  pendingDeleteDoc.value = {
    item,
    meta: {
      ...rawMeta,
      doc_id: docId,
      chunkId: parsedChunkId,
      fileId: String(fileId),
    },
  };
  confirmType.value = "delete-doc";
  confirmInput.value = "";
  showConfirmModal.value = true;
}

async function executeConfirm() {
  if (!canConfirm.value) return;

  const name = selectedCollection.value;
  const currentType = confirmType.value;

  // 核心优化：在开始请求前立即关闭弹窗
  showConfirmModal.value = false;
  isConfirming.value = true;

  if (currentType === "delete-doc" && pendingDeleteDoc.value) {
    const { meta } = pendingDeleteDoc.value;
    try {
      const chunkId = Number(meta.chunkId);
      if (!Number.isFinite(chunkId) || chunkId < 0)
        throw new Error("非法的 chunkId");
      const fileId = meta.fileId || meta.file_id;
      if (!fileId) throw new Error("找不到 fileId，无法删除");
      // 传递当前集合名给后端，防止后端收到 null
      await apiDeleteDocument(chunkId, String(fileId), name);
      await fetchMetadata(true);
    } catch (e) {
      console.error(e);
      alert(e?.message || "删除失败");
    } finally {
      pendingDeleteDoc.value = null;
    }
  } else if (currentType === "delete") {
    try {
      await apiDeleteCollection(name);
      selectedCollection.value = "";
      searchQuery.value = "";
      metadataList.value = [];
      collectionsCache.value = [];
      lastCollectionsFetchAt.value = 0;
      collectionStatusCache.delete(name);
      metadataCache.delete(name);
      searchResultCache.clear();
      await fetchCollections();
    } catch (e) {
      console.error(e);
      alert("网络请求出错");
    }
  } else if (currentType === "rebuild") {
    try {
      await apiRebuildCollection(name);
      metadataCache.delete(name);
      collectionStatusCache.delete(name);
      await fetchMetadata(true);
    } catch (e) {
      console.error(e);
      alert("网络请求出错");
    }
  }

  isConfirming.value = false;
}

watch(isDbRoute, (val) => {
  if (val) {
    fetchCollections();
  }
});

onUnmounted(() => { document.body.style.overflow = ''; });
</script>

<style scoped>
/* 全局设定 */
.db-view {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: transparent;
  color: var(--text-main);
  overflow: hidden;
  padding: 12px;
  gap: 12px;
}

/* 头部玻璃态 */
.app-glass-header {
  min-height: 74px;
  padding: 10px calc(var(--layout-gap) + 8px);
  display: flex;
  align-items: center;
  background: color-mix(
    in srgb,
    var(--glass-strong, rgba(255, 255, 255, 0.66)) 90%,
    transparent
  );
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 86%, transparent);
  box-shadow:
    0 6px 20px rgba(18, 35, 69, 0.1),
    0 1px 0 rgba(255, 255, 255, 0.24) inset;
  border-radius: 24px;
  overflow: visible;
  z-index: 20;
}

.header-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
}

.title-group {
  display: flex;
  align-items: center;
  gap: 14px;
}
.title-group h2 {
  font-size: clamp(20px, 1.2vw, 24px);
  font-weight: 820;
  background: linear-gradient(
    135deg,
    var(--primary),
    color-mix(in srgb, var(--secondary) 78%, #fff)
  );
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  margin: 0;
  letter-spacing: -0.02em;
}

.icon-pulse {
  color: var(--primary);
  display: flex;
  width: 30px;
  height: 30px;
  border-radius: 10px;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--primary) 14%, transparent);
  box-shadow: 0 10px 18px rgba(47, 71, 126, 0.16);
  animation: dbIconFloat 2.8s ease-in-out infinite;
}

.sidebar-entry-btn {
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  cursor: pointer;
  transition:
    background-color var(--motion-fast, 0.18s) var(--motion-ease, ease),
    border-color var(--motion-fast, 0.18s) var(--motion-ease, ease),
    transform var(--motion-fast, 0.18s) var(--motion-ease, ease),
    box-shadow var(--motion-medium, 0.24s) var(--motion-ease, ease);
}

.sidebar-entry-btn:hover {
  background: color-mix(in srgb, var(--primary) 20%, transparent);
  border-color: color-mix(in srgb, var(--primary) 52%, var(--panel-border));
  transform: translateY(-1px);
}

.control-group {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
  flex-wrap: wrap;
  justify-content: flex-end;
}

/* 综合搜索下拉组件 */
.search-select-wrapper {
  position: relative;
  width: clamp(250px, 34vw, 380px);
  display: flex;
  align-items: center;
  z-index: 30;
}

.search-icon {
  position: absolute;
  left: 14px;
  color: var(--primary);
  opacity: 0.7;
  pointer-events: none;
}

.glass-input {
  width: 100%;
  height: 43px;
  padding: 10px 40px;
  background: color-mix(
    in srgb,
    var(--surface-soft, rgba(255, 255, 255, 0.58)) 92%,
    transparent
  );
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  border-radius: 13px;
  color: var(--text-main);
  font-size: 14px;
  font-weight: 560;
  appearance: none;
  transition:
    border-color 0.22s ease,
    background-color 0.22s ease,
    box-shadow 0.22s ease,
    transform 0.18s ease;
  box-shadow: 0 8px 16px rgba(24, 43, 82, 0.08);
}
.glass-input:hover,
.glass-input:focus {
  border-color: color-mix(in srgb, var(--primary) 48%, var(--panel-border));
  outline: none;
  box-shadow:
    0 10px 20px rgba(22, 42, 83, 0.12),
    0 0 0 3px color-mix(in srgb, var(--primary) 16%, transparent);
}

.clear-search-btn {
  position: absolute;
  right: 36px;
  background: none;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.18s ease;
}
.clear-search-btn:hover {
  background: rgba(244, 124, 142, 0.14);
  color: var(--primary);
  transform: scale(1.05);
}

.arrow-down {
  position: absolute;
  right: 14px;
  pointer-events: auto;
  color: var(--primary);
  opacity: 0.8;
  cursor: pointer;
  display: flex;
  transition: transform 0.2s ease;
}
.arrow-down.open {
  transform: rotate(180deg);
}

.glass-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  width: 100%;
  background: var(--overlay-soft, rgba(255, 255, 255, 0.84));
  border: 1px solid var(--panel-border);
  border-radius: 14px;
  box-shadow:
    0 16px 30px rgba(16, 31, 61, 0.2),
    0 1px 0 rgba(255, 255, 255, 0.24) inset;
  max-height: 280px;
  overflow-y: auto;
  z-index: 100;
  padding: 8px;
  list-style: none;
  margin: 0;
}

.glass-dropdown::-webkit-scrollbar {
  width: 5px;
  height: 5px;
}

.glass-dropdown::-webkit-scrollbar-track {
  background: transparent;
}

.glass-dropdown::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
}

.glass-dropdown:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--text-muted) 15%, transparent);
}

.glass-dropdown::-webkit-scrollbar-thumb:hover {
  background: color-mix(in srgb, var(--text-muted) 25%, transparent);
}

.dropdown-item {
  padding: 10px 14px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--text-main);
  font-size: 14.5px;
  transition:
    background-color 0.18s ease,
    color 0.18s ease,
    transform 0.18s ease;
}
.dropdown-item:hover,
.dropdown-item.active {
  background: color-mix(in srgb, var(--primary) 16%, transparent);
  color: var(--primary);
  font-weight: 600;
  transform: translateX(1px);
}
.dropdown-item.empty {
  cursor: default;
  color: var(--text-muted);
  justify-content: center;
}
.check-icon {
  display: flex;
  color: var(--primary);
}

/* 下拉菜单动画 */
.fade-down-enter-active,
.fade-down-leave-active {
  transition: opacity 0.15s ease;
}
.fade-down-enter-from,
.fade-down-leave-to {
  opacity: 0;
}

.glass-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--surface-soft) 88%, transparent);
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  border-radius: 12px;
  color: var(--primary);
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    border-color 0.2s ease,
    transform 0.18s ease,
    box-shadow 0.2s ease;
  box-shadow: 0 10px 18px rgba(20, 39, 75, 0.1);
}

.icon-btn {
  width: 42px;
  height: 42px;
}

.sidebar-collapse-btn {
  background: color-mix(in srgb, var(--surface-soft) 82%, transparent);
  color: var(--text-secondary);
}

.sidebar-collapse-btn.active {
  background: color-mix(in srgb, var(--primary) 18%, transparent);
  border-color: color-mix(in srgb, var(--primary) 56%, var(--panel-border));
  color: var(--primary);
  box-shadow:
    0 12px 18px rgba(49, 69, 118, 0.2),
    0 0 0 2px color-mix(in srgb, var(--primary) 17%, transparent);
}

.action-btn.sidebar-collapse-btn:hover {
  background: color-mix(in srgb, var(--primary) 22%, transparent);
  color: var(--primary);
}

.glass-btn:hover {
  background: color-mix(in srgb, var(--primary) 12%, var(--surface-soft));
  color: var(--primary);
  border-color: color-mix(in srgb, var(--primary) 40%, var(--panel-border));
  transform: translateY(-1px);
  box-shadow: 0 12px 20px rgba(20, 39, 75, 0.14);
}

.spinning svg {
  animation: spin 1s infinite linear;
}

@keyframes spin {
  100% {
    transform: rotate(360deg);
  }
}

/* 工作区结构 */
.db-workspace {
  flex: 1;
  padding: var(--layout-gap);
  display: flex;
  overflow: hidden;
}

.glass-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: color-mix(
    in srgb,
    var(--glass-soft, rgba(255, 255, 255, 0.52)) 88%,
    transparent
  );
  border-radius: calc(var(--panel-radius) - 2px);
  border: 1px solid color-mix(in srgb, var(--panel-border) 90%, transparent);
  box-shadow:
    0 20px 36px rgba(14, 28, 56, 0.14),
    0 2px 10px rgba(14, 28, 56, 0.08);
  backdrop-filter: none;
  overflow: hidden;
  animation: panelReveal 0.28s ease;
  position: relative;
  border-radius: 28px;
}

.glass-panel::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(
      circle at 10% -18%,
      color-mix(in srgb, var(--primary) 16%, transparent),
      transparent 42%
    ),
    radial-gradient(
      circle at 92% -12%,
      color-mix(in srgb, var(--secondary) 12%, transparent),
      transparent 44%
    );
  opacity: 0.45;
}

.panel-header {
  padding: 12px calc(var(--layout-gap) + 2px) 13px;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 88%, transparent);
  background: color-mix(
    in srgb,
    var(--glass-strong, rgba(255, 255, 255, 0.66)) 86%,
    transparent
  );
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  position: relative;
  z-index: 1;
  border-radius: 0;
}

.dataset-stats {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.create-btn-top {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 13px;
  background: var(--primary);
  color: white;
  border: none;
  cursor: pointer;
  box-shadow: 0 12px 20px rgba(58, 37, 98, 0.22);
  transition:
    filter 0.2s ease,
    opacity 0.2s ease,
    transform 0.18s ease;
}
.create-btn-top:hover {
  filter: brightness(1.06);
  color: white;
  transform: translateY(-1px);
}

.action-group {
  display: flex;
  gap: 9px;
  align-items: center;
  flex-wrap: wrap;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 6px 13px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    border-color 0.2s ease,
    transform 0.18s ease,
    box-shadow 0.18s ease;
  border: 1px solid color-mix(in srgb, var(--panel-border) 74%, transparent);
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  color: var(--primary);
}
.action-btn:hover {
  background: var(--primary);
  color: #fff;
  transform: translateY(-1px);
  box-shadow: 0 11px 18px rgba(58, 37, 99, 0.22);
}

.upload-btn {
  background: color-mix(in srgb, var(--secondary) 14%, transparent);
  color: var(--secondary);
}
.upload-btn:hover {
  background: var(--secondary);
  color: #fff;
}

.warning-btn {
  background: rgba(245, 158, 11, 0.12);
  color: #f59e0b;
}
.warning-btn:hover {
  background: #f59e0b;
  color: #fff;
}

.danger-btn {
  background: rgba(239, 68, 68, 0.12);
  color: #ef4444;
}
.danger-btn:hover {
  background: #ef4444;
  color: #fff;
}

.action-btn:disabled,
.action-btn:disabled:hover {
  opacity: 0.68;
  transform: none;
  box-shadow: none;
  cursor: not-allowed;
}

/* 创建弹窗遮罩 */
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(9, 16, 30, 0.44);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 999;
}

/* 弹窗卡片 */
.modal-card {
  width: min(480px, 92vw);
  background: color-mix(
    in srgb,
    var(--surface-solid, #ffffff) 94%,
    transparent
  );
  border-radius: 18px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 88%, transparent);
  box-shadow:
    0 26px 42px rgba(9, 17, 31, 0.35),
    0 1px 0 rgba(255, 255, 255, 0.24) inset;
  display: flex;
  flex-direction: column;
  animation: panelReveal 0.22s ease;
}

.modal-header {
  padding: 14px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 86%, transparent);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--primary);
  font-weight: 700;
}
.close-btn {
  background: none;
  border: none;
  font-size: 24px;
  color: var(--text-muted);
  cursor: pointer;
  line-height: 1;
  transition:
    color 0.2s ease,
    transform 0.18s ease;
}

.close-btn:hover {
  color: #ef4444;
  transform: scale(1.04);
}

.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.modal-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.5;
}
.create-input {
  padding: 12px 16px;
  width: auto;
}
.error-msg {
  margin: 0;
  font-size: 12px;
  color: #ef4444;
}

.modal-footer {
  padding: 12px 20px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  border-top: 1px solid color-mix(in srgb, var(--panel-border) 86%, transparent);
  background: color-mix(in srgb, var(--surface-soft) 82%, transparent);
}

.cancel-btn {
  background: transparent;
  color: var(--text-main);
  border-color: var(--panel-border);
  padding: 8px 16px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
}

.cancel-btn:hover {
  background: color-mix(in srgb, var(--hover-bg) 72%, transparent);
}

.confirm-btn {
  background: var(--primary);
  color: #fff;
  padding: 8px 24px;
  border-radius: 10px;
  border: none;
  font-size: 13px;
  font-weight: 640;
  cursor: pointer;
  transition:
    filter 0.2s ease,
    transform 0.18s ease,
    box-shadow 0.18s ease;
}

.confirm-btn:hover:not(:disabled) {
  background: var(--primary-hover);
  filter: brightness(1.05);
  transform: translateY(-1px);
  box-shadow: 0 12px 20px rgba(54, 31, 96, 0.24);
}

.confirm-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.danger-fill-btn {
  background: #ef4444;
  color: white;
}
.danger-fill-btn:hover:not(:disabled) {
  background: #dc2626;
}

.warning-fill-btn {
  background: #f59e0b;
  color: white;
}
.warning-fill-btn:hover:not(:disabled) {
  background: #d97706;
}

.stat-badge {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 13px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--panel-border) 70%, transparent);
  color: var(--primary);
  font-size: 13px;
  font-weight: 560;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.18s ease;
}
.stat-badge.clickable {
  cursor: pointer;
}
.stat-badge.clickable:hover {
  background: color-mix(in srgb, var(--primary) 19%, transparent);
  transform: translateY(-1px);
}

.stat-badge.outline {
  background: transparent;
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  color: var(--secondary);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 10px rgba(34, 197, 94, 0.7);
}

.highlight-text {
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.2px;
}

/* 表格区域：隐藏滚动条不占宽度，鼠标滚轮仍可滚动 */
.table-scroll-area {
  flex: 1 1 0px;
  min-height: 0;
  overflow-y: auto;
  width: 100%;
  position: relative;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.table-scroll-area::-webkit-scrollbar {
  width: 0;
  height: 0;
}

.hero-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  border-spacing: 0;
}

.hero-table th {
  position: sticky;
  top: 0;
  z-index: 5;
  background: color-mix(in srgb, var(--overlay-strong) 92%, transparent);
  padding: 14px 18px;
  text-align: left;
  font-size: 12px;
  font-weight: 760;
  color: var(--primary);
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 86%, transparent);
  letter-spacing: 0.05em;
}

.hero-table th:first-child {
  border-top-left-radius: 0;
}

.hero-table th:last-child {
  border-top-right-radius: 0;
}

.th-id {
  width: 15%;
  min-width: 148px;
}
.th-content {
  width: 45%;
}
.th-meta {
  width: 33%;
  min-width: 280px;
}
.th-actions {
  width: 7%;
  min-width: 76px;
  text-align: center !important;
}

.td-id {
  vertical-align: middle !important;
  padding-right: 10px;
}

.td-actions {
  vertical-align: middle !important;
}

.td-actions {
  text-align: center;
  padding: 16px 0 !important;
}

.action-btn-delete {
  background: color-mix(in srgb, #ef4444 9%, transparent);
  border: 1px solid color-mix(in srgb, #ef4444 24%, transparent);
  cursor: pointer;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    border-color 0.2s ease,
    transform 0.18s ease,
    box-shadow 0.18s ease;
  color: #ef4444;
}

.action-btn-delete:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px color-mix(in srgb, #ef4444 28%, transparent);
  border-color: color-mix(in srgb, #ef4444 56%, transparent);
}

.action-btn-delete:hover {
  background: #ef4444;
  color: #fff;
  border-color: transparent;
  transform: translateY(-1px);
  box-shadow: 0 10px 16px rgba(153, 33, 33, 0.26);
}

/* 行内操作按钮组 */
.hero-row {
  transition:
    background-color var(--motion-fast, 0.18s) var(--motion-ease, ease),
    box-shadow var(--motion-medium, 0.24s) var(--motion-ease, ease),
    transform var(--motion-fast, 0.18s) var(--motion-ease, ease);
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 86%, transparent);
  content-visibility: auto;
  contain-intrinsic-size: auto 56px;
}
.hero-row:hover {
  background: color-mix(in srgb, var(--primary) 8%, transparent);
  box-shadow: inset 2px 0 0 color-mix(in srgb, var(--primary) 52%, transparent);
}

.hero-table td {
  padding: 16px 18px;
  vertical-align: top;
}

/* ID与内容展示 */
.hash-tag {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  min-height: 28px;
  padding: 4px 10px 4px 12px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--secondary) 5%, transparent);
  border: 1px solid color-mix(in srgb, var(--secondary) 10%, transparent);
  color: color-mix(in srgb, var(--secondary) 82%, var(--text-main));
  font-family: "Microsoft YaHei", sans-serif;
  font-size: 11px;
  font-weight: 640;
  line-height: 1.35;
  letter-spacing: 0.02em;
  word-break: break-all;
  overflow-wrap: anywhere;
  box-shadow: none;
}

.td-content {
  position: relative;
  padding: 10px 12px !important;
  vertical-align: top;
}

.text-block {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-main);
  font-family: "Microsoft YaHei", sans-serif;
  letter-spacing: 0.01em;
  word-break: break-word;
  font-weight: 430;
  max-height: 60px;
  overflow: hidden;
  transition: none;
}
.td-content.expanded .text-block {
  max-height: 300px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}
.td-content.expanded .text-block:hover {
  scrollbar-color: color-mix(in srgb, var(--text-muted) 15%, transparent) transparent;
}
.td-content.expanded .text-block::-webkit-scrollbar {
  width: 5px;
  height: 5px;
}
.td-content.expanded .text-block::-webkit-scrollbar-track {
  background: transparent;
}
.td-content.expanded .text-block::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
}
.td-content.expanded .text-block:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--text-muted) 15%, transparent);
}
.td-content.expanded .text-block::-webkit-scrollbar-thumb:hover {
  background: color-mix(in srgb, var(--text-muted) 25%, transparent);
}

.expand-btn {
  position: absolute;
  right: 4px;
  bottom: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: color-mix(in srgb, var(--primary) 12%, var(--bg-base));
  color: var(--primary);
  cursor: pointer;
  transition: transform 0.2s ease, background-color 0.15s;
  opacity: 0.85;
  padding: 0;
}
.expand-btn:hover {
  background: color-mix(in srgb, var(--primary) 22%, var(--bg-base));
  opacity: 1;
}
.td-content.expanded .expand-btn svg {
  transform: rotate(90deg);
}

.text-block :deep(p) {
  margin: 0;
}
.text-block :deep(ul),
.text-block :deep(ol) {
  margin: 0;
  padding-left: 1.2em;
}
.text-block :deep(h1),
.text-block :deep(h2),
.text-block :deep(h3),
.text-block :deep(h4) {
  margin: 0;
  font-size: inherit;
  font-weight: 600;
}
.text-block :deep(blockquote) {
  margin: 0;
  padding: 0 0.5em;
  border-left: 2px solid color-mix(in srgb, var(--primary) 40%, transparent);
}
.text-block :deep(pre) {
  margin: 4px 0;
  font-size: inherit;
  font-family: inherit;
  white-space: pre-wrap;
  background: transparent !important;
}
.text-block :deep(pre code.hljs) {
  background: transparent !important;
}

/* Glass Tag 元数据设计 */
.tag-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.glass-tag {
  display: inline-flex;
  align-items: center;
  background: color-mix(in srgb, var(--glass-soft) 88%, transparent);
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  border-radius: 10px;
  font-size: 12px;
  box-shadow: 0 5px 12px rgba(20, 39, 75, 0.08);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;
}
.glass-tag:hover {
  background: color-mix(in srgb, var(--primary) 8%, var(--glass-soft));
  border-color: color-mix(in srgb, var(--primary) 58%, var(--panel-border));
}

.tag-label {
  padding: 4px 10px;
  background: color-mix(in srgb, var(--primary) 7%, transparent);
  color: var(--secondary);
  font-weight: 600;
  border-right: 1px solid
    color-mix(in srgb, var(--panel-border) 86%, transparent);
  border-top-left-radius: 10px;
  border-bottom-left-radius: 10px;
}

.tag-value {
  padding: 4px 10px;
  color: var(--text-main);
  font-weight: 600;
}

/* 炫酷状态展示 */
.state-cell {
  padding: 72px 0;
  text-align: center;
}

.loading-fx {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  color: var(--primary);
  font-weight: 620;
}

.magic-ring {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: 4px solid color-mix(in srgb, var(--panel-border) 88%, transparent);
  border-top-color: var(--primary);
  border-left-color: var(--secondary);
  animation: magicSpin 0.9s linear infinite;
}
@keyframes magicSpin {
  0% {
    transform: rotate(0);
  }
  100% {
    transform: rotate(360deg);
  }
}

.empty-fx {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: var(--text-main);
}

.ambient-icon {
  width: 92px;
  height: 92px;
  border-radius: 24px;
  background: color-mix(in srgb, var(--primary) 9%, transparent);
  display: flex;
  justify-content: center;
  align-items: center;
  color: var(--primary);
  margin-bottom: 18px;
  box-shadow: 0 12px 22px rgba(19, 37, 71, 0.12);
}

.empty-fx h3 {
  font-size: 19px;
  color: var(--primary);
  margin: 0 0 8px 0;
  font-weight: 740;
}

.empty-fx p {
  color: var(--text-muted);
  font-size: 13px;
  margin: 0;
}

.state-action-btn {
  margin-top: 14px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 82%, transparent);
  background: color-mix(in srgb, var(--primary) 12%, transparent);
  color: var(--primary);
  border-radius: 10px;
  padding: 8px 14px;
  font-weight: 650;
  cursor: pointer;
  transition:
    background-color var(--motion-fast, 0.18s) var(--motion-ease, ease),
    border-color var(--motion-fast, 0.18s) var(--motion-ease, ease),
    color var(--motion-fast, 0.18s) var(--motion-ease, ease),
    transform var(--motion-fast, 0.18s) var(--motion-ease, ease);
}

.state-action-btn:hover:not(:disabled) {
  background: var(--primary);
  color: #fff;
  border-color: transparent;
  transform: translateY(-1px);
}

.state-action-btn:disabled {
  opacity: 0.58;
  cursor: not-allowed;
}

@keyframes dbIconFloat {
  0%,
  100% {
    transform: translateY(0);
    opacity: 0.9;
  }
  50% {
    transform: translateY(-2px);
    opacity: 1;
  }
}

@keyframes panelReveal {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.fade-scale-enter-active,
.fade-scale-leave-active {
  transition:
    opacity var(--motion-medium, 0.24s) var(--motion-ease, ease),
    transform var(--motion-medium, 0.24s) var(--motion-ease, ease);
}

.fade-scale-enter-from,
.fade-scale-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.98);
}

/* 轻量化视觉：减少数据库页过度炫彩与位移动效 */
.title-group h2 {
  background: none;
  -webkit-text-fill-color: currentColor;
  color: var(--text-main);
  font-weight: 700;
}

.icon-pulse {
  animation: none;
  box-shadow: none;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
}

.app-glass-header,
.glass-panel,
.panel-header {
  box-shadow: none;
}

.glass-btn:hover,
.action-btn:hover,
.create-btn-top:hover,
.state-action-btn:hover:not(:disabled),
.hero-row:hover {
  transform: none;
  box-shadow: none;
}

@media (max-width: 1360px) {
  .app-glass-header {
    padding: 10px var(--layout-gap);
  }

  .db-workspace {
    padding: calc(var(--layout-gap) - 2px);
  }

  .search-select-wrapper {
    width: 300px;
  }

  .dataset-stats {
    gap: 10px;
  }

  .action-group {
    gap: 8px;
  }

  .hero-table {
    min-width: 860px;
  }
}

@media (max-width: 1100px) {
  .header-content {
    align-items: center;
    flex-wrap: wrap;
  }

  .control-group {
    margin-left: 0;
    width: 100%;
    justify-content: flex-start;
  }

  .search-select-wrapper {
    width: min(460px, 100%);
  }

  .panel-header {
    align-items: flex-start;
  }

  .action-group {
    width: 100%;
  }
}
/* 确保父容器正确传递高度 */
.glass-panel {
  flex: 1 1 0px !important;
  min-height: 0 !important;
  overflow: hidden !important;
}

.db-workspace {
  flex: 1 1 0px !important;
  min-height: 0 !important;
}
</style>
