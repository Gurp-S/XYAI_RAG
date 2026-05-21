import { ref } from 'vue'

const STORAGE_KEY = 'xyai-admin-theme'

// Initialize: use stored preference, fall back to time-based
function getInitialTheme() {
  const stored = localStorage.getItem(STORAGE_KEY)
  if (stored === 'dark' || stored === 'light') return stored === 'dark'
  const hour = new Date().getHours()
  return hour < 6 || hour >= 18
}

const isDark = ref(getInitialTheme())

// Apply to DOM
function applyTheme(dark) {
  if (dark) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}
applyTheme(isDark.value)

export function useTimeTheme() {
  function toggleTheme() {
    isDark.value = !isDark.value
    localStorage.setItem(STORAGE_KEY, isDark.value ? 'dark' : 'light')
    applyTheme(isDark.value)
  }

  function setTheme(dark) {
    isDark.value = dark
    localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light')
    applyTheme(dark)
  }

  return { isDark, toggleTheme, setTheme }
}
