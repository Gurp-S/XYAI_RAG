<template>
  <Teleport to="body">
    <div class="user-center-container">
      <transition name="uc-pop">
        <section v-if="isOpen" v-click-outside="close" class="user-popup-panel">
          <!-- ── Header ── -->
          <header class="uc-header">
            <div class="uc-header-inner">
              <div class="uc-avatar-shell">
                <div class="uc-avatar">
                  <template
                    v-if="store.currentUser && store.currentUser.avatar"
                  >
                    <img
                      :src="store.currentUser.avatar"
                      alt="avatar"
                      class="uc-avatar-img"
                    />
                  </template>
                  <template v-else>{{ store.userAvatarText }}</template>
                </div>
                <span class="uc-online-dot"></span>
              </div>
              <h2 class="uc-user-name">{{ store.userDisplayName }}</h2>
              <p class="uc-user-meta">
                ID {{ store.currentUser?.id || "-" }} · {{ currentRoleLabel }}
              </p>
            </div>
            <button
              class="uc-close"
              type="button"
              aria-label="关闭"
              @click="close"
            >
              ×
            </button>
          </header>

          <!-- ── Tabs ── -->
          <div class="uc-tabs">
            <button
              class="uc-tab"
              :class="{ active: activeTab === 'friends' }"
              @click="
                activeTab = 'friends';
                drawerOpen = false;
              "
            >
              好友
            </button>
            <button
              class="uc-tab"
              :class="{ active: activeTab === 'profile' }"
              @click="
                activeTab = 'profile';
                drawerOpen = false;
              "
            >
              资料
            </button>
          </div>

          <!-- ── Body ── -->
          <div class="uc-body" :class="{ 'drawer-active': drawerOpen }">
            <!-- ====== 好友 Tab ====== -->
            <div v-if="activeTab === 'friends'" class="uc-friends">
              <!-- 小组卡片（嵌入好友列表顶部的抽屉触发器） -->
              <div
                v-if="group"
                class="uc-group-trigger"
                @click="drawerOpen = !drawerOpen"
              >
                <div
                  class="uc-group-trigger-avatar"
                  :style="avatarStyleByText(group.groupId || 'G')"
                >
                  <svg
                    viewBox="0 0 24 24"
                    width="18"
                    height="18"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                  >
                    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                    <circle cx="9" cy="7" r="4" />
                    <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                    <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                  </svg>
                </div>
                <div class="uc-group-trigger-info">
                  <div class="uc-group-trigger-name">
                    {{ group.groupId || "我的小组" }}
                  </div>
                  <div class="uc-group-trigger-count">
                    {{ group.group ? group.group.length : 0 }} 位成员
                  </div>
                </div>
                <svg
                  class="uc-group-trigger-chevron"
                  viewBox="0 0 24 24"
                  width="20"
                  height="20"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                >
                  <polyline points="9 18 15 12 9 6" />
                </svg>
              </div>

              <!-- 好友列表 -->
              <div v-show="!drawerOpen" class="uc-friend-list">
                <div class="uc-section-label">{{ friends.length }} 位好友</div>
                <article
                  v-for="friend in friends"
                  :key="friend.id || friend.userId || friend.name"
                  class="uc-friend-card"
                >
                  <div class="uc-friend-avatar" :style="avatarStyleFor(friend)">
                    <template v-if="friend && friend.avatar">
                      <img
                        :src="friend.avatar"
                        alt=""
                        class="uc-friend-avatar-img"
                      />
                    </template>
                    <template v-else>{{ getAvatar(friend) }}</template>
                    <span class="uc-friend-dot"></span>
                  </div>
                  <div class="uc-friend-info" @click="startUserChat(friend)">
                    <div class="uc-friend-name">
                      {{ getDisplayName(friend) }}
                    </div>
                    <div class="uc-friend-status">
                      {{ friend.email || "在线" }}
                    </div>
                  </div>
                  <button
                    class="uc-friend-chat-btn"
                    title="聊天"
                    @click="startUserChat(friend)"
                  >
                    <svg
                      viewBox="0 0 24 24"
                      width="16"
                      height="16"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                    >
                      <path
                        d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                      />
                    </svg>
                  </button>
                  <button
                    class="uc-friend-del-btn"
                    title="删除"
                    @click="confirmDeleteFriend(friend)"
                  >
                    <svg
                      viewBox="0 0 24 24"
                      width="14"
                      height="14"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                    >
                      <polyline points="3 6 5 6 21 6" />
                      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
                      <path d="M10 11v6" />
                      <path d="M14 11v6" />
                    </svg>
                  </button>
                </article>
                <div v-if="friends.length === 0" class="uc-empty">
                  <svg
                    viewBox="0 0 24 24"
                    width="36"
                    height="36"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.5"
                    opacity="0.3"
                  >
                    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                    <circle cx="9" cy="7" r="4" />
                    <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                    <path d="M16 3.13a4 4 0 0 1 0 7.75" />
                  </svg>
                  <p>暂无好友</p>
                </div>
              </div>

              <!-- ====== 抽屉：小组成员 ====== -->
              <transition name="uc-drawer">
                <div v-if="drawerOpen" class="uc-drawer">
                  <div class="uc-drawer-head" @click="drawerOpen = false">
                    <svg
                      viewBox="0 0 24 24"
                      width="20"
                      height="20"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                    >
                      <polyline points="15 18 9 12 15 6" />
                    </svg>
                    <span>{{ group?.groupId || "小组" }}</span>
                  </div>
                  <div class="uc-drawer-body">
                    <div class="uc-section-label">
                      群成员 · {{ group?.group?.length || 0 }} 人
                    </div>
                    <div v-if="group?.group?.length" class="uc-drawer-members">
                      <div
                        v-for="member in group.group"
                        :key="member.id || member.userId || member.name"
                        class="uc-drawer-member"
                        @click="startUserChat(member)"
                      >
                        <div
                          class="uc-drawer-member-avatar"
                          :style="avatarStyleFor(member)"
                        >
                          <template v-if="member && member.avatar"
                            ><img
                              :src="member.avatar"
                              alt=""
                              class="uc-friend-avatar-img"
                          /></template>
                          <template v-else>{{ getAvatar(member) }}</template>
                        </div>
                        <div class="uc-drawer-member-info">
                          <div class="uc-drawer-member-name">
                            {{ getDisplayName(member) }}
                          </div>
                          <div class="uc-drawer-member-role">
                            {{ member.userRank === 0 ? "管理员" : "成员" }}
                          </div>
                        </div>
                        <button
                          class="uc-drawer-chat-btn"
                          title="私聊"
                          @click.stop="startUserChat(member)"
                        >
                          <svg
                            viewBox="0 0 24 24"
                            width="15"
                            height="15"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2"
                            stroke-linecap="round"
                          >
                            <path
                              d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                            />
                          </svg>
                        </button>
                      </div>
                    </div>
                    <div v-else class="uc-empty"><p>暂无成员</p></div>
                    <button
                      class="uc-drawer-group-chat"
                      @click="startGroupChat(group)"
                    >
                      <svg
                        viewBox="0 0 24 24"
                        width="16"
                        height="16"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                      >
                        <path
                          d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                        />
                      </svg>
                      进入群聊
                    </button>
                  </div>
                </div>
              </transition>
            </div>

            <!-- ====== 资料 Tab ====== -->
            <div v-if="activeTab === 'profile'" class="uc-profile">
              <div class="uc-card">
                <div class="uc-card-title">基础信息</div>
                <form class="uc-form" @submit.prevent="submitProfileUpdate">
                  <div class="uc-field">
                    <label class="uc-field-label">昵称</label>
                    <input
                      v-model="profileForm.name"
                      class="uc-input"
                      type="text"
                      placeholder="输入昵称"
                    />
                  </div>
                  <div class="uc-field">
                    <label class="uc-field-label">头像 URL</label>
                    <input
                      v-model="profileForm.avatar"
                      class="uc-input"
                      type="text"
                      placeholder="输入头像链接"
                    />
                  </div>
                </form>
              </div>

              <div class="uc-card">
                <div class="uc-card-title">账号信息</div>
                <div class="uc-form">
                  <div class="uc-field">
                    <label class="uc-field-label">邮箱</label>
                    <input
                      v-model="profileForm.email"
                      class="uc-input"
                      type="email"
                      placeholder="未设置"
                    />
                  </div>
                  <div class="uc-field">
                    <label class="uc-field-label">手机号</label>
                    <input
                      v-model="profileForm.phone"
                      class="uc-input"
                      type="tel"
                      placeholder="未设置"
                    />
                  </div>
                  <details class="uc-details">
                    <summary class="uc-details-summary">查看更多</summary>
                    <div class="uc-field uc-field-readonly">
                      <label class="uc-field-label">用户 ID</label>
                      <span class="uc-field-value">{{
                        profileForm.id || "-"
                      }}</span>
                    </div>
                    <div class="uc-field uc-field-readonly">
                      <label class="uc-field-label">组 ID</label>
                      <span class="uc-field-value">{{
                        profileForm.groupId || "-"
                      }}</span>
                    </div>
                  </details>
                </div>
              </div>

              <p v-if="profileHint" class="uc-hint">{{ profileHint }}</p>

              <div class="uc-actions">
                <button
                  class="uc-btn uc-btn-primary"
                  type="submit"
                  :disabled="profileSaving"
                  @click="submitProfileUpdate"
                >
                  {{ profileSaving ? "保存中…" : "保存资料" }}
                </button>
                <button
                  class="uc-btn uc-btn-ghost"
                  type="button"
                  @click="syncProfileForm()"
                >
                  重置
                </button>
              </div>

              <p v-if="profileSuccess" class="uc-msg success">
                {{ profileSuccess }}
              </p>
              <p v-if="profileError" class="uc-msg error">{{ profileError }}</p>
            </div>
          </div>
        </section>
      </transition>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useUiStore } from "../store/index";
