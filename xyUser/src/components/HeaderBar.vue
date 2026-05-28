<template>
  <header class="header">
    <div class="header-title">
      <button
        id="toggleSidebar"
        class="sidebar-toggle header-sidebar-toggle"
        @click="store.toggleSidebar()"
      >
        <svg
          width="24"
          height="24"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <line x1="3" y1="12" x2="21" y2="12"></line>
          <line x1="3" y1="6" x2="21" y2="6"></line>
          <line x1="3" y1="18" x2="21" y2="18"></line>
        </svg>
      </button>
      对话主控制台
      <span class="enterprise-badge">ENTERPRISE</span>
    </div>
    <div class="header-controls">
      <div class="status-badge">
        <div class="status-dot"></div>
        <span id="status">系统就绪</span>
      </div>
      <button
        class="theme-toggle dark-quick-toggle"
        :class="{ active: isDark }"
        type="button"
        @click="toggleThemeLocally"
      >
        <svg
          v-if="!isDark"
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="4"></circle>
          <path d="M12 2v2"></path>
          <path d="M12 20v2"></path>
          <path d="m4.93 4.93 1.41 1.41"></path>
          <path d="m17.66 17.66 1.41 1.41"></path>
          <path d="M2 12h2"></path>
          <path d="M20 12h2"></path>
          <path d="m6.34 17.66-1.41 1.41"></path>
          <path d="m19.07 4.93-1.41 1.41"></path>
        </svg>
        <svg
          v-else
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"></path>
        </svg>
      </button>
      <div ref="appearanceAnchor" class="appearance-anchor">
        <button
          id="toggleTheme"
          class="theme-toggle appearance-trigger"
          @click.stop="toggleAppearancePanel"
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
            <circle cx="12" cy="12" r="10"></circle>
            <circle cx="12" cy="12" r="4"></circle>
            <line x1="21.17" y1="8" x2="12" y2="8"></line>
            <line x1="3.95" y1="6.06" x2="8.54" y2="14"></line>
            <line x1="10.88" y1="21.94" x2="15.46" y2="14"></line>
          </svg>
        </button>

        <transition name="panel-fade">
          <div
            v-if="showAppearancePanel"
            ref="appearancePanel"
            class="appearance-panel modern-panel"
            @click.stop
          >
            <div class="panel-head">
              <h4>外观与主题设定</h4>
              <button
                class="mini-close"
                aria-label="关闭主题面板"
                @click="showAppearancePanel = false"
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
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
              </button>
            </div>

            <div class="panel-tabs">
              <button
                v-for="tab in ['theme', 'bubble', 'background']"
                :key="tab"
                class="panel-tab-btn"
                :class="{ active: activeTab === tab }"
                @click="activeTab = tab"
              >
                {{
                  tab === "theme"
                    ? "色彩布局"
                    : tab === "bubble"
                      ? "气泡皮肤"
                      : "背景头像"
                }}
              </button>
            </div>

            <div class="panel-content-scroll">
              <!-- 色彩布局 Tab -->
              <div v-if="activeTab === 'theme'" class="tab-pane">
                <div class="panel-section">
                  <div class="section-title">主界面配色</div>
                  <div class="theme-grid">
                    <button
                      v-for="item in themeOptions"
                      :key="item.name"
                      class="theme-chip"
                      :class="{ active: item.name === store.themeName }"
                      @click="changeTheme(item.name)"
                    >
                      <span
                        class="chip-dot"
                        :style="{ background: item.preview }"
                      ></span>
                      <span>{{ item.label }}</span>
                    </button>
                  </div>
                </div>

                <div class="panel-section">
                  <div
                    class="section-title"
                    style="
                      display: flex;
                      justify-content: space-between;
                      align-items: center;
                    "
                  >
                    <span>自定义强调色</span>
                    <span
                      v-if="hasCustomColors"
                      class="color-reset"
                      @click="resetCustomColors"
                      >恢复默认</span
                    >
                  </div>
                  <div class="color-group">
                    <div class="color-swatches">
                      <button
                        v-for="c in primaryPresets"
                        :key="c.color || '__default__'"
                        class="swatch-btn"
                        :class="{
                          active:
                            (c.color || '') === (store.customPrimary || ''),
                        }"
                        @click="setPrimaryColor(c.color)"
                      >
                        <span v-if="!c.color" class="swatch-label">默认</span>
                        <span
                          v-else
                          class="swatch-dot"
                          :style="{ background: c.color }"
                        ></span>
                      </button>
                      <label class="swatch-btn swatch-plus">
                        <input
                          type="color"
                          class="color-input-hidden"
                          :value="store.customPrimary || '#2563eb'"
                          @input="setPrimaryColor($event.target.value)"
                        />
                        <span class="swatch-label">+</span>
                      </label>
                    </div>
                  </div>
                  <div class="color-group" style="margin-top: 12px">
                    <div
                      class="color-group-label"
                      style="margin-bottom: 4px; font-size: 12px"
                    >
                      全局边框色
                    </div>
                    <div class="color-swatches">
                      <button
                        v-for="c in borderPresets"
                        :key="c.color || '__default__'"
                        class="swatch-btn"
                        :class="{
                          active:
                            (c.color || '') === (store.customBorder || ''),
                        }"
                        @click="setBorderColor(c.color)"
                      >
                        <span v-if="!c.color" class="swatch-label">默认</span>
                        <span
                          v-else
                          class="swatch-dot"
                          :style="{ background: c.color }"
                        ></span>
                      </button>
                      <label class="swatch-btn swatch-plus">
                        <input
                          type="color"
                          class="color-input-hidden"
                          :value="store.customBorder || '#64748b'"
                          @input="setBorderColor($event.target.value)"
                        />
                        <span class="swatch-label">+</span>
                      </label>
                    </div>
                  </div>
                </div>

                <div class="panel-section last-section">
                  <div class="section-title">侧栏模式</div>
                  <div class="style-row" style="grid-template-columns: 1fr 1fr">
                    <button
                      class="style-chip align-center"
                      :class="{ active: store.sidebarMode === 'fullscreen' }"
                      @click="store.setSidebarMode('fullscreen')"
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
                        <rect
                          x="3"
                          y="3"
                          width="18"
                          height="18"
                          rx="2"
                          ry="2"
                        ></rect>
                        <line x1="9" y1="3" x2="9" y2="21"></line>
                      </svg>
                      <span>固定展开</span>
                    </button>
                    <button
                      class="style-chip align-center"
                      :class="{ active: store.sidebarMode === 'collapse' }"
                      @click="store.setSidebarMode('collapse')"
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
                        <rect
                          x="3"
                          y="3"
                          width="18"
                          height="18"
                          rx="2"
                          ry="2"
                        ></rect>
                      </svg>
                      <span>自动收起</span>
                    </button>
                  </div>
                </div>
              </div>

              <!-- 气泡皮肤 Tab -->
              <div v-if="activeTab === 'bubble'" class="tab-pane">
                <div class="panel-section">
                  <div class="bot-bubble-row" @click="toggleBotBubble">
                    <div class="bubble-row-info">
                      <div class="bubble-row-title">启用独立 AI 底色</div>
                      <div class="bubble-row-desc">
                        区分用户与 AI 气泡的背景颜色
                      </div>
                    </div>
                    <div
                      class="toggle-switch"
                      :class="{ 'is-active': store.botBubbleEnabled }"
                    >
                      <div class="toggle-knob"></div>
                    </div>
                  </div>
                </div>

                <div class="panel-section last-section">
                  <div class="section-title">消息气泡皮肤</div>
                  <div class="skin-grid-modern">
                    <button
                      v-for="skin in bubbleSkinOptions"
                      :key="skin.name"
                      class="skin-card"
                      :class="{ active: store.bubbleSkin === skin.name }"
                      @click="store.setBubbleSkin(skin.name)"
                    >
                      <div
                        class="skin-card-preview"
                        :style="{ background: skin.preview }"
                      >
                        <svg
                          v-if="skin.name === 'cat'"
                          viewBox="0 0 300 180"
                          width="80%"
                          height="80%"
                          class="icon-cat"
                        >
                          <g
                            fill="none"
                            stroke="#FFFFFF"
                            stroke-width="10"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          >
                            <path
                              d="M 60 30 L 50 5 L 80 30 H 220 L 250 5 L 240 30 A 30 30 0 0 1 270 60 V 110 A 30 30 0 0 1 240 140 Q 260 160 280 160 Q 240 160 210 140 H 60 A 30 30 0 0 1 30 110 V 60 A 30 30 0 0 1 60 30 Z"
                            />
                            <path
                              d="M 60 120 Q 80 125 100 120 M 60 130 Q 80 135 100 130 M 240 120 Q 220 125 200 120 M 240 130 Q 220 135 200 130"
                              stroke-dasharray="4 8"
                              stroke-width="6"
                            />
                          </g>
                        </svg>
                        <svg
                          v-else-if="skin.name === 'dog'"
                          viewBox="0 0 300 180"
                          width="80%"
                          height="80%"
                          class="icon-dog"
                        >
                          <g
                            fill="none"
                            stroke="#FFFFFF"
                            stroke-width="10"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          >
                            <path
                              d="M 80 30 H 220 A 20 20 0 0 1 240 50 C 290 40, 290 120, 240 110 V 120 A 20 20 0 0 1 220 140 Q 240 160 250 160 Q 220 160 200 140 H 160 A 10 10 0 0 0 140 140 H 80 A 20 20 0 0 1 60 120 C 10 120, 10 40, 60 50 A 20 20 0 0 1 80 30 Z"
                            />
                            <path
                              d="M 235 60 Q 245 80 238 100 M 65 60 Q 55 80 62 100"
                              stroke-width="6"
                            />
                            <circle
                              cx="138"
                              cy="125"
                              r="5"
                              fill="#FFFFFF"
                              stroke="none"
                            />
                            <circle
                              cx="162"
                              cy="125"
                              r="5"
                              fill="#FFFFFF"
                              stroke="none"
                            />
                            <path
                              d="M 146 142 V 148 C 146 153, 154 153, 154 148 V 142"
                              stroke-width="6"
                              stroke-dasharray="2 6"
                            />
                          </g>
                        </svg>
                        <svg
                          v-else-if="skin.name === 'fish'"
                          viewBox="0 0 300 180"
                          width="80%"
                          height="80%"
                          class="icon-fish"
                        >
                          <g
                            fill="none"
                            stroke="#FFFFFF"
                            stroke-width="10"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          >
                            <path
                              d="M 80 70 Q 130 10 220 40 Q 270 80 220 120 Q 140 160 80 90 L 20 135 Q 40 80 15 20 L 80 70 Z"
                            />
                            <path d="M 210 65 Q 195 80 210 95" />
                            <circle cx="225" cy="70" r="6" />
                            <path d="M 140 23 L 130 5 Q 160 10 180 28" />
                            <path d="M 140 148 L 120 170 Q 160 170 170 135" />
                          </g>
                        </svg>
                        <svg
                          v-else-if="skin.name === 'helloKitty'"
                          viewBox="0 0 300 240"
                          width="70%"
                          height="70%"
                          class="icon-kitty"
                        >
                          <g
                            fill="none"
                            stroke="#FFFFFF"
                            stroke-width="10"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          >
                            <path
                              d="M 140 50 L 105 20 A 15 20 0 0 0 105 75 L 130 58 A 75 75 0 0 0 95 180 Q 70 200 50 205 Q 85 200 110 193 A 75 75 0 0 0 170 58 L 195 75 A 15 20 0 0 0 195 20 L 160 50 L 150 40 L 140 50 Z"
                            />
                            <polygon points="150,40 160,50 150,60 140,50" />
                            <path
                              d="M 78 100 L 45 95 M 73 115 L 43 115 M 78 130 L 45 135"
                            />
                            <path
                              d="M 222 100 L 255 95 M 227 115 L 257 115 M 222 130 L 255 135"
                            />
                            <ellipse cx="120" cy="115" rx="5" ry="8" />
                            <ellipse cx="180" cy="115" rx="5" ry="8" />
                            <ellipse cx="150" cy="125" rx="8" ry="5" />
                          </g>
                        </svg>
                        <svg
                          v-else
                          viewBox="0 0 300 180"
                          width="70%"
                          height="70%"
                          class="icon-default"
                        >
                          <g
                            fill="none"
                            stroke="#FFFFFF"
                            stroke-width="16"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          >
                            <rect
                              x="30"
                              y="30"
                              width="240"
                              height="110"
                              rx="30"
                            />
                            <path
                              d="M 70 140 L 50 170 L 90 140 Z"
                              fill="#FFFFFF"
                            />
                          </g>
                        </svg>
                      </div>
                      <span class="skin-label">{{ skin.label }}</span>
                    </button>
                  </div>
                </div>
              </div>

              <!-- 背景头像 Tab -->
              <div v-if="activeTab === 'background'" class="tab-pane">
                <div class="panel-section">
                  <div class="section-title">界面透明度/磨砂调节</div>
                  <div class="slider-group">
                    <div class="slider-row">
                      <span class="slider-label">模糊度</span>
                      <input
                        class="slider-input"
                        type="range"
                        min="0"
                        max="24"
                        step="1"
                        :value="backgroundBlur"
                        @input="handleBackgroundBlurChange"
                        @change="flushBackgroundBlurChange"
                      />
                      <span class="slider-value">{{ backgroundBlur }}px</span>
                    </div>
                    <div class="slider-row">
                      <span class="slider-label">不透明</span>
                      <input
                        class="slider-input"
                        type="range"
                        min="0"
                        max="100"
                        step="1"
                        :value="Math.round(backgroundOpacity * 100)"
                        @input="handleBackgroundOpacityChange"
                        @change="flushBackgroundOpacityChange"
                      />
                      <span class="slider-value"
                        >{{ Math.round(backgroundOpacity * 100) }}%</span
                      >
                    </div>
                  </div>
                </div>

                <div class="panel-section">
                  <div class="media-upload-card">
                    <div class="media-info">
                      <div class="media-title">自定义侧栏背景图片</div>
                      <div class="media-desc">
                        支持 JPG, PNG, WEBP<br />最大文件 5MB
                      </div>
                    </div>
                    <div class="media-actions">
                      <button
                        class="btn-upload"
                        type="button"
                        @click="triggerBackgroundUpload"
                      >
                        上传
                      </button>
                      <button
                        v-if="store.backgroundImage"
                        class="btn-clear"
                        type="button"
                        @click="clearBackground"
                      >
                        清除
                      </button>
                    </div>
                    <input
                      ref="backgroundUploader"
                      type="file"
                      accept="image/png,image/jpeg,image/webp,image/gif"
                      class="background-input"
                      @change="handleBackgroundUpload"
                    />
                  </div>
                </div>

                <div class="panel-section last-section">
                  <div class="media-upload-card">
                    <div class="media-info">
                      <div class="media-title">自定义 AI 头像</div>
                      <div class="media-desc">自定义机器人的消息头像</div>
                      <div v-if="store.aiAvatar" class="media-preview-avatar">
                        <img :src="store.aiAvatar" alt="AI头像" />
                      </div>
                    </div>
                    <div class="media-actions">
                      <button
                        class="btn-upload"
                        type="button"
                        @click="triggerAiAvatarUpload"
                      >
                        上传
                      </button>
                      <button
                        v-if="store.aiAvatar"
                        class="btn-clear"
                        type="button"
                        @click="clearAiAvatar"
                      >
                        清除
                      </button>
                    </div>
                    <input
                      ref="aiAvatarUploader"
                      type="file"
                      accept="image/png,image/jpeg,image/webp,image/gif"
                      class="background-input"
                      @change="handleAiAvatarUpload"
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </transition>
      </div>

      <button
        id="newConversation"
        class="btn-ghost"
        type="button"
        @click="handleNewChat"
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <line x1="12" y1="5" x2="12" y2="19"></line>
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
        新对话
      </button>
    </div>
  </header>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useUiStore } from "../store/index";

