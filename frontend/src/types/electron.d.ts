/**
 * Electron 桌面壳注入的全局桥接对象（由 `electron/preload.ts` 经 contextBridge 暴露）。
 *
 * 契约单一来源：`electron/api-types.ts`。浏览器 / vite dev 下 `window.electronAPI` 为 undefined。
 */
import type {ElectronAPI} from '../../electron/api-types'

declare global {
    interface Window {
        electronAPI?: ElectronAPI
    }
}

export {}