import { authFetch, safeReadJson } from "../services/api";

const props = defineProps({
  isOpen: {
    type: Boolean,
    default: false,
  },
});
const emit = defineEmits(["close"]);

const store = useUiStore();
const router = useRouter();
const friends = ref([]);
const group = ref(null);
const activeTab = ref("friends");
const drawerOpen = ref(false);
const profileSaving = ref(false);
const profileError = ref("");
const profileSuccess = ref("");
const profileForm = reactive({
  id: "",
  name: "",
  avatar: "",
  email: "",
  phone: "",
  groupId: "",
});

const currentRoleLabel = computed(() => {
  const rank = Number(store.currentUser?.userRank);
  if (rank === 0) return "管理员";
  if (rank === 1) return "组长";
  return "成员";
});

const profileHint = computed(() => {
  const idSet = Boolean(profileForm.id || store.currentUser?.id);
  const groupIdSet = Boolean(profileForm.groupId || store.currentUser?.groupId);
  if (idSet || groupIdSet) return "";
  return "当前仅开放基础资料编辑，修改密码保持原样。";
});

const gradientPool = [
  "linear-gradient(135deg,#cbd5e1,#94a3b8)",
  "linear-gradient(135deg,#dbeafe,#93c5fd)",
  "linear-gradient(135deg,#dcfce7,#86efac)",
  "linear-gradient(135deg,#fef3c7,#fcd34d)",
];

