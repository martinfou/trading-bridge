import { ref } from 'vue'

const STORAGE_KEY = 'trading_control_plane_url'
const DEFAULT_URL = 'http://localhost:8080'

function normalizeUrl(raw: string | null | undefined): string {
  const trimmed = (raw || '').trim().replace(/\/+$/, '')
  if (!trimmed) return DEFAULT_URL
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`
  return withScheme.replace(/\/+$/, '')
}

function loadInitialUrl(): string {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && saved.trim()) {
      return normalizeUrl(saved)
    }
  } catch {
    // ignore storage errors
  }
  return DEFAULT_URL
}

const controlPlaneUrl = ref<string>(loadInitialUrl())

export function setControlPlaneUrl(url: string) {
  const cleaned = normalizeUrl(url)
  controlPlaneUrl.value = cleaned
  try {
    localStorage.setItem(STORAGE_KEY, cleaned)
  } catch {
    // ignore storage errors
  }
}

export function resetControlPlaneUrl() {
  setControlPlaneUrl(DEFAULT_URL)
}

export function useControlPlaneConfig() {
  return { controlPlaneUrl, setControlPlaneUrl, resetControlPlaneUrl, DEFAULT_URL }
}
