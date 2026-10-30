/** 金额格式化：分转元 / 保留两位，等宽数字由 .money 类保证 */
export function fenToYuan(fen: number | string | null | undefined): string {
  const n = Number(fen ?? 0)
  return (n / 100).toFixed(2)
}

/** 元金额：保留两位并右对齐展示用 */
export function yuan(n: number | string | null | undefined): string {
  return Number(n ?? 0).toFixed(2)
}

/** 行程时间展示：2026-10-30T18:00:00 → 10月30日 18:00 */
export function formatTripTime(iso: string | null | undefined): string {
  if (!iso) return '--'
  const d = new Date(iso.includes('T') ? iso : iso.replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return iso.slice(0, 16)
  const m = d.getMonth() + 1
  const day = d.getDate()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${m}月${day}日 ${hh}:${mm}`
}

/** 倒计时文案：毫秒 → "还剩 2天3小时" */
export function countdownText(ms: number): string {
  if (ms <= 0) return '已到期'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return `还剩 ${d}天${h}小时`
  if (h > 0) return `还剩 ${h}小时${m}分`
  return `还剩 ${m}分${s % 60}秒`
}