const vClickOutside = {
  mounted(el, binding) {
    el.clickOutsideEvent = function (event) {
      if (!(el === event.target || el.contains(event.target))) {
        binding.value(event);
      }
    };
    setTimeout(() => {
      document.addEventListener("click", el.clickOutsideEvent);
    }, 0);
  },
  unmounted(el) {
    document.removeEventListener("click", el.clickOutsideEvent);
  },
};

watch(
  () => props.isOpen,
  (newVal) => {
    if (newVal && store.currentUser) {
      activeTab.value = "friends";
      drawerOpen.value = false;
      syncProfileForm(store.currentUser);
      fetchUserData();
    }
  },
);

watch(
  () => store.currentUser,
  (user) => {
    syncProfileForm(user);
  },
  { immediate: true },
);

function syncProfileForm(user = store.currentUser) {
  profileForm.id = String(user?.id || "");
  profileForm.name = String(user?.name || "");
  profileForm.avatar = String(user?.avatar || "");
  profileForm.email = String(user?.email || "");
  profileForm.phone = String(user?.phone || "");
  profileForm.groupId = String(user?.groupId || "");
  profileError.value = "";
  profileSuccess.value = "";
}

async function fetchUserData() {
  if (!store.currentUser?.id) return;

  try {
    const [friendsRes, groupRes] = await Promise.all([
      authFetch("/user/friend", { method: "GET" }).then((r) => safeReadJson(r)),
      authFetch("/user/group", { method: "GET" }).then((r) => safeReadJson(r)),
    ]);

    if (friendsRes && friendsRes.code === 200) {
      friends.value = Array.isArray(friendsRes.data) ? friendsRes.data : [];
    }
    if (groupRes && groupRes.code === 200) {
      group.value = groupRes.data || null;
    }
  } catch {
    return;
  }
}

