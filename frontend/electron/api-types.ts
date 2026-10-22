/**
 * Electron preload 的桥接契约（preload 与渲染层共用的唯一类型定义）。
 *
 * 只放类型、不发运行时代码（`import type` 引用，打包时被擦除）。
 * 新增通道必须同步三处：本文件、`preload.ts` 的 exposeInMainWorld、`main.ts` 的 ipcMain.handle，
 * 并在 `src/api/__tests__/initApiBaseUrl.spec.ts` 覆盖（防「渲染层调了不存在的 channel」）。
 */
export interface ElectronEnvironment {
    /** true = 未打包（Electron 开发态，未拉起 sidecar，应走 vite proxy） */
    dev: boolean
    version: string
}

export interface ElectronAPI {
    /** 桌面壳标记，渲染层据此判断是否进入握手分支 */
    readonly isElectron: true
    /** sidecar 实际监听端口；未就绪为 null（前端轮询等待） */
    backendPort(): Promise<number | null>
    /** 壳侧收集的启动期错误（资源缺失 / spawn 失败 / 提前退出） */
    startupErrors(): Promise<string[]>
    /** backend.log 尾部 500 行 */
    readBackendLog(): Promise<string[]>
    /** 运行环境：dev 模式直接走 vite proxy，不再轮询端口 */
    environment(): Promise<ElectronEnvironment>
}