const store = useUiStore();
const isDark = computed(() => store.darkMode);
const showAppearancePanel = ref(false);
const activeTab = ref("theme");
const appearanceAnchor = ref(null);
const appearancePanel = ref(null);
const backgroundUploader = ref(null);
const backgroundBlur = ref(store.backgroundBlur ?? 4);
const backgroundOpacity = ref(store.backgroundOpacity ?? 1);
const borderContrast = ref(store.borderContrast ?? 100);
const MAX_BACKGROUND_SIZE = 5 * 1024 * 1024;
let blurCommitRafId = 0;
let opacityCommitRafId = 0;
let borderContrastCommitRafId = 0;
let queuedBackgroundBlur = Number.isFinite(Number(store.backgroundBlur))
  ? Number(store.backgroundBlur)
  : 4;
let queuedBackgroundOpacity = Number.isFinite(Number(store.backgroundOpacity))
  ? Number(store.backgroundOpacity)
  : 1;
let queuedBorderContrast = Number.isFinite(Number(store.borderContrast))
  ? Number(store.borderContrast)
  : 100;

const themeOptions = [
  {
    name: "deep-space",
    label: "夜蓝",
    preview: "linear-gradient(135deg,#334155,#2563eb)",
  },
  {
    name: "ink-gold",
    label: "纸墨",
    preview: "linear-gradient(135deg,#a16207,#c2410c)",
  },
  {
    name: "pine-night",
    label: "松雾",
    preview: "linear-gradient(135deg,#0f766e,#16a34a)",
  },
  {
    name: "cyber-violet",
    label: "暮紫",
    preview: "linear-gradient(135deg,#6d28d9,#2563eb)",
  },
];

