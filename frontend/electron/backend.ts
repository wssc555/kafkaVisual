/**
 * sidecar 后端管理。
 *
 * 职责：
 * 1. 从 resources 探测 `app.jar` + `runtime/bin/java(.exe)`，缺失时降级
 *    （dev 跳过 spawn / release 记启动错误）；
 * 2. spawn 参数固定为 `java -jar app.jar --server.port=0 --announce-port --app.db-path=<userData>`
 *    —— 后端 `PortAnnouncer` 与 `AppPaths` 的约定，一字不能改；
 * 3. stdout 逐行写 `backend.log` 并扫描 `PORT=` 前缀握手（**不是首行**语义：
 *    Spring banner / 启动日志先于协议行出现）；
 * 4. stdout 关闭仍未握手 → 追加「后端进程提前退出」到 startupErrors
 *    （前端启动失败弹框依赖这条）；
 * 5. stderr 全量落盘；
 * 6. `readLogTail()` 只返回尾部 500 行（前端 `TAIL_LINES=500` 用 `lines.length >= 500`
 *    判断「已截断」提示，壳侧必须保持同一截断值）；
 * 7. 退出时 kill 子进程（接受强杀：JVM 的 shutdown hook 不执行，但句柄由 OS 回收，
 *    无跨进程残留状态，强杀比进程残留卡端口/锁库好）。
 *
 * 日志格式与 Rust 版一致：`[<秒级 unix 时间戳>] [<tag>] <line>`，
 * tag 为 `electron`（壳侧）/ `java`（stdout）/ `java-err`（stderr）。
 */
import {type ChildProcess, spawn} from 'node:child_process'
import * as fs from 'node:fs'
import * as path from 'node:path'
import * as readline from 'node:readline'

/** 与前端 `StartupDiagnostics.vue` 的 TAIL_LINES 必须一致 */
export const TAIL_LINES = 500

export interface BackendOptions {
    /** 打包态 resources 根目录（`app.jar` / `runtime` 所在）；dev 为仓库级 build/resources */
    resourcesRoot: string
    /** 桌面模式数据目录（`app.getPath('userData')`），作为 `--app.db-path` 传给后端 */
    userDataDir: string
    /** 是否打包态：资源缺失时只有打包态算启动错误（dev 缺资源是正常形态） */
    packaged: boolean
}

export class BackendManager {
    /** 后端实际监听端口；null = 尚未握手（前端轮询等待）或未拉起 */
    private port: number | null = null
    private child: ChildProcess | null = null
    /** 启动期错误：资源缺失 / spawn 失败 / 握手 EOF 无端口，供前端弹框展示 */
    private readonly startupErrors: string[] = []
    private readonly logFile: string
    private logWritable = false

    constructor(private readonly opts: BackendOptions) {
        this.logFile = path.join(opts.userDataDir, 'logs', 'backend.log')
        try {
            fs.mkdirSync(path.dirname(this.logFile), {recursive: true})
            this.logWritable = true
        } catch {
            // 日志不可写不影响握手等关键逻辑，后续写日志静默降级
        }
    }

    /** 前端握手入口：返回 sidecar 实际监听端口；未就绪返回 null（「还没好」是正常中间态） */
    getPort(): number | null {
        return this.port
    }

    /** 启动期错误清单（拷贝，避免调用方改到内部状态） */
    getStartupErrors(): string[] {
        return [...this.startupErrors]
    }

    /** 诊断日志尾部（默认 500 行）；文件不存在（dev 未 spawn）返回空数组 */
    readLogTail(limit: number = TAIL_LINES): string[] {
        try {
            const content = fs.readFileSync(this.logFile, 'utf8')
            const lines = content.split(/\r?\n/)
            // Rust `str::lines()` 不计末尾换行产生的空串，这里对齐
            while (lines.length > 0 && lines[lines.length - 1] === '') lines.pop()
            return lines.slice(-limit)
        } catch {
            return []
        }
    }