async function submitProfileUpdate() {
  if (!store.currentUser?.id) {
    profileError.value = "当前未登录";
    return;
  }

  profileSaving.value = true;
  profileError.value = "";
  profileSuccess.value = "";

  try {
    const formData = new FormData();
    formData.append("id", String(store.currentUser.id));

    const name = String(profileForm.name || "").trim();
    const avatar = String(profileForm.avatar || "").trim();
    const email = String(profileForm.email || "").trim();
    const phone = String(profileForm.phone || "").trim();
    const groupId = String(profileForm.groupId || "").trim();

    if (name) formData.append("name", name);
    if (avatar) formData.append("avatar", avatar);
    if (email) formData.append("email", email);
    if (phone) formData.append("phone", phone);
    if (groupId) formData.append("groupId", groupId);

    const response = await authFetch("/user/update", {
      method: "POST",
      body: formData,
    });
    const result = await safeReadJson(response);
    if (!response.ok || !result || result.code !== 200) {
      profileError.value = result?.msg || "保存失败";
      return;
    }

    const refreshed = await store.fetchCurrentUserInfo();
    if (refreshed) {
      store.setUser(refreshed);
      syncProfileForm(refreshed);
    } else {
      store.setUser({
        ...store.currentUser,
        name: name || store.currentUser.name,
        avatar: avatar || store.currentUser.avatar,
        email: email || store.currentUser.email,
        phone: phone || store.currentUser.phone,
        groupId: groupId || store.currentUser.groupId,
      });
      syncProfileForm(store.currentUser);
    }

    profileSuccess.value = "资料已更新";
    await fetchUserData();
  } catch {
    profileError.value = "保存失败，请稍后重试";
  } finally {
    profileSaving.value = false;
  }
}

function getUserId(user) {
  return user?.id || user?.userId || user?.uid || "-";
}

function getDisplayName(user) {
  return (
    user?.name ||
    user?.nickname ||
    user?.userName ||
    user?.username ||
    getUserId(user)
  );
}

function getAvatar(user, len = 1) {
  return String(getDisplayName(user) || "?")
    .substring(0, len)
    .toUpperCase();
}

function hashText(input) {
  const text = String(input || "");
  let hash = 0;
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash << 5) - hash + text.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

