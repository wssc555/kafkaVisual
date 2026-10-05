//! Tauri 桌面壳(任务 021)。
//!
//! 职责(设计 §4.1–§4.3):
//! 1. 打包态:从 resources 拉起后端 sidecar(`runtime/bin/java -jar app.jar
//!    --server.port=0 --announce-port --app.db-path=<OS app-data>`),
//!    扫描 stdout 找 `PORT=<port>` 握手行,经 `backend_port` command 暴露给前端;
//! 2. 开发态(tauri dev 不打 resources)或拉起失败:state 端口为 None,
//!    前端轮询超时后保持 `'/api'` 走 vite proxy → 8080,开发工作流不变;
//! 3. 二次启动聚焦已有窗口(single-instance);
//! 4. 进程退出(窗口关闭)时 kill sidecar。
//!
//! 诊断(排障闭环):java 的 stdout/stderr 与壳侧关键事件全部落盘到
//! `<app-data>/logs/backend.log`(release 版无控制台,这是唯一可见的诊断出口)。

use std::fs::File;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};

use tauri::{AppHandle, Manager, RunEvent, State};

/// CREATE_NO_WINDOW:java.exe 是控制台程序,不带此标志会在 Windows 上弹黑色命令行框。
#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// sidecar 后端进程句柄 + 握手端口 + 启动期错误收集。
///
/// `port=None` 表示后端尚未就绪或未拉起:前端 `backend_port` 返回 null,
/// 由前端轮询等待(桌面模式)或走 proxy 回退(开发模式)。
/// `startup_errors` 供前端启动后拉取并以弹框展示(release 无控制台,弹框是
/// 用户能看到启动失败原因的第一现场;完整细节仍落 backend.log)。
struct BackendState {
    port: Mutex<Option<u16>>,
    child: Mutex<Option<Child>>,
    startup_errors: Mutex<Vec<String>>,
}

/// 前端握手入口(设计 §4.2):返回 sidecar 实际监听端口;未就绪时返回 null。
///
/// 后端从 spawn 到打印 PORT= 需要 5–30s(Spring Boot 启动),前端会轮询本命令
/// 直到拿到端口或超时,因此这里不抛错、不阻塞 —— 「还没好」是正常中间态。
#[tauri::command]
fn backend_port(state: State<'_, BackendState>) -> Option<u16> {
    *state.port.lock().expect("BackendState.port poisoned")
}

/// 启动期错误清单(资源缺失 / spawn 失败 / 握手 EOF 无端口),供前端弹框展示。
#[tauri::command]
fn startup_errors(state: State<'_, BackendState>) -> Vec<String> {
    state
        .startup_errors
        .lock()
        .expect("BackendState.startup_errors poisoned")
        .clone()
}

/// 返回 sidecar 诊断日志(backend.log)的尾部,供前端启动面板实时展示。
/// 文件不存在(开发模式/尚未 spawn)返回空清单。
/// 注意:command 宏要求 AppHandle 按值捕获(State 才可用引用形式)。
#[tauri::command]
fn read_backend_log(app: AppHandle) -> Vec<String> {
    let Some(dir) = app.path().app_data_dir().ok().map(|d| d.join("logs")) else {
        return Vec::new();
    };
    let Ok(content) = std::fs::read_to_string(dir.join("backend.log")) else {
        return Vec::new();
    };
    let mut lines: Vec<&str> = content.lines().collect();
    let start = lines.len().saturating_sub(500);
    lines.split_off(start).into_iter().map(String::from).collect()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // single-instance 官方要求注册为第一个插件;二次启动 → 聚焦主窗口(设计 §4.3)。
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_focus();
            }
        }))
        // 注册 IPC 命令:漏掉的话前端 invoke('backend_port') 会报 unknown command,
        // 端口握手永远失败(死代码警告正是这个缺失的信号)。
        .invoke_handler(tauri::generate_handler![
            backend_port,
            startup_errors,
            read_backend_log
        ])
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }
            spawn_backend(app.handle())?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            // 窗口关闭 → 默认全部退出 → Exit 事件 → 回收 sidecar(设计 §4.3)。
            if let RunEvent::Exit = event {
                kill_backend(app.state::<BackendState>());
            }
        });
}

/// 打开 sidecar 诊断日志:`<app-data>/logs/backend.log`(追加模式)。
/// 打不开(权限/路径问题)时返回 None,后续写日志静默降级为 no-op。
fn open_backend_log(app: &AppHandle) -> Option<Arc<Mutex<File>>> {
    let dir = app.path().app_data_dir().ok()?.join("logs");
    std::fs::create_dir_all(&dir).ok()?;
    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join("backend.log"))
        .ok()?;
    Some(Arc::new(Mutex::new(file)))
}