const bubbleSkinOptions = [
  {
    name: "default",
    label: "默认",
    preview: "linear-gradient(135deg,#94a3b8,#64748b)",
  },
  {
    name: "helloKitty",
    label: "凯蒂",
    preview: "linear-gradient(135deg,#ffe4ec,#ffb6c1)",
  },
  {
    name: "cat",
    label: "小猫",
    preview: "linear-gradient(135deg,#d4a574,#b8834a)",
  },
  {
    name: "dog",
    label: "小狗",
    preview: "linear-gradient(135deg,#7ba7c9,#5a8ab0)",
  },
  {
    name: "fish",
    label: "小鱼",
    preview: "linear-gradient(135deg,#5fa8a0,#3d8a80)",
  },
];

function handleNewChat() {
  showAppearancePanel.value = false;
  store.newConversation();
  // Ensure input is focused after starting a new conversation so send remains responsive
  setTimeout(() => {
    try {
      const el = document.getElementById("message");
      if (el && typeof el.focus === "function") el.focus();
    } catch (e) {
      // ignore
    }
  }, 0);
}

function toggleThemeLocally() {
  store.toggleDarkMode();
}

function toggleBotBubble() {
  store.setBotBubbleEnabled(!store.botBubbleEnabled);
}

function toggleAppearancePanel() {
  showAppearancePanel.value = !showAppearancePanel.value;
}