function avatarStyleByText(text) {
  const index = hashText(text) % gradientPool.length;
  return { background: gradientPool[index] };
}

function avatarStyleFor(user) {
  return avatarStyleByText(getDisplayName(user));
}

function close() {
  emit("close");
}

function startUserChat(user) {
  if (!user) return;
  store.openContactChat("user", user);
  emit("close");
  router.push("/");
}

function startGroupChat(groupInfo) {
  if (!groupInfo) return;
  store.openContactChat("group", {
    id: groupInfo.groupId || groupInfo.id,
    name: groupInfo.groupId || "未命名组",
  });
  emit("close");
  router.push("/");
}

async function confirmDeleteFriend(friend) {
  const friendId = getUserId(friend);
  const friendName = getDisplayName(friend);
  if (!confirm(`确定要删除好友 ${friendName} 吗？`)) return;

  try {
    const res = await authFetch(
      `/user/friend/delete?friendId=${encodeURIComponent(friendId)}`,
      { method: "POST" },
    );
    const result = await safeReadJson(res);
    if (result && result.code === 200) {
      fetchUserData();
    }
  } catch {
    return;
  }
}
</script>

<style scoped>
/* ─── Container ─── */
.user-center-container {
  position: relative;
  z-index: 10000;
}

.user-popup-panel {
  position: fixed;
  left: 20px;
  bottom: 88px;
  width: min(400px, calc(100vw - 24px));
  height: min(660px, calc(100vh - 116px));
  border-radius: var(--panel-radius);
  border: 1px solid var(--panel-border);
  background: var(--overlay-strong);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ─── Header ─── */
.uc-header {
  position: relative;
  padding: 16px 20px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 50%, transparent);
}
.uc-header-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.uc-avatar-shell {
  position: relative;
  width: 56px;
  height: 56px;
}
.uc-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  background: color-mix(in srgb, var(--primary) 80%, #4a6fa5);
}
.uc-avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  display: block;
}
.uc-online-dot {
  position: absolute;
  right: 0;
  bottom: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #22c55e;
  border: 2px solid var(--overlay-strong);
}
.uc-user-name {
  margin: 4px 0 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-main);
  line-height: 1.3;
}
.uc-user-meta {
  margin: 1px 0 0;
  font-size: 11px;
  font-weight: 300;
  color: var(--text-muted);
  opacity: 0.6;
}
.uc-close {
  position: absolute;
  top: 12px;
  right: 14px;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  transition:
    background-color 0.12s,
    color 0.12s;
}
.uc-close:hover {
  background: var(--hover-bg);
  color: var(--text-main);
}

/* ─── Tabs ─── */
.uc-tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 40%, transparent);
  padding: 0 16px;
}
.uc-tab {
  position: relative;
  flex: 1;
  padding: 12px 0 10px;
  border: none;
  background: transparent;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-muted);
  cursor: pointer;
  transition: color 0.12s;
}
.uc-tab.active {
  color: var(--text-main);
  font-weight: 600;
}
.uc-tab.active::after {
  content: "";
  position: absolute;
  bottom: 0;
  left: 20%;
  right: 20%;
  height: 2px;
  border-radius: 2px;
  background: var(--primary);
}
.uc-tab:hover {
  color: var(--text-secondary);
}

/* ─── Body ─── */
.uc-body {
  flex: 1;
  overflow: hidden;
  position: relative;
}
.uc-body::-webkit-scrollbar {
  width: 5px;
}
.uc-body::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
}

/* ─── Friends tab ─── */
.uc-friends {
  height: 100%;
  overflow-y: auto;
  padding: 14px 16px 10px;
  transition: height 0.25s ease;
}
.uc-friends::-webkit-scrollbar {
  width: 5px;
}
.uc-friends::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
}
.uc-friends:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--panel-border) 50%, transparent);
}

/* ─── Friend list ─── */
.uc-section-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--text-muted);
  opacity: 0.65;
  margin: 0 2px 10px;
}