/// 带秒级 unix 时间戳的日志行;`tag` 区分来源(tauri / java / java-err)。
/// `file` 为 None(日志文件打不开)时静默 no-op —— 握手等关键逻辑不依赖日志可写。
fn log_line(file: &Option<Arc<Mutex<File>>>, tag: &str, line: &str) {
    let Some(f) = file else { return };
    if let Ok(mut f) = f.lock() {
        let secs = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let _ = writeln!(f, "[{secs}] [{tag}] {line}");
    }
}

/// 探测并拉起后端 fat jar。
///
/// 打包态资源布局(`tauri.conf.json` 的 `bundle.resources`):
/// `<resource_dir>/resources/app.jar` 与 `<resource_dir>/resources/runtime/`(jlink JRE)。
/// resource_dir 在 Windows/Linux prod 下即 exe 所在目录 —— 便携版(zip 解压)与
/// 安装版遵循同一布局。
///
/// 资源缺失时(tauri dev 不打 resources)不视为错误:保持空 state,前端轮询超时
/// 后走 proxy。spawn 失败同理 —— 壳仍可启动并展示前端,错误细节落 backend.log。
fn spawn_backend(app: &AppHandle) -> Result<(), tauri::Error> {
    let resource_dir = to_plain_path(&app.path().resource_dir()?);
    let jar = resource_dir.join("resources").join("app.jar");
    let java = java_executable(&resource_dir);
    let log = open_backend_log(app);

    log_line(
        &log,
        "tauri",
        &format!(
            "resource_dir={} jar={} jar_exists={} java={} java_exists={}",
            resource_dir.display(),
            jar.display(),
            jar.is_file(),
            java.display(),
            java.is_file()
        ),
    );

    if !jar.is_file() || !java.is_file() {
        log_line(
            &log,
            "tauri",
            "resources missing — dev/proxy mode, no sidecar spawned",
        );
        log::info!(
            "backend jar or jlink runtime not found (jar={}, java={}) — dev/proxy mode, no sidecar",
            jar.display(),
            java.display()
        );
        // release 下资源缺失 = 安装包损坏,属启动错误;dev 模式(tauri dev 不打 resources)
        // 是正常形态,不进错误清单。前端超时后经 startup_errors 拉取弹框。
        let mut errors = Vec::new();
        if !cfg!(debug_assertions) {
            errors.push(format!(
                "后端资源缺失: jar={} (存在={}), java={} (存在={})。安装包可能不完整,请重新安装/解压。",
                jar.display(),
                jar.is_file(),
                java.display(),
                java.is_file()
            ));
        }
        app.manage(BackendState {
            port: Mutex::new(None),
            child: Mutex::new(None),
            startup_errors: Mutex::new(errors),
        });
        return Ok(());
    }

    // OS app-data 目录(由 identifier com.kafkaviz.frontend 决定),与 AppPaths 约定对接。
    let db_dir = app.path().app_data_dir()?;
    let db_arg = format!("--app.db-path={}", db_dir.display());
    log_line(
        &log,
        "tauri",
        &format!(
            "spawning: {} -jar {} --server.port=0 --announce-port {}",
            java.display(),
            jar.display(),
            db_arg
        ),
    );

    let mut cmd = Command::new(&java);
    cmd.arg("-jar")
        .arg(&jar)
        .arg("--server.port=0")
        .arg("--announce-port")
        .arg(db_arg)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    // 不弹命令行框;同时 stderr 改为捕获(写日志),不再 inherit(窗口子系统下无处可去)。
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    let mut child = match cmd.spawn() {
        Ok(child) => child,
        Err(e) => {
            log_line(&log, "tauri", &format!("spawn failed: {e}"));
            log::error!("failed to spawn backend sidecar: {e}");
            app.manage(BackendState {
                port: Mutex::new(None),
                child: Mutex::new(None),
                startup_errors: Mutex::new(vec![format!("后端进程启动失败: {e}")]),
            });
            return Ok(());
        }
    };
    log_line(&log, "tauri", &format!("spawned pid={:?}", child.id()));

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    app.manage(BackendState {
        port: Mutex::new(None),
        child: Mutex::new(Some(child)),
        startup_errors: Mutex::new(Vec::new()),
    });

    // stdout 线程:逐行写日志 + 扫描 PORT=<port> 握手行(不用「首行」语义 ——
    // Spring banner/启动日志先于协议行出现,前缀扫描才稳,见 PortAnnouncer javadoc)。
    if let Some(stdout) = stdout {
        let handle = app.clone();
        let log_out = log.clone();
        thread::spawn(move || {
            let reader = BufReader::new(stdout);
            let mut announced = false;
            for line in reader.lines().map_while(Result::ok) {
                log_line(&log_out, "java", &line);
                if let Some(port) = line.trim().strip_prefix("PORT=") {
                    match port.trim().parse::<u16>() {
                        Ok(p) => {
                            announced = true;
                            *handle
                                .state::<BackendState>()
                                .port
                                .lock()
                                .expect("BackendState.port poisoned") = Some(p);
                            log_line(&log_out, "tauri", &format!("handshake ok, port={p}"));
                            log::info!("backend ready on port {p}");
                        }
                        Err(e) => {
                            log_line(&log_out, "tauri", &format!("invalid PORT value {port:?}: {e}"))
                        }
                    }
                }
            }
            log_line(
                &log_out,
                "tauri",
                "backend stdout closed — process exited before/after handshake (see java lines above)",
            );
            // stdout 关闭仍未握手 = 后端进程已死(Spring 启动失败/被杀),
            // 记入启动错误供前端弹框;java 侧的真实异常就在本文件上方 java/java-err 行里。
            if !announced {
                if let Ok(mut errs) = handle
                    .state::<BackendState>()
                    .startup_errors
                    .lock()
                {
                    errs.push("后端进程提前退出,未能完成端口握手(常见原因:JVM 启动异常)。详见 backend.log 中 java/java-err 行。".to_string());
                }
                log::error!("backend exited before handshake");
            }
        });
    }

    // stderr 线程:全量落盘。java 的启动异常(如「找不到或无法找到主类」)走 stderr。
    if let Some(stderr) = stderr {
        let log_err = log.clone();
        thread::spawn(move || {
            let reader = BufReader::new(stderr);
            for line in reader.lines().map_while(Result::ok) {
                log_line(&log_err, "java-err", &line);
            }
            log_line(&log_err, "tauri", "backend stderr closed");
        });
    }

    Ok(())
}

