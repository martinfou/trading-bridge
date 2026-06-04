import { ref, onUnmounted } from 'vue'
import { useControlPlaneConfig } from './controlPlaneConfig'

export type WsStatus = 'disconnected' | 'connecting' | 'connected' | 'error'

export interface WsEvent {
  type: string
  timestamp: string
  payload?: Record<string, unknown>
}

export function useRunWebSocket() {
  const { controlPlaneUrl } = useControlPlaneConfig()
  const status = ref<WsStatus>('disconnected')
  const lastEvent = ref<WsEvent | null>(null)
  const events = ref<WsEvent[]>([])

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let currentRunId: string | null = null
  let manualClose = false

  function connect(runId: string) {
    if (ws && currentRunId === runId && ws.readyState === WebSocket.OPEN) return

    manualClose = false
    currentRunId = runId
    status.value = 'connecting'

    const wsUrl = controlPlaneUrl.value.replace(/^http/, 'ws')
    ws = new WebSocket(`${wsUrl}/ws/runs/${runId}`)

    ws.onopen = () => {
      status.value = 'connected'
    }

    ws.onmessage = (msg: MessageEvent) => {
      try {
        const event: WsEvent = JSON.parse(msg.data)
        events.value.push(event)
        lastEvent.value = event
      } catch {
        // non-JSON message, ignore
      }
    }

    ws.onerror = () => {
      status.value = 'error'
    }

    ws.onclose = () => {
      if (!manualClose && currentRunId) {
        status.value = 'connecting'
        scheduleReconnect()
      } else {
        status.value = 'disconnected'
      }
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(() => {
      if (currentRunId && !manualClose) {
        connect(currentRunId)
      }
    }, 2000)
  }

  function disconnect() {
    manualClose = true
    currentRunId = null
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.onclose = null
      ws.close()
      ws = null
    }
    status.value = 'disconnected'
  }

  function clear() {
    events.value = []
    lastEvent.value = null
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    status,
    lastEvent,
    events,
    connect,
    disconnect,
    clear,
  }
}
