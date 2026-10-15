<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useStatusBar } from '@/composables/useStatusBar'
import { useControlPlaneConfig } from '@/composables/controlPlaneConfig'
import {
  LayoutDashboard,
  Database,
  Cpu,
  Radio,
  GitCompare,
  LogOut,
  TrendingUp,
  AlertTriangle,
  Zap,
  CheckCircle,
  Info,
  Clock
} from '@lucide/vue'

const router = useRouter()
const route = useRoute()

const { message, type, clearStatus } = useStatusBar()
const { controlPlaneUrl } = useControlPlaneConfig()

const utcTime = ref('')
let clockInterval: any = null

function updateClock() {
  const now = new Date()
  const yyyy = now.getUTCFullYear()
  const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(now.getUTCDate()).padStart(2, '0')
  const hh = String(now.getUTCHours()).padStart(2, '0')
  const min = String(now.getUTCMinutes()).padStart(2, '0')
  const ss = String(now.getUTCSeconds()).padStart(2, '0')
  utcTime.value = `${yyyy}-${mm}-${dd} ${hh}:${min}:${ss} UTC`
}

const isConnected = ref(false)
let connectionInterval: any = null

async function checkConnection() {
  try {
    const res = await fetch(`${controlPlaneUrl.value}/api/control/summary`)
    isConnected.value = res.ok
  } catch (err) {
    isConnected.value = false
  }
}

onMounted(() => {
  updateClock()
  clockInterval = setInterval(updateClock, 1000)
  
  checkConnection()
  connectionInterval = setInterval(checkConnection, 2000)
})

onUnmounted(() => {
  if (clockInterval) clearInterval(clockInterval)
  if (connectionInterval) clearInterval(connectionInterval)
})

const nav = [
  { path: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { path: '/wfa', label: 'Walk-Forward', icon: TrendingUp },
  { path: '/data-manager', label: 'Data Manager', icon: Database },
  { path: '/strategies', label: 'Strategies', icon: Cpu },
  { path: '/results', label: 'Backtests', icon: Clock },
  { path: '/live-trading', label: 'Trading Desk', icon: Radio },
  { path: '/compare', label: 'Compare', icon: GitCompare },
]

function quitApp() {
  const api = (window as any).electronAPI
  if (api && typeof api.quitApp === 'function') {
    api.quitApp()
  } else {
    console.log('Quit App requested (not in Electron)')
  }
}
</script>

<template>
  <div class="app-layout">
    <aside class="sidebar">
      <div class="logo" @click="router.push('/dashboard')">
        <TrendingUp class="logo-icon" />
        <span class="logo-text">Trading Bridge</span>
      </div>
      <nav class="nav">
        <a
          v-for="item in nav"
          :key="item.path"
          :class="['nav-item', { active: route.path.startsWith(item.path) }]"
          @click="router.push(item.path)"
        >
          <component :is="item.icon" class="nav-icon" />
          <span class="nav-label">{{ item.label }}</span>
        </a>
      </nav>
      <div class="sidebar-footer" style="display: flex; flex-direction: column; gap: 0.5rem;">
        <a class="nav-item quit-item" @click="quitApp" style="padding: 0.4rem 0.5rem; border: 1px solid rgba(239, 68, 68, 0.2); border-radius: 6px; color: #fca5a5;">
          <LogOut class="nav-icon" />
          <span class="nav-label">Quit App</span>
        </a>
        <span class="version">v0.1.0</span>
      </div>
    </aside>
    <main class="content">
      <router-view />
    </main>
    <footer class="status-bar" :class="[type, { 'has-message': message }]">
      <div class="status-left">
        <span class="status-dot" :class="[message ? type : (isConnected ? 'ready' : 'error')]"></span>
        <span class="status-text">
          {{ message ? 'System Alert' : (isConnected ? 'System Ready' : 'Control Plane Disconnected') }}
        </span>
      </div>

      <div class="status-center">
        <transition name="slide-up">
          <div v-if="message" class="status-message-container">
            <span class="status-message-icon">
              <AlertTriangle v-if="type === 'error'" class="status-icon" />
              <Zap v-else-if="type === 'warning'" class="status-icon" />
              <CheckCircle v-else-if="type === 'success'" class="status-icon" />
              <Info v-else class="status-icon" />
            </span>
            <span class="status-message" :title="message">{{ message }}</span>
            <button class="status-dismiss" @click="clearStatus" title="Dismiss message">×</button>
          </div>
        </transition>
      </div>

      <div class="status-right">
        <span class="status-url" title="Control Plane Server URL">
          <Radio class="status-icon" />
          {{ controlPlaneUrl }}
        </span>
        <span class="status-divider">|</span>
        <span class="status-clock">
          <Clock class="status-icon" />
          {{ utcTime }}
        </span>
      </div>
    </footer>
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }

