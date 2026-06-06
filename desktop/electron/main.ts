import { app, BrowserWindow, dialog, ipcMain } from 'electron'
import { spawn, type ChildProcess } from 'child_process'
import http from 'http'
import path from 'path'
import fs from 'fs'

let mainWindow: BrowserWindow | null = null
let loadingWindow: BrowserWindow | null = null
let javaProcess: ChildProcess | null = null

const CONTROL_PLANE_PORT = 8080
const READY_TIMEOUT_MS = 30_000
const POLL_INTERVAL_MS = 500

// ── Path resolution ──────────────────────────────────────────────────────────

interface JvmConfig {
  javaBin: string
  jarPath: string
  dataDir: string
  cwd: string
}

function resolveDevPaths(): JvmConfig {
  const projectRoot = path.resolve(__dirname, '../../')
  const jarPath = path.join(projectRoot, 'trading-runtime/target/trading-runtime-1.0.0-SNAPSHOT-shaded.jar')

  // Find system java via JAVA_HOME or PATH
  let javaBin = 'java'
  if (process.env.JAVA_HOME && process.env.JAVA_HOME !== '/') {
    const candidate = path.join(process.env.JAVA_HOME, 'bin/java' + (process.platform === 'win32' ? '.exe' : ''))
    if (fs.existsSync(candidate)) javaBin = candidate
  }

  return { javaBin, jarPath, dataDir: path.join(projectRoot, 'data/runtime'), cwd: projectRoot }
}

function resolvePackagedPaths(): JvmConfig {
  const resourcesDir = path.join(process.resourcesPath, 'resources')
  const jarPath = path.join(resourcesDir, 'jar/control-plane.jar')
  const javaBin = path.join(resourcesDir, 'jre/bin/java')
  const dataDir = path.join(app.getPath('userData'), 'data')
  return { javaBin, jarPath, dataDir, cwd: dataDir }
}

function resolveConfig(): JvmConfig {
  if (process.env.VITE_DEV_SERVER_URL) {
    const cfg = resolveDevPaths()
    console.log('[main] Dev mode — java:', cfg.javaBin, 'jar:', cfg.jarPath)
    return cfg
  }
  const cfg = resolvePackagedPaths()
  console.log('[main] Packaged mode — java:', cfg.javaBin, 'jar:', cfg.jarPath)
  return cfg
}

// ── JVM lifecycle ────────────────────────────────────────────────────────────