function changeTheme(themeName) {
  store.setTheme(themeName);
}

function triggerBackgroundUpload() {
  if (!backgroundUploader.value) return;
  backgroundUploader.value.value = "";
  backgroundUploader.value.click();
}

function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === "string") {
        resolve(reader.result);
      } else {
        reject(new Error("读取图片失败"));
      }
    };
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.readAsDataURL(file);
  });
}

async function compressImageForStorage(file) {
  const maxSide = 1920;

  if (typeof createImageBitmap !== "function") {
    return fileToDataUrl(file);
  }

  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, maxSide / bitmap.width, maxSide / bitmap.height);
  const targetWidth = Math.max(1, Math.round(bitmap.width * scale));
  const targetHeight = Math.max(1, Math.round(bitmap.height * scale));

  const canvas = document.createElement("canvas");
  canvas.width = targetWidth;
  canvas.height = targetHeight;

  const ctx = canvas.getContext("2d", { alpha: false });
  if (!ctx) {
    bitmap.close?.();
    return fileToDataUrl(file);
  }

  ctx.drawImage(bitmap, 0, 0, targetWidth, targetHeight);
  bitmap.close?.();

  return canvas.toDataURL("image/webp", 0.82);
}

async function handleBackgroundUpload(event) {
  const file = event.target.files?.[0];
  if (!file) return;

  if (!file.type.startsWith("image/")) {
    alert("请选择图片文件");
    return;
  }

  if (file.size > MAX_BACKGROUND_SIZE) {
    alert("图片请控制在 5MB 以内");
    event.target.value = "";
    return;
  }

  try {
    const dataUrl =
      file.size > 1.2 * 1024 * 1024
        ? await compressImageForStorage(file)
        : await fileToDataUrl(file);

    const saved = store.setBackgroundImage(dataUrl);
    if (!saved) {
      alert("图片已超出浏览器本地存储上限，请换更小图片");
      return;
    }
  } catch (err) {
    console.error("背景图片处理失败", err);
    alert("图片处理失败，请重试");
  } finally {
    event.target.value = "";
  }
}

