/**
 * 渲染层唯一的壳能力出口（contextIsolation: true + sandbox: true）。
 *
 * 只用白名单方法暴露 IPC，不暴露 ipcRenderer 本体 —— 渲染层无法构造任意 channel。
 * 与 `api-types.ts` 的 ElectronAPI 必须逐字对齐。
 */
import {contextBridge, ipcRenderer} from 'electron'
import type {ElectronAPI, ElectronEnvironment} from './api-types'

const electronAPI: ElectronAPI = {
    isElectron: true,
    backendPort: () => ipcRenderer.invoke('backend:port') as Promise<number | null>,
    startupErrors: () => ipcRenderer.invoke('startup:errors') as Promise<string[]>,
    readBackendLog: () => ipcRenderer.invoke('backend:readLog') as Promise<string[]>,
    environment: () => ipcRenderer.invoke('app:environment') as Promise<ElectronEnvironment>,
}

contextBridge.exposeInMainWorld('electronAPI', electronAPI)
