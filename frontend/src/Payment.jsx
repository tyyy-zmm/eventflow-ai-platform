import React, { useEffect, useRef, useState } from 'react'
import { Check, RefreshCw, ShieldCheck } from 'lucide-react'
import { api, explain } from './api'

const states = { OPEN: '等待支付', PAID: '支付成功', REFUND_PENDING: '退款处理中', REFUNDED: '已退款' }
const currency = value => (Number(value) / 100).toFixed(2)

export default function Payment({ item, session, changed, sessionFailure }) {
  const alive = useRef(true), paying = useRef(false), version = useRef(0)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  const [payment, setPayment] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(true)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    api('/account/payments', { method: 'POST', csrf: session.csrfToken, body: { requestId: item.id }, signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) { setPayment(result); setError('') } })
      .catch(e => { if (!controller.signal.aborted) setError(sessionFailure(e)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [item.id, session.csrfToken, reload])
  useEffect(() => {
    if (!payment?.id) return
    const controller = new AbortController()
    let running = false
    async function refresh() {
      if (running || paying.current || document.hidden) return
      running = true
      const revision = version.current
      try {
        const result = await api(`/account/payments/${payment.id}`, { signal: controller.signal })
        if (!controller.signal.aborted && revision === version.current) { setPayment(result); if (result.state !== 'OPEN') setError('') }
      } catch (e) { if (!controller.signal.aborted) setError(explain(e)) }
      finally { running = false }
    }
    refresh()
    const timer = setInterval(refresh, 2000)
    return () => { controller.abort(); clearInterval(timer) }
  }, [payment?.id])
  async function pay() {
    if (loading || paying.current || busy || !payment || payment.state !== 'OPEN') return
    paying.current = true; version.current++; setBusy(true); setError('')
    try {
      // A stable simulated provider ID makes retry/reload safe after an ambiguous response.
      await api(`/account/payments/${payment.id}/sandbox-receipt`, {
        method: 'POST', csrf: session.csrfToken,
        body: { channelId: `ui_${payment.id}`, amountCents: payment.amount_cents },
      })
      const result = await api(`/account/payments/${payment.id}`)
      if (alive.current) { setPayment(result); changed() }
    } catch (e) { if (alive.current) setError(`${sessionFailure(e)} 可刷新状态，或使用同一笔付款重试。`) }
    finally { paying.current = false; if (alive.current) setBusy(false) }
  }
  return <section className="payment-panel" aria-label="模拟支付">
    <p className="payment-shop">{item.shopName}</p><h3>{item.offerTitle}</h3>
    <div className="payment-amount"><span>应付金额</span><strong>¥{currency(payment?.amount_cents ?? item.priceCents)}</strong></div>
    <p className={`payment-state ${payment?.state === 'PAID' ? 'paid' : ''}`} role="status">{payment?.state === 'PAID' && <Check size={20}/>} {payment ? states[payment.state] || '正在核对状态' : loading ? '正在准备订单…' : '暂无可用付款记录'}</p>
    {payment?.state === 'REFUND_PENDING' && <p className="muted">付款到账时订单已无法完成，本笔付款正在退回，请稍后查看。</p>}
    {payment?.state === 'REFUNDED' && <p className="muted">本笔模拟付款已退回，订单状态以订单列表为准。</p>}
    <p className="payment-note"><ShieldCheck size={17}/>此为模拟支付，不会扣除真实资金。超过有效期或取消的订单无法通过付款重新打开。</p>
    {error && <p className="error" role="alert">{error}</p>}
    <div className="modal-actions">
      <button className="outline" disabled={busy || loading} onClick={() => setReload(x => x + 1)}><RefreshCw size={16}/>刷新状态</button>
      {payment?.state === 'OPEN' && <button className="primary" disabled={busy || loading} onClick={pay}>{busy ? '正在核对付款…' : '确认模拟支付'}</button>}
    </div>
  </section>
}