function clearBackground() {
  store.clearBackgroundImage();
}

const aiAvatarUploader = ref(null);
const MAX_AVATAR_SIZE = 2 * 1024 * 1024;

function triggerAiAvatarUpload() {
  if (!aiAvatarUploader.value) return;
  aiAvatarUploader.value.value = "";
  aiAvatarUploader.value.click();
}

async function handleAiAvatarUpload(event) {
  const file = event.target?.files?.[0];
  if (!file) return;
  if (file.size > MAX_AVATAR_SIZE) {
    alert("头像图片不能超过 2MB");
    event.target.value = "";
    return;
  }
  try {
    const dataUrl = await fileToDataUrl(file);
    store.setAiAvatar(dataUrl);
  } catch (err) {
    console.error("AI头像处理失败", err);
    alert("头像处理失败，请重试");
  } finally {
    event.target.value = "";
  }
}

function clearAiAvatar() {
  store.setAiAvatar("");
}

function handleBackgroundBlurChange(event) {
  const value = Number(event.target.value);
  backgroundBlur.value = Number.isFinite(value) ? value : 0;
  queuedBackgroundBlur = backgroundBlur.value;

  if (blurCommitRafId) {
    return;
  }

  blurCommitRafId = requestAnimationFrame(() => {
    blurCommitRafId = 0;
    store.setBackgroundBlur(queuedBackgroundBlur);
  });
}

function flushBackgroundBlurChange() {
  if (blurCommitRafId) {
    cancelAnimationFrame(blurCommitRafId);
    blurCommitRafId = 0;
  }
  store.setBackgroundBlur(queuedBackgroundBlur);
}

function handleBackgroundOpacityChange(event) {
  const value = Number(event.target.value) / 100;
  backgroundOpacity.value = Number.isFinite(value) ? value : 1;
  queuedBackgroundOpacity = backgroundOpacity.value;

  if (opacityCommitRafId) {
    return;
  }

  opacityCommitRafId = requestAnimationFrame(() => {
    opacityCommitRafId = 0;
    store.setBackgroundOpacity(queuedBackgroundOpacity);
  });
}

function flushBackgroundOpacityChange() {
  if (opacityCommitRafId) {
    cancelAnimationFrame(opacityCommitRafId);
    opacityCommitRafId = 0;
  }
  store.setBackgroundOpacity(queuedBackgroundOpacity);
}

function handleBorderContrastChange(event) {
  const value = Number(event.target.value);
  borderContrast.value = Number.isFinite(value) ? value : 100;
  queuedBorderContrast = borderContrast.value;

  if (borderContrastCommitRafId) {
    return;
  }

  borderContrastCommitRafId = requestAnimationFrame(() => {
    borderContrastCommitRafId = 0;
    store.setBorderContrast(queuedBorderContrast);
  });
}

