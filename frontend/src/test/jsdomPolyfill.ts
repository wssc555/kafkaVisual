/**
 * 组件测试（`// @vitest-environment jsdom`）所需的浏览器 API 兜底。
 *
 * 必须作为组件测试文件的**第一个 import**（ES import 按声明顺序求值，
 * 这样才能赶在 element-plus / @vue/test-utils 初始化之前生效）。
 *
 * jsdom 不实现 ResizeObserver；Element Plus 的表格通过 @vueuse 的
 * useResizeObserver 消费它，缺失时库内部会静默跳过，但显式补一个空实现
 * 可以避免不同版本对兜底行为的依赖差异。
 */
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
}

// jsdom 不实现 matchMedia；useResponsive（断点档位）在 setup 首帧即调用，
// 缺省时 TopicDetail 等页面级组件挂载直接抛 TypeError。
// 恒返回 matches=false（窄档），断言只关心组件逻辑，不依赖视口尺寸。
if (typeof globalThis.matchMedia === 'undefined') {
  globalThis.matchMedia = ((query: string) => {
    return {
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    } as unknown as MediaQueryList
  }) as typeof globalThis.matchMedia
}