:root {
  --bg-primary: #0a0a0a;
  --bg-secondary: #141414;
  --bg-card: #1a1a1a;
  --bg-card-hover: #222222;
  --bg-glass: rgba(26, 26, 26, 0.7);
  --text-primary: #f1f5f9;
  --text-secondary: #94a3b8;
  --text-muted: #64748b;
  --accent: #d97706;
  --accent-hover: #f59e0b;
  --border: #262626;
  --border-focus: #f59e0b;
  --success: #10b981;
  --danger: #ef4444;
  --warning: #f59e0b;
  --info: #38bdf8;
  
  /* Multi-Asset Color Tokens */
  --asset-futures: #a855f7;
  --asset-futures-bg: rgba(168, 85, 247, 0.15);
  --asset-futures-border: rgba(168, 85, 247, 0.4);
  
  --asset-equity: #06b6d4;
  --asset-equity-bg: rgba(6, 182, 212, 0.15);
  --asset-equity-border: rgba(6, 182, 212, 0.4);
  
  --asset-forex: #f59e0b;
  --asset-forex-bg: rgba(245, 158, 11, 0.15);
  --asset-forex-border: rgba(245, 158, 11, 0.4);
}

body {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  background: var(--bg-primary);
  color: var(--text-primary);
  overflow: hidden;
  font-feature-settings: "cv02", "cv03", "cv04", "cv11", "tnum";
}

/* Custom Scrollbars */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
::-webkit-scrollbar-track {
  background: var(--bg-primary);
}
::-webkit-scrollbar-thumb {
  background: #2a2a2a;
  border-radius: 3px;
}
::-webkit-scrollbar-thumb:hover {
  background: #3a3a3a;
}

.app-layout {
  display: grid;
  grid-template-columns: 220px 1fr;
  grid-template-rows: 1fr 30px;
  height: 100vh;
}

.sidebar {
  grid-row: 1;
  grid-column: 1;
  background: var(--bg-secondary);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 1rem;
}

.logo {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem;
  margin-bottom: 2rem;
  cursor: pointer;
}

.logo-icon {
  width: 1.4rem;
  height: 1.4rem;
  color: var(--accent);
  stroke-width: 2.5;
}
.logo-text { font-size: 1rem; font-weight: 600; }

.nav { display: flex; flex-direction: column; gap: 0.25rem; }

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.6rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-secondary);
  text-decoration: none;
  transition: all 0.15s;
}