function flushBorderContrastChange() {
  if (borderContrastCommitRafId) {
    cancelAnimationFrame(borderContrastCommitRafId);
    borderContrastCommitRafId = 0;
  }
  store.setBorderContrast(queuedBorderContrast);
}

const primaryPresets = [
  { color: "" },
  { color: "#2563eb" },
  { color: "#c2410c" },
  { color: "#16a34a" },
  { color: "#6d28d9" },
  { color: "#ef4444" },
  { color: "#ec4899" },
  { color: "#06b6d4" },
  { color: "#f59e0b" },
];

const borderPresets = [
  { color: "" },
  { color: "#475569" },
  { color: "#64748b" },
  { color: "#94a3b8" },
  { color: "#4b5563" },
  { color: "#6b7280" },
  { color: "#78716c" },
  { color: "#5b6b7d" },
  { color: "#3f4a5a" },
];

const hasCustomColors = computed(
  () => !!(store.customPrimary || store.customBorder),
);

function setPrimaryColor(color) {
  store.setCustomPrimary(color || "");
}

function setBorderColor(color) {
  store.setCustomBorder(color || "");
}

function resetCustomColors() {
  store.setCustomPrimary("");
  store.setCustomBorder("");
}

function handleGlobalClick(event) {
  if (!showAppearancePanel.value) return;

  const anchor = appearanceAnchor.value;
  if (anchor && !anchor.contains(event.target)) {
    showAppearancePanel.value = false;
  }
}

watch(
  () => store.backgroundBlur,
  (newVal) => {
    backgroundBlur.value = Number.isFinite(Number(newVal)) ? Number(newVal) : 4;
  },
);
watch(
  () => store.backgroundOpacity,
  (newVal) => {
    backgroundOpacity.value = Number.isFinite(Number(newVal))
      ? Number(newVal)
      : 1;
  },
);
watch(
  () => store.borderContrast,
  (newVal) => {
    borderContrast.value = Number.isFinite(Number(newVal))
      ? Number(newVal)
      : 100;
  },
);

onMounted(() => {
  document.addEventListener("click", handleGlobalClick);
});

onUnmounted(() => {
  document.removeEventListener("click", handleGlobalClick);

  if (blurCommitRafId) {
    cancelAnimationFrame(blurCommitRafId);
    blurCommitRafId = 0;
  }
  if (opacityCommitRafId) {
    cancelAnimationFrame(opacityCommitRafId);
    opacityCommitRafId = 0;
  }
  if (borderContrastCommitRafId) {
    cancelAnimationFrame(borderContrastCommitRafId);
    borderContrastCommitRafId = 0;
  }
});
</script>

<style scoped>
.appearance-anchor {
  position: relative;
  z-index: 30;
}

.appearance-trigger,
.dark-quick-toggle,
.mini-close,
.theme-chip,
.style-chip,
.panel-btn {
  transition:
    transform var(--feedback-fast, 140ms)
      var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
    background-color var(--feedback-fast, 140ms)
      var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
    border-color var(--feedback-fast, 140ms)
      var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
    color var(--feedback-fast, 140ms)
      var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
    box-shadow var(--feedback-medium, 170ms)
      var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1));
}

.appearance-trigger {
  position: relative;
}

.appearance-trigger:hover,
.dark-quick-toggle:hover,
.theme-chip:hover,
.style-chip:hover,
.panel-btn:hover,
.mini-close:hover {
  transform: translateY(-1px);
}

.theme-chip:active,
.style-chip:active,
.panel-btn:active,
.mini-close:active,
.appearance-trigger:active,
.dark-quick-toggle:active {
  transform: translateY(0);
}

.dark-quick-toggle.active {
  color: #f8fafc;
  border-color: color-mix(in srgb, var(--primary) 56%, var(--panel-border));
  background: color-mix(in srgb, var(--primary) 28%, var(--surface-soft));
  box-shadow: 0 8px 18px color-mix(in srgb, var(--primary) 24%, transparent);
}

.appearance-panel {
  position: absolute;
  right: 0;
  top: calc(100% + 10px);
  width: 360px;
  max-height: none;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  border-radius: 20px;
  border: 1px solid var(--panel-border);
  background: color-mix(in srgb, var(--overlay-strong) 92%, var(--bg-base));
  box-shadow:
    0 30px 64px rgba(5, 12, 24, 0.28),
    0 2px 0 rgba(255, 255, 255, 0.08) inset;
  backdrop-filter: blur(20px) saturate(120%);
  -webkit-backdrop-filter: blur(20px) saturate(120%);
  z-index: 120;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 0;
}

.panel-head h4 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
}

