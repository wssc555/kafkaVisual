/**
 * 打包态前端静态托管。
 *
 * 为什么不用 `loadFile(dist/index.html)`：
 * 1. Vite 产物是 ESM，`file://` 文档的 origin 为 opaque，模块脚本一律走 CORS 校验
 *    → `Access to script ... blocked by CORS policy`，直接白屏；
 * 2. `createWebHistory()` 在 `file://` 下 pushState 抛 SecurityError；
 * 3. `base` 默认 `/` 导致绝对路径资源 404。
 *
 * 起一个只监听 127.0.0.1 的静态服务器（端口交 OS 分配）换来前端零改动，
 * 且 dev/prod 链路同构（dev 走 vite dev server，prod 走这里）。
 * SPA 回退：无扩展名 / 目录请求一律返回 index.html，保证 history 路由刷新可用。
 */
import * as fs from 'node:fs'
import * as http from 'node:http'
import * as path from 'node:path'

/** 仅覆盖 Vite 产物实际会出现的类型；未知扩展名按 application/octet-stream 处理 */
const MIME_TYPES: Record<string, string> = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.map': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.ico': 'image/x-icon',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf',
    '.eot': 'application/vnd.ms-fontobject',
    '.wasm': 'application/wasm',
    '.txt': 'text/plain; charset=utf-8',
}

/**
 * 内容安全策略。
 *
 * `connect-src` 保留 `127.0.0.1:*` / `localhost:*` —— 后端 sidecar 端口随机，
 * 且与本静态服务器不同源。
 */
export const CONTENT_SECURITY_POLICY = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    "connect-src 'self' http://127.0.0.1:* http://localhost:*",
].join('; ')

export interface StaticServer {
    /** 形如 `http://127.0.0.1:<port>`，交给 `win.loadURL()` */
    origin: string
    close(): Promise<void>
}

/**
 * 在 127.0.0.1 的随机端口托管 `rootDir`（`dist/`）。
 *
 * 约束：只监听 127.0.0.1、端口传 0 让 OS 分配、
 * 随窗口关闭一起 close()。
 */
export function startStaticServer(rootDir: string): Promise<StaticServer> {
    const root = path.resolve(rootDir)
    const indexFile = path.join(root, 'index.html')

    const server = http.createServer((req, res) => {
        const method = req.method ?? 'GET'
        if (method !== 'GET' && method !== 'HEAD') {
            res.writeHead(405, {'Content-Type': 'text/plain; charset=utf-8'})
            res.end('Method Not Allowed')
            return
        }

        let pathname: string
        try {
            pathname = decodeURIComponent(new URL(req.url ?? '/', 'http://127.0.0.1').pathname)
        } catch {
            // 非法百分号转义
            res.writeHead(400, {'Content-Type': 'text/plain; charset=utf-8'})
            res.end('Bad Request')
            return
        }

        const resolved = resolveInside(root, pathname)
        if (resolved === null) {
            res.writeHead(403, {'Content-Type': 'text/plain; charset=utf-8'})
            res.end('Forbidden')
            return
        }

        const file = pickFile(resolved, root, indexFile, pathname)
        if (file === null) {
            res.writeHead(404, {'Content-Type': 'text/plain; charset=utf-8'})
            res.end('Not Found')
            return
        }

        let stat: fs.Stats
        try {
            stat = fs.statSync(file)
        } catch {
            res.writeHead(404, {'Content-Type': 'text/plain; charset=utf-8'})
            res.end('Not Found')
            return
        }

        const ext = path.extname(file).toLowerCase()
        const headers: http.OutgoingHttpHeaders = {
            'Content-Type': MIME_TYPES[ext] ?? 'application/octet-stream',
            'Content-Length': stat.size,
            'Content-Security-Policy': CONTENT_SECURITY_POLICY,
            'X-Content-Type-Options': 'nosniff',
            // 带 hash 的产物可长缓存；index.html 必须每次回源，否则升级后仍加载旧入口
            'Cache-Control': file === indexFile ? 'no-store' : 'public, max-age=31536000, immutable',
        }

        if (method === 'HEAD') {
            res.writeHead(200, headers)
            res.end()
            return
        }

        res.writeHead(200, headers)
        const stream = fs.createReadStream(file)
        stream.on('error', () => res.destroy())
        stream.pipe(res)
    })

    return new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(0, '127.0.0.1', () => {
            const addr = server.address()
            if (addr === null || typeof addr === 'string') {
                server.close()
                reject(new Error('静态服务器未能获取监听端口'))
                return
            }
            const origin = `http://127.0.0.1:${addr.port}`
            resolve({
                origin,
                close: () =>
                    new Promise<void>((done) => {
                        server.close(() => done())
                        // keep-alive 连接会拖住 close 回调，直接断开
                        server.closeAllConnections?.()
                    }),
            })
        })
    })
}

/**
 * 把 URL pathname 解析到 root 内部；越界（`..` / 绝对路径逃逸）返回 null。
 * `path.resolve(root, '.' + pathname)` 把 `/assets/a.js` 变成 `<root>/assets/a.js`，
 * 再由前缀校验兜住 `../../etc/passwd` 这类输入。
 */
function resolveInside(root: string, pathname: string): string | null {
    const resolved = path.resolve(root, `.${pathname}`)
    if (resolved === root) return resolved
    return resolved.startsWith(root + path.sep) ? resolved : null
}

/**
 * 选最终文件：命中真实文件就用它；否则按 SPA 规则回退 index.html。
 * 带扩展名的请求（`/assets/x.js`）不回退 —— 缺失就是 404，避免把 HTML 当 JS 返回。
 */
function pickFile(resolved: string, root: string, indexFile: string, pathname: string): string | null {
    try {
        if (fs.statSync(resolved).isFile()) return resolved
    } catch {
        // 不存在，继续走回退判定
    }
    if (path.extname(pathname) !== '') return null
    return fs.existsSync(indexFile) ? indexFile : null
}