.nav-item:hover { background: var(--bg-card); color: var(--text-primary); }
.nav-item.active { background: var(--accent); color: #fff; }
.quit-item:hover { background: rgba(239, 68, 68, 0.1) !important; color: #f87171 !important; border-color: rgba(239, 68, 68, 0.4) !important; }

.nav-icon {
  width: 1.15rem;
  height: 1.15rem;
  stroke-width: 1.75;
  flex-shrink: 0;
}
.nav-label { font-size: 0.875rem; }

.sidebar-footer { margin-top: auto; padding: 0.5rem; }
.version { font-size: 0.75rem; color: var(--text-secondary); }

.content {
  grid-row: 1;
  grid-column: 2;
  overflow-y: auto;
  padding: 1.5rem;
}

/* Status Bar styling */
.status-bar {
  grid-row: 2;
  grid-column: 1 / -1;
  height: 30px;
  background: rgba(14, 14, 14, 0.85);
  backdrop-filter: blur(8px);
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.75rem;
  font-size: 0.725rem;
  color: var(--text-secondary);
  z-index: 100;
  transition: background-color 0.3s ease, border-top-color 0.3s ease;
}

.status-bar.error {
  background: rgba(45, 18, 18, 0.9);
  border-top-color: rgba(239, 68, 68, 0.3);
  color: #fca5a5;
}
.status-bar.warning {
  background: rgba(45, 34, 18, 0.9);
  border-top-color: rgba(245, 158, 11, 0.3);
  color: #fde047;
}
.status-bar.success {
  background: rgba(18, 45, 25, 0.9);
  border-top-color: rgba(34, 197, 94, 0.3);
  color: #86efac;
}

.status-left, .status-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background-color: var(--success);
  box-shadow: 0 0 6px var(--success);
  transition: all 0.3s ease;
}

.status-dot.ready {
  background-color: var(--success);
  box-shadow: 0 0 6px var(--success);
}
.status-dot.info {
  background-color: #3b82f6;
  box-shadow: 0 0 6px #3b82f6;
}
.status-dot.success {
  background-color: var(--success);
  box-shadow: 0 0 6px var(--success);
}
.status-dot.warning {
  background-color: var(--warning);
  box-shadow: 0 0 6px var(--warning);
}
.status-dot.error {
  background-color: var(--danger);
  box-shadow: 0 0 6px var(--danger);
}

.status-text {
  font-weight: 600;
  text-transform: uppercase;
  font-size: 0.65rem;
  letter-spacing: 0.05em;
}

.status-center {
  flex-grow: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  overflow: hidden;
  position: relative;
  height: 100%;
}

.status-message-container {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  background: rgba(255, 255, 255, 0.03);
  padding: 0.15rem 0.6rem;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  max-width: 500px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-bar.error .status-message-container {
  background: rgba(239, 68, 68, 0.1);
  border-color: rgba(239, 68, 68, 0.2);
}
.status-bar.warning .status-message-container {
  background: rgba(245, 158, 11, 0.1);
  border-color: rgba(245, 158, 11, 0.2);
}
.status-bar.success .status-message-container {
  background: rgba(34, 197, 94, 0.1);
  border-color: rgba(34, 197, 94, 0.2);
}

.status-message-icon {
  display: flex;
  align-items: center;
  justify-content: center;
}

.status-icon {
  width: 0.85rem;
  height: 0.85rem;
  stroke-width: 2;
  flex-shrink: 0;
}

.status-message {
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
}
.status-bar.error .status-message {
  color: #fee2e2;
}
.status-bar.warning .status-message {
  color: #fef08a;
}
.status-bar.success .status-message {
  color: #dcfce7;
}

.status-dismiss {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  font-size: 0.95rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 0.15rem;
  border-radius: 50%;
  transition: all 0.15s;
}
.status-dismiss:hover {
  color: var(--text-primary);
  background: rgba(255, 255, 255, 0.1);
}
.status-bar.error .status-dismiss:hover {
  background: rgba(239, 68, 68, 0.2);
}

.status-url,
.status-clock {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  font-family: 'JetBrains Mono', monospace;
}

.status-url {
  opacity: 0.85;
}

.status-divider {
  color: var(--border);
  margin: 0 0.15rem;
}

.status-clock {
  color: var(--text-primary);
}

/* Slide Up Transition for messages */
.slide-up-enter-active,
.slide-up-leave-active {
  transition: all 0.3s ease;
}
.slide-up-enter-from {
  transform: translateY(20px);
  opacity: 0;
}
.slide-up-leave-to {
  transform: translateY(-20px);
  opacity: 0;
}

@keyframes pulse {
  0% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.7);
  }
  70% {
    transform: scale(1.05);
    box-shadow: 0 0 0 4px rgba(239, 68, 68, 0);
  }
  100% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(239, 68, 68, 0);
  }
}

@keyframes pulse-warning {
  0% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(245, 158, 11, 0.7);
  }
  70% {
    transform: scale(1.05);
    box-shadow: 0 0 0 4px rgba(245, 158, 11, 0);
  }
  100% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(245, 158, 11, 0);
  }
}

@keyframes pulse-success {
  0% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.7);
  }
  70% {
    transform: scale(1.05);
    box-shadow: 0 0 0 4px rgba(34, 197, 94, 0);
  }
  100% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(34, 197, 94, 0);
  }
}

.status-dot.warning {
  animation: pulse-warning 1.5s infinite;
}
.status-dot.success {
  animation: pulse-success 1.5s infinite;
}
.status-dot.error {
  animation: pulse 1s infinite;
}
</style>