.panel-tabs {
  display: flex;
  gap: 4px;
  padding: 12px 20px;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 40%, transparent);
}

.panel-tab-btn {
  flex: 1;
  background: transparent;
  border: none;
  border-radius: 8px;
  padding: 6px 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s ease;
}

.panel-tab-btn:hover {
  background: var(--hover-bg);
  color: var(--text-main);
}

.panel-tab-btn.active {
  background: var(--glass-strong);
  color: var(--primary);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.panel-content-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  max-height: 520px;
}

.panel-section {
  padding-bottom: 20px;
  margin-bottom: 20px;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 40%, transparent);
}

.panel-section.last-section {
  padding-bottom: 4px;
  margin-bottom: 0;
  border-bottom: none;
}

.section-title {
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
}

.skin-grid-modern {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.skin-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 14px 8px;
  border: 1.5px solid var(--panel-border);
  border-radius: 14px;
  background: var(--glass-soft);
  cursor: pointer;
  transition: all 0.2s;
}

.skin-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 16px color-mix(in srgb, var(--primary) 10%, transparent);
  background: color-mix(in srgb, var(--hover-bg) 60%, var(--glass-soft));
}

.skin-card.active {
  border-color: var(--primary);
  background: color-mix(in srgb, var(--primary) 6%, var(--glass-soft));
  box-shadow: 0 4px 12px color-mix(in srgb, var(--primary) 14%, transparent);
}

.skin-card-preview {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.06);
  border: 1px solid color-mix(in srgb, var(--panel-border) 50%, white);
}

.skin-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-main);
}

.bubble-row-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.bubble-row-title {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text-main);
}

.bubble-row-desc {
  font-size: 11px;
  color: var(--text-muted);
}

.media-upload-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 12px;
  background: var(--glass-soft);
  border: 1px solid var(--panel-border);
}

.media-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.media-title {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text-main);
}

.media-desc {
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--text-muted);
}

.media-actions {
  display: flex;
  gap: 8px;
}

.btn-upload,
.btn-clear {
  flex: 1;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-upload {
  background: var(--primary);
  color: white;
  border-color: transparent;
}

.btn-upload:hover {
  background: var(--primary-hover);
  box-shadow: 0 6px 12px color-mix(in srgb, var(--primary) 24%, transparent);
}

.btn-clear {
  background: transparent;
  color: #dc2626;
  border-color: color-mix(in srgb, #dc2626 50%, var(--panel-border));
}

.btn-clear:hover {
  background: color-mix(in srgb, #dc2626 10%, transparent);
}

.media-preview-avatar {
  margin-top: 8px;
  display: flex;
}

.media-preview-avatar img {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 50%;
  border: 2px solid var(--panel-border);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.mini-close {
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 8px;
  background: var(--glass-soft);
  color: var(--text-muted);
  cursor: pointer;
}

.mini-close:hover {
  color: var(--text-main);
  background: var(--hover-bg);
}

.appearance-line {
  width: 100%;
  border-radius: 10px;
  border: 1px solid var(--panel-border);
  background: var(--glass-soft);
  color: var(--text-main);
  padding: 8px 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
}

.appearance-line strong {
  color: var(--primary);
  font-size: 12px;
}

.section-title {
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
}

.panel-section {
  padding-bottom: 18px;
  margin-bottom: 18px;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 40%, transparent);
}
.panel-section:first-child {
  padding-top: 0;
}

.theme-grid,
.style-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.theme-chip,
.style-chip {
  width: 100%;
  border: 1px solid var(--panel-border);
  background: transparent;
  color: var(--text-main);
  border-radius: 12px;
  padding: 12px;
  text-align: left;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  min-height: 46px;
}

.theme-chip:hover,
.style-chip:hover {
  background: var(--hover-bg);
}

.theme-chip.active,
.style-chip.active {
  border-color: color-mix(in srgb, var(--primary) 50%, var(--panel-border));
  background: color-mix(in srgb, var(--primary) 16%, transparent);
}

.toggle-switch {
  width: 32px;
  height: 18px;
  border-radius: 12px;
  background: color-mix(in srgb, var(--panel-border) 60%, transparent);
  position: relative;
  transition: background-color 0.2s;
  flex-shrink: 0;
}
.toggle-switch.is-active {
  background: var(--primary);
}
.toggle-knob {
  position: absolute;
  left: 2px;
  top: 2px;
  width: 14px;
  height: 14px;
  background: #fff;
  border-radius: 50%;
  transition: transform 0.2s cubic-bezier(0.22, 1, 0.36, 1);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.15);
}
.toggle-switch.is-active .toggle-knob {
  transform: translateX(14px);
}

.bot-bubble-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--panel-border);
  background: var(--glass-soft);
  transition: background-color 0.15s;
}
.bot-bubble-row:hover {
  background: var(--hover-bg);
}
.bot-bubble-row span {
  font-size: 13px;
  color: var(--text-main);
}

