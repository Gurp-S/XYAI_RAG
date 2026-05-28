import { ref, onMounted, onUnmounted } from 'vue'

/**
 * Calculate table max-height so the table fills available viewport space.
 * Measures the .card-table element's actual position for accuracy.
 * @param {number} extraOffset - Additional offset to subtract
 */
export function useTableHeight(extraOffset = 0) {
  const tableHeight = ref(400)

  function calc() {
    // Find the card-table element in the current page
    const cardTable = document.querySelector('.card-table')
    if (cardTable) {
      const rect = cardTable.getBoundingClientRect()
      // Available height: viewport bottom → card top, minus pagination area
      const paginationArea = 56
      tableHeight.value = window.innerHeight - rect.top - paginationArea - extraOffset
    } else {
      // Fallback: use hardcoded estimate
      tableHeight.value = window.innerHeight - 200 - extraOffset
    }
  }

  let observer = null
  onMounted(() => {
    // Wait a tick for DOM to fully render
    requestAnimationFrame(() => {
      calc()
      // Watch for layout changes (sidebar toggle, filter wrap, etc.)
      const cardTable = document.querySelector('.card-table')
      if (cardTable) {
        observer = new ResizeObserver(calc)
        observer.observe(cardTable)
      }
    })
  })
  onUnmounted(() => {
    if (observer) observer.disconnect()
  })

  return { tableHeight, recalc: calc }
}
