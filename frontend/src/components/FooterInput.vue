<template>
  <section class="input-area">
    <div class="input-wrapper">
      <textarea :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" @keydown.enter.exact.prevent="$emit('send')" placeholder="输入你想咨询的问题，按 Enter 发送，Shift+Enter 换行"></textarea>
      <!-- Added inline style + extra class to raise specificity so button remains visible
           even if global styles accidentally override it in light mode -->
      <button class="btn-send btn-send-inline"
              aria-label="发送消息"
              style="background: linear-gradient(135deg,#f47c8e,#ff9fb0); color:#fff; width:46px; height:46px; border-radius:18px; border:none; display:flex; align-items:center; justify-content:center; cursor:pointer; z-index:9999 !important; box-shadow:0 12px 24px rgba(244,124,142,0.18); pointer-events:auto;"
              @click="$emit('send')">
        <!-- use an inline svg icon and force its fill to currentColor to follow button color -->
        <svg viewBox="0 0 24 24" style="width:20px;height:20px;fill:currentColor;display:block;">
          <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
        </svg>
      </button>
    </div>
  </section>
</template>
<script setup>
import { defineProps } from 'vue'
defineProps({ modelValue: { type: String, default: '' } })
</script>
<style scoped>
.input-area{padding:12px}
</style>

<!-- Global (non-scoped) overrides for the inline/button class to support dark mode
     and to ensure the inline styling can be adapted when the body toggles class -->
<style>
body.dark .btn-send-inline{
  /* use !important here as a last-resort to beat other global rules */
  background: linear-gradient(135deg,#8b7cf6,#f47c8e) !important;
  color: #0b0b10 !important;
  box-shadow: 0 10px 26px rgba(0,0,0,0.5) !important;
}
</style>
