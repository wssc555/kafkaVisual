/**
 * Electron 主进程壳。
 *
 * 结构：
 * ├─ app.setName('com.kafkaviz.frontend')   ← 必须在任何 getPath 之前（数据目录兼容）
 * ├─ StaticServer：127.0.0.1 随机端口托管 dist/（仅打包态）
 * ├─ BackendManager：port / child / startupErrors / backend.log
 * ├─ ipcMain：backend:port | startup:errors | backend:readLog | app:environment
 * ├─ single instance：requestSingleInstanceLock + second-instance → focus
 * └─ before-quit：child.kill() + 静态服务器 close()
 *
 * 静默契约（漏掉不会报错，只会表现为启动面板一直转圈 / 日志提示不准 / 库落在 ./data）：
 * 1. spawn 参数 `--server.port=0 --announce-port --app.db-path=<userData>` 一字不能改；
 * 2. stdout 用前缀扫描 `PORT=`（不是首行），关闭时仍未握手要记 startupErrors；
 * 3. `backend:readLog` 只回尾部 500 行。
 */
import {app, BrowserWindow, dialog, ipcMain} from 'electron'
import * as fs from 'node:fs'
import * as path from 'node:path'
import {BackendManager} from './backend'
import {startStaticServer, type StaticServer} from './static-server'

/**
 * 数据目录兼容的**唯一关键点**。
 *
 * Electron 的 `app.getPath('userData')` 目录名由 `app.getName()` 决定
 * （`package.json` 的 `productName` ?? `name`），**与 electron-builder 的 appId 无关**。
 * 本项目 productName 是 "Kafka Visualizer"，不干预的话三平台都会落到
 * `Kafka Visualizer/`，老用户升级后会「集群配置/归档/凭据全部失踪」。
 *
 * 必须在任何 `getPath()` 调用之前执行（`userData` 路径在首次 getPath 时定型）。
 */
app.setName('com.kafkaviz.frontend')

/** 开发态前端地址（vite dev server，/api 由 vite proxy 转 8080） */
const DEV_SERVER_URL = process.env.ELECTRON_START_URL ?? 'http://localhost:5173'

let mainWindow: BrowserWindow | null = null
let staticServer: StaticServer | null = null
let backend: BackendManager | null = null

/**
 * sidecar 资源根目录：打包态 = `<安装目录>/resources`（electron-builder 的
 * extraResources 把 app.jar / runtime 直接放在这里，免安装版同理）；
 * 开发态 = 仓库级 staging 目录 `build/resources`（由手工或 CI 准备，通常为空）。
 */
function resolveResourcesRoot(): string {
    if (app.isPackaged) return process.resourcesPath
    return path.normalize(path.join(__dirname, '..', '..', 'build', 'resources'))
}

/**
 * 渲染产物目录：开发态 = `frontend/dist`；打包态优先 `app.asar/dist`
 * （`asarUnpack: dist/**` 让 Electron 的 fs 层透明重定向到 app.asar.unpacked）。
 */
function resolveRendererDir(): string {
    if (!app.isPackaged) return path.normalize(path.join(__dirname, '..', 'dist'))
    const packed = path.join(app.getAppPath(), 'dist')
    if (fs.existsSync(path.join(packed, 'index.html'))) return packed
    return path.join(process.resourcesPath, 'app.asar.unpacked', 'dist')
}

async function createMainWindow(): Promise<BrowserWindow> {
    const win = new BrowserWindow({
        title: 'Kafka Visualizer',
        width: 1280,
        height: 800,
        minWidth: 960,
        minHeight: 640,
        resizable: true,
        fullscreen: false,
        show: false,
        backgroundColor: '#ffffff',
        webPreferences: {
            // 单一出口的桥接层：渲染层只能看到 preload 白名单里的 4 个方法
            preload: path.join(__dirname, 'preload.cjs'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true,
            spellcheck: false,
        },
    })

    mainWindow = win
    win.once('ready-to-show', () => win.show())
    win.on('closed', () => {
        if (mainWindow === win) mainWindow = null
    })

    // 无外链需求：禁止弹新窗口，并只放行 dev server / 静态服务器自身 origin 的导航
    win.webContents.setWindowOpenHandler(() => ({action: 'deny'}))
    win.webContents.on('will-navigate', (event, url) => {
        if (!isInternalUrl(url)) event.preventDefault()
    })

    if (app.isPackaged) {
        staticServer = await startStaticServer(resolveRendererDir())
        await win.loadURL(`${staticServer.origin}/`)
    } else {
        await win.loadURL(DEV_SERVER_URL)
    }
    return win
}

/** 导航白名单：dev server 或本次会话启动的静态服务器 origin */
function isInternalUrl(target: string): boolean {
    try {
        const url = new URL(target)
        const allowed = new Set<string>()
        if (staticServer) allowed.add(new URL(staticServer.origin).origin)
        if (!app.isPackaged) allowed.add(new URL(DEV_SERVER_URL).origin)
        return allowed.has(url.origin)
    } catch {
        return false
    }
}

async function bootstrap(): Promise<void> {
    backend = new BackendManager({
        resourcesRoot: resolveResourcesRoot(),
        // 数据目录路径保持不变（见文件顶部 app.setName 说明）
        userDataDir: app.getPath('userData'),
        packaged: app.isPackaged,
    })
    // 先拉起 sidecar 再建窗口：Spring Boot 启动 5–30s，越早开始越好
    backend.start()
    await createMainWindow()
}

// ---- IPC：三个通道 + environment 环境探测通道（供渲染层区分 dev/prod）----

ipcMain.handle('backend:port', () => backend?.getPort() ?? null)
ipcMain.handle('startup:errors', () => backend?.getStartupErrors() ?? [])
ipcMain.handle('backend:readLog', () => backend?.readLogTail() ?? [])
/**
 * dev 模式不准备 sidecar 资源也不启动静态服务器，前端若照常轮询端口会白等 60s。
 * 渲染层据此直接走 vite proxy（等价于浏览器形态）。
 */
ipcMain.handle('app:environment', () => ({dev: !app.isPackaged, version: app.getVersion()}))

// ---- 单实例：二次启动聚焦已有窗口 ----

const gotSingleInstanceLock = app.requestSingleInstanceLock()
if (!gotSingleInstanceLock) {
    app.quit()
} else {
    app.on('second-instance', () => {
        if (!mainWindow) return
        if (mainWindow.isMinimized()) mainWindow.restore()
        if (!mainWindow.isVisible()) mainWindow.show()
        mainWindow.focus()
    })

    app.whenReady().then(bootstrap).catch((e: unknown) => {
        const msg = e instanceof Error ? e.stack ?? e.message : String(e)
        console.error('[electron] bootstrap failed:', msg)
        // 窗口都没起来时没有可展示的 UI：用系统弹框留一个第一现场
        // （release 无控制台，弹框是用户能看到启动失败原因的第一现场）
        dialog.showErrorBox('Kafka Visualizer 启动失败', msg)
        app.quit()
    })

    // 关掉窗口即退出（含 macOS），避免 sidecar 长驻
    app.on('window-all-closed', () => app.quit())

    // 退出回收：kill sidecar + 释放静态服务器端口
    app.on('before-quit', () => backend?.stop())
    app.on('will-quit', () => {
        void staticServer?.close()
        staticServer = null
    })
}
