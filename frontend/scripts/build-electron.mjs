#!/usr/bin/env node
/**
 * 把 `electron/` 下的 TS 打成两个 CJS 单文件：`dist-electron/main.cjs` / `preload.cjs`。
 *
 * 不用 tsc 直出的原因：
 * 1. `package.json` 是 `"type": "module"`，tsc 产出的 `.js` 会被 Node 当成 ESM；
 *    而 sandbox preload **不支持 ESM**（必须是单文件 CJS），主进程走 CJS 也最稳。
 * 2. esbuild 顺带把 `backend.ts` / `static-server.ts` / `api-types.ts` 内联，
 *    运行时只需要这两个文件（`external: ['electron']` 保留 require('electron')）。
 */
import {build} from 'esbuild'
import * as path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/** 两个入口共用的编译参数 */
const common = {
    bundle: true,
    platform: 'node',
    // Electron 45 自带 Node 22.x；与 CI 的 node-version: 22 对齐
    target: 'node22',
    format: 'cjs',
    // electron 由宿主运行时提供，其余全部内联
    external: ['electron'],
    sourcemap: false,
    logLevel: 'warning',
}

await build({
    ...common,
    entryPoints: [path.join(root, 'electron/main.ts')],
    outfile: path.join(root, 'dist-electron/main.cjs'),
})

await build({
    ...common,
    entryPoints: [path.join(root, 'electron/preload.ts')],
    outfile: path.join(root, 'dist-electron/preload.cjs'),
})

console.log('[build-electron] dist-electron/main.cjs + preload.cjs ready')