    /** 探测并拉起后端 fat jar；资源缺失 / spawn 失败均不抛错（壳仍可启动并展示前端） */
    start(): void {
        const jar = path.normalize(path.join(this.opts.resourcesRoot, 'app.jar'))
        const java = path.normalize(path.join(this.opts.resourcesRoot, 'runtime', 'bin', javaBinName()))
        const jarExists = fs.existsSync(jar)
        const javaExists = fs.existsSync(java)

        this.log('electron', `resources_root=${this.opts.resourcesRoot} jar=${jar} jar_exists=${jarExists} java=${java} java_exists=${javaExists}`)

        if (!jarExists || !javaExists) {
            this.log('electron', 'resources missing — dev/proxy mode, no sidecar spawned')
            if (this.opts.packaged) {
                // 安装包损坏才算出错；dev 模式（不准备 sidecar 产物）是正常形态
                this.startupErrors.push(
                    `后端资源缺失: jar=${jar} (存在=${jarExists}), java=${java} (存在=${javaExists})。安装包可能不完整,请重新安装/解压。`,
                )
            }
            return
        }

        try {
            fs.mkdirSync(this.opts.userDataDir, {recursive: true})
        } catch {
            // 目录已存在或不可创建 —— 让后端自己报错，日志里能看到
        }

        const dbArg = `--app.db-path=${this.opts.userDataDir}`
        this.log('electron', `spawning: ${java} -jar ${jar} --server.port=0 --announce-port ${dbArg}`)

        let child: ChildProcess
        try {
            child = spawn(java, ['-jar', jar, '--server.port=0', '--announce-port', dbArg], {
                // Windows:java.exe 是控制台程序，不带 windowsHide 会弹黑色命令行框
                windowsHide: true,
                stdio: ['ignore', 'pipe', 'pipe'],
            })
        } catch (e) {
            const msg = e instanceof Error ? e.message : String(e)
            this.log('electron', `spawn failed: ${msg}`)
            this.startupErrors.push(`后端进程启动失败: ${msg}`)
            return
        }

        this.child = child
        this.log('electron', `spawned pid=${child.pid ?? 'unknown'}`)

        child.on('error', (e) => {
            // ENOENT / EACCES（可执行位丢失）等异步错误
            this.log('electron', `process error: ${e.message}`)
            this.startupErrors.push(`后端进程启动失败: ${e.message}`)
        })
        child.on('exit', (code, signal) => {
            this.log('electron', `backend exited code=${code ?? 'null'} signal=${signal ?? 'null'}`)
            if (this.child === child) this.child = null
        })

        if (child.stdout) this.watchStdout(child.stdout)
        if (child.stderr) this.watchStderr(child.stderr)
    }

    /** 退出回收：kill + 等待，避免 JVM 残留 */
    stop(): void {
        const child = this.child
        this.child = null
        if (!child || child.exitCode !== null || child.signalCode !== null) return
        this.log('electron', `killing backend sidecar pid=${child.pid ?? 'unknown'}`)
        try {
            child.kill()
        } catch (e) {
            this.log('electron', `kill failed: ${e instanceof Error ? e.message : String(e)}`)
        }
    }

    /**
     * stdout：逐行落盘 + 前缀扫描 `PORT=<port>`。
     * 用前缀而非「首行」—— Spring 启动日志会先于协议行出现（见 PortAnnouncer javadoc）。
     */
    private watchStdout(stream: NodeJS.ReadableStream): void {
        let announced = false
        const rl = readline.createInterface({input: stream})
        rl.on('line', (line) => {
            this.log('java', line)
            const trimmed = line.trim()
            if (!trimmed.startsWith('PORT=')) return
            const raw = trimmed.slice('PORT='.length).trim()
            const port = /^\d+$/.test(raw) ? Number.parseInt(raw, 10) : Number.NaN
            if (Number.isInteger(port) && port > 0 && port < 65536) {
                announced = true
                this.port = port
                this.log('electron', `handshake ok, port=${port}`)
            } else {
                this.log('electron', `invalid PORT value "${raw}"`)
            }
        })
        rl.on('close', () => {
            this.log('electron', 'backend stdout closed — process exited before/after handshake (see java lines above)')
            // stdout 关闭仍未握手 = 后端进程已死（Spring 启动失败/被杀）：记入启动错误，
            // java 侧真实异常就在同一文件的 java / java-err 行里
            if (!announced) {
                this.startupErrors.push(
                    '后端进程提前退出,未能完成端口握手(常见原因:JVM 启动异常)。详见 backend.log 中 java/java-err 行。',
                )
            }
        })
    }

    /** stderr：全量落盘（「找不到或无法找到主类」等启动异常走这里） */
    private watchStderr(stream: NodeJS.ReadableStream): void {
        const rl = readline.createInterface({input: stream})
        rl.on('line', (line) => this.log('java-err', line))
        rl.on('close', () => this.log('electron', 'backend stderr closed'))
    }

    /** 带秒级 unix 时间戳的日志行；日志文件不可写时静默 no-op */
    private log(tag: string, line: string): void {
        if (!this.logWritable) return
        const secs = Math.floor(Date.now() / 1000)
        try {
            fs.appendFileSync(this.logFile, `[${secs}] [${tag}] ${line}\n`)
        } catch {
            // 单次写失败（被杀软/编辑器短暂占用）不永久关闭日志，下一行继续尝试
        }
    }
}

/** jlink runtime 的 java 可执行文件，按平台取 `bin/java(.exe)` */
function javaBinName(): string {
    return process.platform === 'win32' ? 'java.exe' : 'java'
}
