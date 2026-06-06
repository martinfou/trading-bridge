import { ref } from 'vue'

export type StatusType = 'info' | 'success' | 'warning' | 'error'

const message = ref<string | null>(null)
const type = ref<StatusType>('info')
let timeoutId: ReturnType<typeof setTimeout> | null = null

export function useStatusBar() {
  function setStatus(msg: string, msgType: StatusType = 'info', duration = 6000) {
    message.value = msg
    type.value = msgType

    if (timeoutId) {
      clearTimeout(timeoutId)
      timeoutId = null
    }

    // Auto-clear logic for non-error messages by default, or errors if desired (here we don't auto-clear errors)
    if (duration > 0 && msgType !== 'error') {
      timeoutId = setTimeout(() => {
        if (message.value === msg) {
          message.value = null
        }
      }, duration)
    }
  }

  function clearStatus() {
    message.value = null
    if (timeoutId) {
      clearTimeout(timeoutId)
      timeoutId = null
    }
  }

  return {
    message,
    type,
    setStatus,
    clearStatus,
  }
}
