/**
 * Format large numbers: 12345 -> 12.3k, 1234567 -> 1.2m
 */
export function formatNumber(n) {
  if (n === null || n === undefined) return '-'
  if (typeof n === 'string') n = parseFloat(n)
  if (isNaN(n)) return '-'
  const abs = Math.abs(n)
  if (abs >= 1000000) return (n / 1000000).toFixed(1) + 'm'
  if (abs >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

/**
 * Format duration display: >=1000ms -> X.Xs, <1000ms -> Xms
 */
export function formatDuration(ms) {
  if (ms === null || ms === undefined) return '-'
  if (ms === 0) return '0ms'
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's'
  return ms + 'ms'
}

/**
 * Format duration with color class
 */
export function durationClass(ms) {
  if (ms === null || ms === undefined) return ''
  if (ms > 5000) return 'text-danger'
  if (ms > 1000) return 'text-warning'
  return 'text-success'
}

/**
 * Status color mapping
 */
export function statusType(status) {
  const map = {
    success: 'success',
    running: 'primary',
    processing: 'primary',
    failed: 'danger',
    error: 'danger',
    enabled: 'success',
    disabled: 'info',
    online: 'success',
    offline: 'info',
    healthy: 'success',
    unhealthy: 'danger',
    closed: 'info',
    open: 'warning',
    true: 'success',
    false: 'info'
  }
  return map[String(status).toLowerCase()] || 'info'
}
