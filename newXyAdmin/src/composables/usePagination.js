import { ref, computed } from 'vue'

export function usePagination(fetchFn, defaultSortBy = 'id', defaultSortOrder = 'asc') {
  const page = ref(1)
  const size = ref(10)
  const total = ref(0)
  const data = ref([])
  const loading = ref(false)
  const sortBy = ref(defaultSortBy)
  const sortOrder = ref(defaultSortOrder)

  const pagination = computed(() => ({
    page: page.value,
    size: size.value,
    sortBy: sortBy.value,
    sortOrder: sortOrder.value
  }))

  async function load(extraParams = {}) {
    loading.value = true
    try {
      const res = await fetchFn({ ...pagination.value, ...extraParams })
      data.value = res.records || []
      total.value = res.total || 0
    } catch (e) {
      data.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  function handlePageChange(p) {
    page.value = p
    load()
  }

  function handleSizeChange(s) {
    size.value = s
    page.value = 1
    load()
  }

  function handleSortChange({ prop, order }) {
    if (prop) {
      sortBy.value = prop
      sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
    } else {
      sortBy.value = defaultSortBy
      sortOrder.value = defaultSortOrder
    }
    page.value = 1
    load()
  }

  return {
    page, size, total, data, loading, sortBy, sortOrder,
    load, handlePageChange, handleSizeChange, handleSortChange
  }
}
