<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

const nav = [
  { path: '/dashboard', label: 'Dashboard', icon: '📊' },
  { path: '/strategies', label: 'Strategies', icon: '📋' },
  { path: '/compare', label: 'Compare', icon: '⇄' },
]
</script>

<template>
  <div class="app-layout">
    <aside class="sidebar">
      <div class="logo" @click="router.push('/dashboard')">
        <span class="logo-icon">📈</span>
        <span class="logo-text">Trading Bridge</span>
      </div>
      <nav class="nav">
        <a
          v-for="item in nav"
          :key="item.path"
          :class="['nav-item', { active: route.path.startsWith(item.path) }]"
          @click="router.push(item.path)"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          <span class="nav-label">{{ item.label }}</span>
        </a>
      </nav>
      <div class="sidebar-footer">
        <span class="version">v0.1.0</span>
      </div>
    </aside>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }

:root {
  --bg-primary: #0a0a0a;
  --bg-secondary: #141414;
  --bg-card: #1a1a1a;
  --text-primary: #e5e5e5;
  --text-secondary: #888;
  --accent: #d97706;
  --accent-hover: #f59e0b;
  --border: #222;
  --success: #22c55e;
  --danger: #ef4444;
  --warning: #f59e0b;
}

body {
  font-family: 'Inter', system-ui, -apple-system, sans-serif;
  background: var(--bg-primary);
  color: var(--text-primary);
  overflow: hidden;
}

.app-layout {
  display: grid;
  grid-template-columns: 220px 1fr;
  height: 100vh;
}

.sidebar {
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

.logo-icon { font-size: 1.5rem; }
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

.nav-icon { font-size: 1rem; width: 1.25rem; text-align: center; }
.nav-label { font-size: 0.875rem; }

.sidebar-footer { margin-top: auto; padding: 0.5rem; }
.version { font-size: 0.75rem; color: var(--text-secondary); }

.content {
  overflow-y: auto;
  padding: 1.5rem;
}
</style>