.chip-dot {
  width: 12px;
  height: 12px;
  border-radius: 999px;
  flex-shrink: 0;
}

.blur-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.blur-title strong {
  color: var(--text-main);
  font-size: 12px;
}

.blur-slider {
  width: 100%;
  accent-color: var(--primary);
  cursor: pointer;
}

.panel-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}

.panel-btn {
  flex: 1;
  border: 1px solid var(--panel-border);
  border-radius: 10px;
  padding: 9px 10px;
  background: var(--glass-soft);
  color: var(--text-main);
  font-size: 13px;
  cursor: pointer;
}

.panel-btn:hover {
  background: var(--hover-bg);
  box-shadow: 0 8px 16px color-mix(in srgb, var(--primary) 18%, transparent);
}

.panel-btn.danger {
  color: #dc2626;
  border-color: color-mix(in srgb, #dc2626 48%, var(--panel-border));
}

.background-input {
  display: none;
}

.ai-avatar-preview {
  margin-top: 8px;
  display: flex;
  justify-content: center;
}
.ai-avatar-preview-img {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--panel-border);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.panel-fade-enter-active,
.panel-fade-leave-active {
  transition:
    opacity var(--motion-fast, 0.18s) var(--motion-ease, ease),
    transform var(--motion-fast, 0.18s) var(--motion-ease, ease);
}

.panel-fade-enter-from,
.panel-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.985);
}

@media (max-width: 768px) {
  .appearance-panel {
    width: min(92vw, 356px);
    right: -4px;
    padding: 14px;
  }
}

.color-reset {
  font-size: 12px;
  cursor: pointer;
  color: var(--primary);
  user-select: none;
}
.color-reset:hover {
  text-decoration: underline;
}

.color-group {
  margin-top: 10px;
}
.color-group:first-child {
  margin-top: 0;
}
.color-group-label {
  font-size: 11px;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.color-swatches {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.swatch-btn {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 1.5px solid var(--panel-border);
  background: var(--glass-soft);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  transition:
    border-color 0.15s,
    transform 0.12s;
}
.swatch-btn:hover {
  transform: translateY(-1px);
  border-color: var(--primary);
}
.swatch-btn.active {
  border-color: var(--primary);
  box-shadow: 0 0 0 1.5px var(--primary);
}

.swatch-dot {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  display: block;
}

.swatch-label {
  font-size: 10px;
  color: var(--text-muted);
  line-height: 1;
}

.swatch-plus {
  border-style: dashed;
  border-color: color-mix(in srgb, var(--panel-border) 60%, transparent);
}
.swatch-plus:hover {
  border-style: solid;
}

.color-input-hidden {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

/* Two-column row */
.two-col-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.two-col-cell {
  min-width: 0;
}

/* Slider group */
.slider-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.slider-row {
  display: grid;
  grid-template-columns: 40px 1fr 40px;
  align-items: center;
  gap: 12px;
}
.slider-label {
  font-size: 13px;
  color: var(--text-muted);
  text-align: right;
}
.slider-input {
  width: 100%;
  accent-color: var(--primary);
  cursor: pointer;
  margin: 0;
  height: 4px;
}
.slider-value {
  font-size: 12px;
  color: var(--text-main);
  text-align: right;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* Inline button group */
.inline-btn-group {
  display: flex;
  gap: 6px;
}
.panel-btn-sm {
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  padding: 7px 14px;
  background: var(--glass-soft);
  color: var(--text-main);
  font-size: 12px;
  cursor: pointer;
  line-height: 1;
  transition:
    background-color 0.15s,
    border-color 0.15s;
}
.panel-btn-sm:hover {
  background: var(--hover-bg);
}
.panel-btn-sm.danger {
  color: #dc2626;
  border-color: color-mix(in srgb, #dc2626 48%, var(--panel-border));
}

/* Compact AI avatar preview */
.ai-avatar-preview-compact {
  margin-top: 6px;
  display: flex;
  justify-content: center;
}
.ai-avatar-thumb {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--panel-border);
}

/* Skin selector grid */
.skin-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
}
.skin-chip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px 6px 10px;
  border: 1.5px solid var(--panel-border);
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background-color 0.15s,
    transform 0.12s;
}
.skin-chip:hover {
  transform: translateY(-1px);
  background: var(--hover-bg);
}
.skin-chip.active {
  border-color: color-mix(in srgb, var(--primary) 50%, var(--panel-border));
  background: color-mix(in srgb, var(--primary) 16%, transparent);
}
.skin-preview {
  width: 34px;
  height: 20px;
  border-radius: 6px;
  border: 1px solid var(--panel-border);
  flex-shrink: 0;
}
.skin-label {
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1;
}
</style>
