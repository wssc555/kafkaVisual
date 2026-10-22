#!/usr/bin/env node
/**
 * 开发态一键起桌面壳（对应 Tauri 的 `pnpm tauri dev`）：
 *   1. 编译 electron 主进程/preload（esbuild，秒级）
 *   2. 起 vite dev server（--strictPort，固定 5173）
 *   3. 等 5173 可访问
 *   4. 以 ELECTRON_START_URL 拉起 electron（主进程 window 加载该地址，/api 由 vite proxy 转 8080）
 *
 * 注意：主进程代码改动需要重跑本命令（未接 watch —— 主进程热重启会反复杀 sidecar，
 * 收益不大）。渲染层有 HMR，改完即时生效。
 */
import {spawn} from 'node:child_process'
import {createRequire} from 'node:module'
import * as path from 'node:path'
import process from 'node:process'
import {fileURLToPath} from 'node:url'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const DEV_URL = 'http://localhost:5173'

/** @type {import('node:child_process').ChildProcess[]} */
const children = []
let exiting = false

/** 跨平台杀掉子进程树（vite 会派生 esbuild 服务进程，裸 kill 在 Windows 会留孤儿） */
function killTree(child) {
    if (!child || child.exitCode !== null || child.signalCode !== null) return
    if (process.platform === 'win32' && child.pid) {
        spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], {stdio: 'ignore'})
        return
    }
    child.kill('SIGTERM')
}

function shutdown(code) {
    if (exiting) return
    exiting = true
    for (const child of children) killTree(child)
    process.exit(code)
}

process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))

async function runNode(script, args = []) {
    await new Promise((resolve, reject) => {
        const child = spawn(process.execPath, [script, ...args], {cwd: root, stdio: 'inherit'})
        child.on('error', reject)
        child.on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`${script} exited with code ${code}`))))
    })
}

async function waitForServer(url, timeoutMs) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
        try {
            const res = await fetch(url)
            if (res.ok || res.status === 404) return
        } catch {
            // dev server 还没起来，继续等
        }
        await new Promise((resolve) => setTimeout(resolve, 300))
    }
    throw new Error(`等待 ${url} 超时（vite dev server 未就绪）`)
}

/** electron 包在 Node 下导出的是可执行文件路径；未安装二进制时给出可执行的修复提示 */
function resolveElectronBinary() {
    try {
        return require('electron')
    } catch (e) {
        throw new Error(
            `无法定位 Electron 可执行文件（${e instanceof Error ? e.message : String(e)}）。` +
                '通常是安装时跳过了 postinstall，请在 frontend/ 执行 `pnpm install` 重新下载。',
        )
    }
}

async function main() {
    await runNode(path.join(root, 'scripts/build-electron.mjs'))

    const electronBin = resolveElectronBinary()

    // vite 用 `node node_modules/vite/bin/vite.js` 起，避免 Windows 下 .cmd 的 shell 引号问题
    const viteBin = path.join(root, 'node_modules', 'vite', 'bin', 'vite.js')
    const vite = spawn(process.execPath, [viteBin, '--strictPort'], {cwd: root, stdio: 'inherit'})
    children.push(vite)
    vite.on('exit', (code) => {
        if (!exiting) {
            console.error(`[dev] vite 退出（code=${code}），关闭桌面壳`)
            shutdown(code ?? 1)
        }
    })

    await waitForServer(DEV_URL, 60_000)

    // 去掉 ELECTRON_RUN_AS_NODE：某些 CI/IDE 终端会预设它，导致 electron 以纯 Node 启动、
    // app 未定义直接报错（表现为「桌面壳起不来」且无有效日志）
    const env = {...process.env, ELECTRON_START_URL: DEV_URL}
    delete env.ELECTRON_RUN_AS_NODE

    const electron = spawn(electronBin, ['.'], {
        cwd: root,
        stdio: 'inherit',
        env,
    })
    children.push(electron)
    electron.on('exit', (code) => {
        if (!exiting) shutdown(code ?? 0)
    })
}

main().catch((e) => {
    console.error(`[dev] ${e instanceof Error ? e.message : String(e)}`)
    shutdown(1)
})
