import { ref, onMounted, onUnmounted } from 'vue'

/**
 * Calculate table max-height for fixed-height layouts.
 * @param {number} extraOffset - Additional offset to subtract (filters, headers, etc.)
 */
export function useTableHeight(extraOffset = 0) {
  const tableHeight = ref(500)

  function calc() {
    const headerH = 56       // header bar
    const padding = 20       // .content padding (10*2)
    const viewHeader = 36    // .view__header area (reduced)
    const cardHeader = 40    // el-card header (reduced)
    const pagination = 44    // pagination area (reduced)
    const cardPad = 24       // card body padding (12*2, reduced)
    tableHeight.value = window.innerHeight - headerH - padding - viewHeader - cardHeader - pagination - cardPad - extraOffset
  }

  onMounted(calc)
  // Don't listen for resize to avoid flicker; recalc on demand

  return { tableHeight, recalc: calc }
}