/* ─── Friend Card (简约高级) ─── */
.uc-friend-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 4px;
  border-radius: 14px;
  transition: background-color 0.15s;
}
.uc-friend-card:hover {
  background: color-mix(in srgb, var(--text-muted) 8%, transparent);
}

.uc-friend-avatar {
  position: relative;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  flex-shrink: 0;
}
.uc-friend-avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 10px;
  object-fit: cover;
  display: block;
}
.uc-friend-dot {
  position: absolute;
  right: -3px;
  bottom: -2px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  border: 2px solid var(--overlay-strong);
  background: #22c55e;
}

.uc-friend-info {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.uc-friend-name {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-main);
  line-height: 1.3;
}
.uc-friend-status {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-muted);
  opacity: 0.7;
  margin-top: 1px;
}

/* ─── Hover-only icon buttons ─── */
.uc-friend-chat-btn,
.uc-friend-del-btn {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  flex-shrink: 0;
  opacity: 0;
  transition:
    opacity 0.16s,
    background-color 0.12s,
    color 0.12s;
}
.uc-friend-card:hover .uc-friend-chat-btn,
.uc-friend-card:hover .uc-friend-del-btn {
  opacity: 0.8;
}
.uc-friend-chat-btn:hover {
  opacity: 1 !important;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  color: var(--primary);
}
.uc-friend-del-btn:hover {
  opacity: 1 !important;
  background: var(--danger-bg);
  color: var(--danger);
}

/* ─── Group trigger ─── */
.uc-group-trigger {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
  border-radius: 14px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 50%, transparent);
  background: color-mix(in srgb, var(--surface-soft) 70%, transparent);
  cursor: pointer;
  transition: border-color 0.12s, background-color 0.12s;
}
.uc-group-trigger:hover {
  border-color: color-mix(in srgb, var(--primary) 24%, var(--panel-border));
  background: color-mix(in srgb, var(--primary) 4%, transparent);
}
.uc-group-trigger-avatar {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
  flex-shrink: 0;
}
.uc-group-trigger-info {
  flex: 1;
  min-width: 0;
}
.uc-group-trigger-name {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-main);
}
.uc-group-trigger-count {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-muted);
  opacity: 0.7;
  margin-top: 1px;
}
.uc-group-trigger-chevron {
  color: var(--text-muted);
  opacity: 0.5;
  flex-shrink: 0;
  transition: transform 0.12s;
}
.uc-group-trigger:hover .uc-group-trigger-chevron {
  transform: translateX(2px);
}

/* ─── Drawer ─── */
.uc-drawer {
  position: absolute;
  inset: 0;
  z-index: 10;
  background: var(--overlay-strong);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.uc-drawer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px 10px;
  border-bottom: 1px solid
    color-mix(in srgb, var(--panel-border) 30%, transparent);
  color: var(--text-main);
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.12s;
}
.uc-drawer-head:hover {
  color: var(--primary);
}
.uc-drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px 16px;
}
.uc-drawer-body::-webkit-scrollbar {
  width: 5px;
}
.uc-drawer-body::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
}
.uc-drawer-body:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--panel-border) 50%, transparent);
}

.uc-drawer-member {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 2px;
  border-radius: 12px;
  cursor: pointer;
  transition: background-color 0.12s;
}
.uc-drawer-member:hover {
  background: color-mix(in srgb, var(--text-muted) 8%, transparent);
}
.uc-drawer-member-avatar {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  flex-shrink: 0;
}
.uc-drawer-member-info {
  flex: 1;
  min-width: 0;
}
.uc-drawer-member-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-main);
}
.uc-drawer-member-role {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-muted);
  opacity: 0.7;
  margin-top: 1px;
}
.uc-drawer-chat-btn {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  flex-shrink: 0;
  opacity: 0;
  transition:
    opacity 0.16s,
    background-color 0.12s,
    color 0.12s;
}
.uc-drawer-member:hover .uc-drawer-chat-btn {
  opacity: 0.8;
}
.uc-drawer-chat-btn:hover {
  opacity: 1 !important;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  color: var(--primary);
}

