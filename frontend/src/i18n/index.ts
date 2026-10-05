/**
 * i18n 入口。
 *
 * - vue-i18n v11 组合式（legacy:false），全局单一实例；
 * - 语言：zh-CN（默认）/ en-US，词条按域拆分在 ./messages/ 下，zh 与 en 同文件相邻维护；
 * - 持久化：localStorage['kafkaviz.lang']（node 测试环境无 localStorage，读写均需防护）；
 * - Element Plus 内置文案经 el-config-provider 联动（App.vue 根部）。
 *
 * 组件层约定：`<script setup>` 里 `const { t } = useI18n()`，模板与脚本统一用 `t('domain.key')`，
 * 不使用 `$t`（规避全局属性类型推导差异）；纯 TS 模块用 `i18n.global.t(...)`。
 */
import {computed} from 'vue'
import {createI18n} from 'vue-i18n'
import zhCnLocale from 'element-plus/es/locale/lang/zh-cn'
import enLocale from 'element-plus/es/locale/lang/en'
import * as common from './messages/common'
import * as app from './messages/app'
import * as dashboard from './messages/dashboard'
import * as topicDetail from './messages/topicDetail'
import * as topicDialogs from './messages/topicDialogs'
import * as clusterManage from './messages/clusterManage'
import * as clusterBrowse from './messages/clusterBrowse'
import * as groupsMeta from './messages/groupsMeta'

export type LocaleId = 'zh-CN' | 'en-US'

export const STORAGE_KEY = 'kafkaviz.lang'

export const LANGUAGES: { value: LocaleId; label: string }[] = [
  {value: 'zh-CN', label: '中文'},
  {value: 'en-US', label: 'English'},
]

/** node 环境（vitest）无 localStorage，读失败静默回落默认语言 */
const readStoredLocale = (): LocaleId => {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY)
    return v === 'en-US' ? 'en-US' : 'zh-CN'
  } catch {
    return 'zh-CN'
  }
}

const persistLocale = (locale: LocaleId) => {
  try {
    window.localStorage.setItem(STORAGE_KEY, locale)
  } catch {
    // 无 localStorage（node 测试）时忽略
  }
}

const initialLocale = readStoredLocale()

export const i18n = createI18n({
  legacy: false,
  locale: initialLocale,
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': {
      common: common.zh,
      app: app.zh,
      dashboard: dashboard.zh,
      topicDetail: topicDetail.zh,
      topicDialogs: topicDialogs.zh,
      clusterManage: clusterManage.zh,
      clusterBrowse: clusterBrowse.zh,
      groupsMeta: groupsMeta.zh,
    },
    'en-US': {
      common: common.en,
      app: app.en,
      dashboard: dashboard.en,
      topicDetail: topicDetail.en,
      topicDialogs: topicDialogs.en,
      clusterManage: clusterManage.en,
      clusterBrowse: clusterBrowse.en,
      groupsMeta: groupsMeta.en,
    },
  },
})

/** 当前语言（响应式） */
export const currentLocale = computed<LocaleId>(() => i18n.global.locale.value as LocaleId)

/** Element Plus 组件文案包（App.vue 的 el-config-provider 消费） */
export const epLocale = computed(() =>
  i18n.global.locale.value === 'en-US' ? enLocale : zhCnLocale,
)

/** 切换语言：更新 vue-i18n locale + 持久化 + 同步 <html lang> */
export const setLanguage = (locale: LocaleId) => {
  i18n.global.locale.value = locale
  persistLocale(locale)
  try {
    document.documentElement.lang = locale
  } catch {
    // node 测试无 document，忽略
  }
}

// 启动即同步一次（SSR/node 下安全）
try {
  document.documentElement.lang = initialLocale
} catch {
  // ignore
}

export default i18n
