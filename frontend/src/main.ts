import {createApp, h} from 'vue'
import ElementPlus, {ElMessageBox} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import i18n from './i18n'
import {initApiBaseUrl} from './api'
import {markReady} from './composables/bootLog'

const app = createApp(App)
app.use(ElementPlus)
app.use(i18n)
app.use(router)

/**
 * 启动错误弹框(桌面模式排障第一现场):
 * initApiBaseUrl 返回的非空列表 = sidecar 拉起/握手失败,以可滚动 pre 展示详情,
 * 并指向 backend.log 完整日志。
 */
const showStartupErrors = (errors: string[]) => {
  const t = i18n.global.t
  ElMessageBox.alert(
    h('div', null, [
      h('p', {style: 'margin:0 0 8px'}, t('app.startup.errorIntro')),
      h(
        'pre',
        {
          style:
            'max-height:280px;overflow:auto;white-space:pre-wrap;word-break:break-all;' +
            'margin:0;padding:10px;background:var(--el-fill-color-light);border-radius:4px;font-size:12px;line-height:1.5;',
        },
        errors.join('\n'),
      ),
      h('p', {style: 'margin:8px 0 0;font-size:12px;color:var(--el-text-color-secondary)'}, [
        t('app.startup.errorLogPath'),
      ]),
    ]),
    t('app.startup.errorTitle'),
    {confirmButtonText: t('common.gotIt'), type: 'error'},
  ).catch(() => {
    // 用户关闭即止
  })
}

// 挂载不再阻塞:App.vue 的 StartupDiagnostics 面板可视化握手等待期(后端日志实时滚动),
// 握手结束(成功或超时)后 markReady → 面板消失 → 正常启动序列。
app.mount('#app')

initApiBaseUrl().then((startupErrors) => {
  markReady(startupErrors)
  if (startupErrors.length > 0) {
    showStartupErrors(startupErrors)
  }
})