.uc-drawer-group-chat {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 14px;
  padding: 11px;
  border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--primary) 18%, transparent);
  background: transparent;
  color: var(--primary);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.12s;
}
.uc-drawer-group-chat:hover {
  background: color-mix(in srgb, var(--primary) 8%, transparent);
}

/* ─── Profile cards ─── */
.uc-card {
  border-radius: var(--card-radius);
  border: 1px solid color-mix(in srgb, var(--panel-border) 50%, transparent);
  background: color-mix(in srgb, var(--surface-soft) 80%, transparent);
  padding: 14px;
  margin-bottom: 10px;
}
.uc-card-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 10px;
  letter-spacing: 0.04em;
}
.uc-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.uc-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.uc-field-label {
  font-size: 11px;
  font-weight: 500;
  color: var(--text-muted);
}
.uc-input {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 55%, transparent);
  background: color-mix(in srgb, var(--surface-solid) 70%, transparent);
  color: var(--text-main);
  font-size: 13px;
  outline: none;
  transition: border-color 0.12s;
}
.uc-input:focus {
  border-color: color-mix(in srgb, var(--primary) 40%, var(--panel-border));
}
.uc-input::placeholder {
  color: var(--text-muted);
  opacity: 0.5;
}
.uc-field-readonly {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
}
.uc-field-value {
  font-size: 14px;
  color: var(--text-secondary);
  font-weight: 300;
}
.uc-details {
  margin-top: 4px;
}
.uc-details-summary {
  font-size: 12px;
  color: var(--text-muted);
  cursor: pointer;
  user-select: none;
  opacity: 0.6;
  transition: opacity 0.12s;
}
.uc-details-summary:hover {
  opacity: 1;
}
.uc-hint {
  font-size: 12px;
  color: var(--text-muted);
  opacity: 0.6;
  margin: 0 0 12px;
}

/* ─── Profile Buttons ─── */
.uc-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.uc-btn {
  flex: 1;
  padding: 9px 14px;
  border-radius: 10px;
  border: none;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background-color 0.12s,
    color 0.12s,
    transform 0.1s;
}
.uc-btn:active {
  transform: scale(0.98);
}
.uc-btn-primary {
  background: var(--primary);
  color: #fff;
}
.uc-btn-primary:hover {
  background: var(--primary-hover);
}
.uc-btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.uc-btn-ghost {
  background: transparent;
  border: 1px solid color-mix(in srgb, var(--panel-border) 55%, transparent);
  color: var(--text-secondary);
}
.uc-btn-ghost:hover {
  background: var(--hover-bg);
  color: var(--text-main);
  border-color: color-mix(in srgb, var(--primary) 30%, var(--panel-border));
}

/* ─── Messages ─── */
.uc-msg {
  font-size: 13px;
  margin: 4px 0;
  padding: 8px 12px;
  border-radius: 8px;
}
.uc-msg.success {
  color: #16a34a;
  background: rgba(22, 163, 74, 0.08);
}
.uc-msg.error {
  color: var(--danger);
  background: var(--danger-bg);
}

/* ─── Empty ─── */
.uc-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 160px;
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 300;
}

/* ─── Drawer transition ─── */
.uc-drawer-enter-active {
  transition: all 0.22s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.uc-drawer-leave-active {
  transition: all 0.14s ease;
}
.uc-drawer-enter-from,
.uc-drawer-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

/* ─── Panel transition ─── */
.uc-pop-enter-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.uc-pop-leave-active {
  transition:
    opacity 0.12s ease,
    transform 0.12s ease;
}
.uc-pop-enter-from,
.uc-pop-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.97);
}
</style>
