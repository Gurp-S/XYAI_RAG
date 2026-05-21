import { ref } from 'vue'

const toasts = ref([])
let _id = 0

export function useToast() {
  function add(message, type = 'info', duration = 3500) {
    const id = ++_id
    toasts.value.push({ id, message, type })
    setTimeout(() => {
      toasts.value = toasts.value.filter(t => t.id !== id)
    }, duration)
  }

  function success(msg) { add(msg, 'success') }
  function error(msg) { add(msg, 'error') }
  function info(msg) { add(msg, 'info') }

  return { toasts, success, error, info }
}
