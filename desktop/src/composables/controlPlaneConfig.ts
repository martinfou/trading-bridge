import { ref } from 'vue'

const STORAGE_KEY = 'trading_control_plane_url'
const API_KEY_STORAGE_KEY = 'trading_control_plane_api_key'
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

function loadInitialApiKey(): string {
  try {
    const saved = localStorage.getItem(API_KEY_STORAGE_KEY)
    if (saved && saved.trim()) {
      return saved.trim()
    }
  } catch {
    // ignore storage errors
  }
  return ''
}

const controlPlaneUrl = ref<string>(loadInitialUrl())
const controlPlaneApiKey = ref<string>(loadInitialApiKey())

export function setControlPlaneUrl(url: string) {
  const cleaned = normalizeUrl(url)
  controlPlaneUrl.value = cleaned
  try {
    localStorage.setItem(STORAGE_KEY, cleaned)
  } catch {
    // ignore storage errors
  }
}

export function setControlPlaneApiKey(apiKey: string) {
  const cleaned = (apiKey || '').trim()
  controlPlaneApiKey.value = cleaned
  try {
    if (cleaned) {
      localStorage.setItem(API_KEY_STORAGE_KEY, cleaned)
    } else {
      localStorage.removeItem(API_KEY_STORAGE_KEY)
    }
  } catch {
    // ignore storage errors
  }
}

export function getControlPlaneHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...(extra || {}) }
  const key = controlPlaneApiKey.value
  if (key) {
    headers['Authorization'] = `Bearer ${key}`
    headers['X-API-Key'] = key
  }
  return headers
}

export function resetControlPlaneUrl() {
  setControlPlaneUrl(DEFAULT_URL)
  setControlPlaneApiKey('')
}

export function useControlPlaneConfig() {
  return {
    controlPlaneUrl,
    controlPlaneApiKey,
    setControlPlaneUrl,
    setControlPlaneApiKey,
    getControlPlaneHeaders,
    resetControlPlaneUrl,
    DEFAULT_URL
  }
}