function startJavaProcess(cfg: JvmConfig): void {
  if (!fs.existsSync(cfg.jarPath)) {
    console.error('[main] JAR not found at', cfg.jarPath)
    showError(`Backend JAR introuvable : ${cfg.jarPath}\n\nReconstruis le projet Java avec :\n  mvn package -pl trading-runtime -am -DskipTests\npuis relance :\n  bash scripts/build-jre.sh <jar> desktop-resources/`)
    return
  }

  const isPath = cfg.javaBin.includes('/') || cfg.javaBin.includes('\\') || cfg.javaBin.includes(path.sep);
  if (isPath && !fs.existsSync(cfg.javaBin)) {
    console.error('[main] Java binary not found at', cfg.javaBin)
    showError(`Java introuvable : ${cfg.javaBin}\n\nEn dev, vérifie que JAVA_HOME pointe vers un JDK 21+.\nEn production, le JRE devrait être embarqué.`)
    return
  }

  fs.mkdirSync(cfg.dataDir, { recursive: true })

  console.log('[main] Starting JVM:', cfg.javaBin, '-jar', cfg.jarPath)
  javaProcess = spawn(cfg.javaBin, [
    '--enable-native-access=ALL-UNNAMED',
    '-jar', cfg.jarPath,
  ], {
    cwd: cfg.cwd,
    env: {
      ...process.env,
      CONTROL_PLANE_PORT: String(CONTROL_PLANE_PORT),
      TRADING_BRIDGE_DATA_DIR: cfg.dataDir,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  javaProcess.stdout?.on('data', (data: Buffer) => {
    console.log('[jvm]', data.toString().trimEnd())
  })

  javaProcess.stderr?.on('data', (data: Buffer) => {
    console.error('[jvm:err]', data.toString().trimEnd())
  })

  javaProcess.on('exit', (code, signal) => {
    console.log('[main] JVM exited with code', code, 'signal', signal)
    if (code !== 0 && code !== null && mainWindow) {
      showError(`Le backend Java s'est arrêté (code ${code}).\nRedémarre l'application.`)
    }
    javaProcess = null
  })

  javaProcess.on('error', (err) => {
    console.error('[main] Failed to start JVM:', err.message)
    showError(`Impossible de démarrer le backend Java : ${err.message}`)
    javaProcess = null
  })
}

function stopJavaProcess(): void {
  if (!javaProcess) return
  console.log('[main] Stopping JVM (SIGTERM)...')
  javaProcess.kill('SIGTERM')
  // Give it 5 seconds, then SIGKILL
  setTimeout(() => {
    if (javaProcess) {
      console.log('[main] JVM still alive, sending SIGKILL')
      javaProcess.kill('SIGKILL')
    }
  }, 5000)
}

// ── Health check ─────────────────────────────────────────────────────────────

function waitForControlPlane(): Promise<void> {
  return new Promise((resolve, reject) => {
    const startTime = Date.now()

    function poll() {
      const req = http.get(`http://localhost:${CONTROL_PLANE_PORT}/api/strategies`, (res) => {
        if (res.statusCode === 200) {
          console.log('[main] Control plane ready')
          resolve()
        } else {
          retryOrTimeout()
        }
      })
      req.on('error', () => retryOrTimeout())
      req.end()
    }

    function retryOrTimeout() {
      if (Date.now() - startTime > READY_TIMEOUT_MS) {
        reject(new Error(`Control plane not ready after ${READY_TIMEOUT_MS / 1000}s`))
        return
      }
      if (!javaProcess) {
        reject(new Error('JVM exited before control plane was ready'))
        return
      }
      setTimeout(poll, POLL_INTERVAL_MS)
    }

    poll()
  })
}

// ── Error dialog ─────────────────────────────────────────────────────────────

function showError(message: string): void {
  if (mainWindow) {
    dialog.showErrorBox('Trading Bridge — Erreur', message)
  } else {
    app.whenReady().then(() => {
      dialog.showErrorBox('Trading Bridge — Erreur', message)
    })
  }
}

// ── Loading window ───────────────────────────────────────────────────────────

function createLoadingWindow(): void {
  loadingWindow = new BrowserWindow({
    width: 600,
    height: 400,
    resizable: false,
    frame: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })

  loadingWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(`
    <!DOCTYPE html>
    <html><body style="display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#1a1a2e;color:#e0e0e0;font-family:sans-serif;flex-direction:column">
      <h2 style="margin-bottom:8px">Trading Bridge</h2>
      <p style="color:#888;font-size:14px">Démarrage du backend Java…</p>
      <div style="margin-top:16px;width:48px;height:48px;border:4px solid #333;border-top-color:#00b894;border-radius:50%;animation:spin 1s linear infinite"></div>
      <style>@keyframes spin{to{transform:rotate(360deg)}}</style>
    </body></html>
  `)}`)

  loadingWindow.once('ready-to-show', () => {
    loadingWindow?.show()
  })
}

function createMainWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 1000,
    minWidth: 1024,
    minHeight: 800,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
    title: 'Trading Bridge',
    show: false,
  })

  mainWindow.once('ready-to-show', () => {
    console.log('[main] Main window ready to show, closing loading window')
    if (loadingWindow) {
      loadingWindow.close()
      loadingWindow = null
    }
    mainWindow?.show()
  })

  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
    mainWindow.webContents.openDevTools()
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'))
  }
}

// ── App lifecycle ────────────────────────────────────────────────────────────

app.whenReady().then(async () => {
  const cfg = resolveConfig()

  // Show loading while JVM starts
  createLoadingWindow()

  // Start JVM
  startJavaProcess(cfg)

  // Wait for control plane
  try {
    await waitForControlPlane()
    console.log('[main] Control plane is ready, creating main window')
    createMainWindow()
  } catch (err) {
    console.error('[main] Failed to start control plane:', err)
    showError(`Le backend Java n'a pas démarré correctement.\n\n${err instanceof Error ? err.message : String(err)}`)
    app.quit()
  }
})

ipcMain.on('quit-app', () => {
  app.quit()
})

app.on('will-quit', () => {
  stopJavaProcess()
})

app.on('window-all-closed', () => {
  app.quit()
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createMainWindow()
  }
})
