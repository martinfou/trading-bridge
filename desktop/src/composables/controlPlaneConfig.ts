import { ref } from 'vue'

const DEFAULT_URL = 'http://localhost:8080'

const controlPlaneUrl = ref(DEFAULT_URL)

export function setControlPlaneUrl(url: string) {
  controlPlaneUrl.value = url
}

export function useControlPlaneConfig() {
  return { controlPlaneUrl }
}
