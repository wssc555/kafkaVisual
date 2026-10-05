import {ElMessageBox} from 'element-plus'
import {ApiError} from '../api'
import {i18n} from '../i18n'

/**
 * 后端 40001 offset 越界文案：
 * `Offset N is out of range for tp, earliest available offset is M`
 * （auto.offset.reset=none 下 seek 到已被 retention 清理 / compact 跳过的 offset 时抛出）。
 * 把它解析出来，才能给用户「从 M 重查」的可操作引导，而不是只弹一句原始 msg。
 */
const EARLIEST_OFFSET_RE = /earliest available offset is (\d+)/

/**
 * 从后端错误文案中提取「最早可用 offset」。
 * @returns 解析成功返回该 offset；文案不匹配（含负数、缺字段、空串）返回 null
 */
export const parseEarliestOffset = (msg: string | null | undefined): number | null => {
  if (!msg) return null
  const matched = EARLIEST_OFFSET_RE.exec(msg)
  if (!matched) return null
  const parsed = Number(matched[1])
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

/**
 * offset 越界（40001）的恢复引导。
 *
 * 只有「40001 且文案里带得出 earliest offset」才接管：其余错误一律返回 null，
 * 由调用方按原逻辑弹出 msg —— 避免把 50302 超时、40401 Topic 不存在等
 * 完全不同的失败也吞进这条恢复路径。
 */
export const useOffsetRecovery = () => {
  /**
   * @returns 用户确认重查时返回最早可用 offset；非 40001 / 文案不可解析 / 用户取消 均返回 null
   */
  const offerRecovery = async (e: unknown): Promise<number | null> => {
    // 用 instanceof 而非 e?.code：interceptor 之外的异常（网络层、组件内抛）也可能带 code 字段
    if (!(e instanceof ApiError) || e.code !== 40001) return null

    const earliest = parseEarliestOffset(e.message)
    if (earliest === null) return null

    try {
      const t = i18n.global.t
      await ElMessageBox.confirm(
        t('common.offsetRecovery.outOfRangeMsg', {earliest}),
        t('common.offsetRecovery.outOfRangeTitle'),
        {
          confirmButtonText: t('common.offsetRecovery.recoverBtn', {earliest}),
          cancelButtonText: t('common.cancel'),
          type: 'warning',
          customStyle: {whiteSpace: 'pre-line'},
        },
      )
      return earliest
    } catch {
      // 用户取消 / 关闭弹窗
      return null
    }
  }

  return {offerRecovery}
}
