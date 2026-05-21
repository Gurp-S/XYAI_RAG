<template>
  <router-view />
</template>

<script setup>
import { watch } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

// Update document title based on route
watch(
  () => route.meta?.title,
  (title) => {
    document.title = title ? `${title} — XYAI 管理后台` : 'XYAI 管理后台'
  },
  { immediate: true }
)
</script>
