import { ref } from 'vue'

const iconMap = {
  'pdf': '📄', 'word': '📝', 'document': '📝', 'doc': '📝',
  'text': '📃', 'txt': '📃', 'csv': '📊', 'html': '🌐',
  'json': '📋', 'markdown': '📋', 'image': '🖼️', 'zip': '📦',
}

export function useFileParser() {
  const parsing = ref(false)
  const parseError = ref('')
  const lastResult = ref(null)

  function getFileIcon(file) {
    if (file?.parsed) return '✅'
    const t = (file?.type || '').toLowerCase()
    for (const [k, v] of Object.entries(iconMap)) {
      if (t.includes(k)) return v
    }
    return '📎'
  }

  function getFileTypeName(file) {
    const t = (file?.type || file?.contentType || '').toLowerCase()
    if (t.includes('pdf')) return 'PDF'
    if (t.includes('word') || t.includes('document')) return 'DOC'
    if (t.includes('text')) return 'TXT'
    if (t.includes('csv')) return 'CSV'
    if (t.includes('html')) return 'HTML'
    if (t.includes('image')) return 'IMG'
    return 'FILE'
  }

  return { parsing, parseError, lastResult, getFileIcon, getFileTypeName }
}

/**
 * 上传文件到 /ai/chat/files 解析，返回 { content, contentType } 或 null。
 * 独立函数，不依赖 composable 的共享 ref。
 */
export async function parseUploadedFile(file) {
  const { authFetch, safeReadJson } = await import('../services/api.js')
  const fd = new FormData()
  fd.append('chatFile', file)

  const res = await authFetch('/ai/chat/files', { method: 'POST', body: fd })
  const json = await safeReadJson(res)
  const data = json?.data || (json?.content ? json : null)

  if (!res.ok || !data || !data.content) {
    const m = json?.msg || json?.message || `解析失败(${res.status})`
    throw new Error(m)
  }
  return data
}