/// jlink runtime 的 java 可执行文件,按平台取 `bin/java(.exe)`。
fn java_executable(resource_dir: &Path) -> PathBuf {
    let bin = resource_dir.join("resources").join("runtime").join("bin");
    if cfg!(target_os = "windows") {
        bin.join("java.exe")
    } else {
        bin.join("java")
    }
}

/// 剥掉 Windows verbatim 前缀(`\\?\C:\...` → `C:\...`;UNC 形式 → `\\server\...`)。
///
/// Tauri 的 resource_dir 可能返回 verbatim 路径 —— JVM launcher(JLI)对自身路径
/// 做字符串拼接定位 `..\lib\jvm.dll`,verbatim 前缀会破坏其解析,表现为
/// java.exe 拉起后**立即静默退出且 stdout/stderr 零输出**(诊断日志实证)。
/// 仅当结果短于 MAX_PATH 时安全;本应用安装/解压路径远短于该值。
#[cfg(target_os = "windows")]
fn to_plain_path(p: &Path) -> PathBuf {
    let s = p.as_os_str().to_string_lossy();
    if let Some(rest) = s.strip_prefix(r"\\?\UNC\") {
        PathBuf::from(format!(r"\\{rest}"))
    } else if let Some(rest) = s.strip_prefix(r"\\?\") {
        PathBuf::from(rest)
    } else {
        p.to_path_buf()
    }
}

#[cfg(not(target_os = "windows"))]
fn to_plain_path(p: &Path) -> PathBuf {
    p.to_path_buf()
}

/// 退出时回收 sidecar:kill + wait,避免 JVM 残留(设计 §4.3「关窗口后端进程回收」)。
///
/// JVM 侧的有序关闭(AdminClient/Producer/ConsumerPool/Curator 的 shutdown hook)
/// 在强杀下不会执行 —— 但这些资源句柄由 OS 兜底回收,ZK 会话随连接断开过期,
/// 无跨进程残留状态,强杀可接受(比进程残留卡端口/锁库好)。
fn kill_backend(state: State<'_, BackendState>) {
    if let Some(mut child) = state
        .child
        .lock()
        .expect("BackendState.child poisoned")
        .take()
    {
        log::info!("killing backend sidecar pid={:?}", child.id());
        let _ = child.kill();
        let _ = child.wait();
    }
}
