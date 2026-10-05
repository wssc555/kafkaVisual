import {ElMessageBox} from 'element-plus'
import {i18n} from '../i18n'

/**
 * 写操作二次确认封装。
 * 所有 POST / PATCH / DELETE 在发起请求前必须调用 confirm()，
 * 危险操作（删除 Topic、扩分区、重置 offset、删除消费组、删除 ZK 节点）
 * 使用 type='error' 并明示不可逆风险。
 */
export const useCrudConfirm = () => {
  /**
   * @param message 确认正文（支持 \n 换行，用于罗列风险点；由调用方传入已翻译文案）
   * @param title   弹窗标题（默认走语言包 common.crud.confirmTitle）
   * @param type    'warning' 一般写操作 | 'error' 不可逆 / 高危操作
   * @returns true = 用户确认；false = 用户取消
   */
  const confirm = async (
    message: string,
    title?: string,
    type: 'warning' | 'error' = 'warning',
  ): Promise<boolean> => {
    const t = i18n.global.t
    try {
      await ElMessageBox.confirm(message, title ?? t('common.crud.confirmTitle'), {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type,
        // 正文含换行时保留换行为空白符，避免风险提示挤成一行
        dangerouslyUseHTMLString: false,
        customStyle: {whiteSpace: 'pre-line'},
      })
      return true
    } catch {
      // 用户点击取消 / 关闭弹窗
      return false
    }
  }

  return {confirm}
}
