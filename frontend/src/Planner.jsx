import React, { useEffect, useState } from 'react'
import { BrainCircuit, CalendarDays, Clock3, MapPin, Sparkles, UsersRound, WalletCards, X } from 'lucide-react'
import { api, explain } from './api'

const terminal = new Set(['SUCCEEDED', 'NEEDS_REFINEMENT', 'FAILED', 'TIMED_OUT', 'CANCELLED'])
const pad = value => String(value).padStart(2, '0')
const localValue = date => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
const dateLabel = value => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
const money = cents => (cents / 100).toFixed(2).replace(/\.00$/, '')

function defaults() {
  const day = new Date(); day.setDate(day.getDate() + 1)
  const from = new Date(day); from.setHours(9, 0, 0, 0)
  const until = new Date(day); until.setHours(23, 0, 0, 0)
  return { city: '上海', from: localValue(from), until: localValue(until), budget: 500, people: 2, preference: '安排两项不冲突的本地体验，优先适合朋友聚会的活动' }
}

export default function Planner({ session, openAuth, sessionFailure }) {
  const [form, setForm] = useState(defaults), [task, setTask] = useState(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')

  useEffect(() => {
    if (!task?.id || terminal.has(task.status)) return
    let active = true, timer
    async function poll() {
      try {
        const next = await api(`/planning/tasks/${task.id}`)
        if (!active) return
        setTask(next)
        if (!terminal.has(next.status)) timer = setTimeout(poll, 800)
      } catch (failure) { if (active) setError(sessionFailure(failure)) }
    }
    timer = setTimeout(poll, 500)
    return () => { active = false; clearTimeout(timer) }
  }, [task?.id, task?.status])

  async function submit(event) {
    event.preventDefault()
    if (!session) { openAuth(); return }
    setBusy(true); setError(''); setTask(null)
    try {
      const payload = {
        requestKey: crypto.randomUUID().replaceAll('-', ''),
        input: {
          constraints: { city: form.city.trim(), from: form.from, until: form.until, budgetCents: Math.round(Number(form.budget) * 100), people: Number(form.people) },
          preference: form.preference.trim(),
        },
      }
      setTask(await api('/planning/tasks', { method: 'POST', csrf: session.csrfToken, body: payload }))
    } catch (failure) { setError(sessionFailure(failure)) }
    finally { setBusy(false) }
  }

  async function cancel() {
    if (!task || terminal.has(task.status)) return
    try { setTask(await api(`/planning/tasks/${task.id}/cancel`, { method: 'POST', csrf: session.csrfToken })) }
    catch (failure) { setError(sessionFailure(failure)) }
  }

  const output = task?.result
  const result = output?.result
  return <section className="planner-page">
    <div className="heading"><div><p className="eyebrow"><Sparkles size={14}/>DISCOVERY + PLANNER</p><h1>智能行程规划</h1><p className="muted">Agent 负责筛选与组合，预算、时间冲突和实时余量由 Java 再校验。</p></div></div>
    <div className="planner-layout">
      <form className="planner-form" onSubmit={submit}>
        <div className="section-title"><BrainCircuit size={19}/><div><h2>规划条件</h2><p className="muted small">规划只生成建议，不会自动预约。</p></div></div>
        <label><span><MapPin size={15}/>城市</span><input value={form.city} maxLength={60} required onChange={e => setForm({ ...form, city: e.target.value })}/></label>
        <div className="field-grid">
          <label><span><CalendarDays size={15}/>开始时间</span><input type="datetime-local" value={form.from} required onChange={e => setForm({ ...form, from: e.target.value })}/></label>
          <label><span><Clock3 size={15}/>结束时间</span><input type="datetime-local" value={form.until} required onChange={e => setForm({ ...form, until: e.target.value })}/></label>
          <label><span><WalletCards size={15}/>总预算（元）</span><input type="number" min="0" max="100000" step="1" value={form.budget} required onChange={e => setForm({ ...form, budget: e.target.value })}/></label>
          <label><span><UsersRound size={15}/>同行人数</span><input type="number" min="1" max="20" value={form.people} required onChange={e => setForm({ ...form, people: e.target.value })}/></label>
        </div>
        <label><span>偏好</span><textarea rows="4" maxLength="2000" value={form.preference} onChange={e => setForm({ ...form, preference: e.target.value })}/></label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy || (task && !terminal.has(task.status))}>{!session ? '登录后规划' : busy ? '正在提交…' : task && !terminal.has(task.status) ? 'Agent 正在规划…' : '生成行程建议'}</button>
      </form>
      <div className="plan-output">
        {!task ? <div className="plan-placeholder"><BrainCircuit size={34}/><h2>从明确约束开始</h2><p className="muted">系统先检索候选活动，再组合行程，最后用确定性规则复核。</p></div> : !terminal.has(task.status) ? <div className="plan-placeholder"><span className="spinner"/><h2>正在筛选活动并编排行程</h2><p className="muted small">任务 {task.id.slice(0, 8)} · {task.status === 'QUEUED' ? '等待执行' : '执行中'}</p><button className="text-button" onClick={cancel}><X size={15}/>取消任务</button></div> : result?.status === 'READY' ? <>
          <div className="plan-summary"><span className="status confirmed">可执行方案</span><span className="muted small">{output.mode} · {output.modelCalls} 次模型调用</span></div>
          <div className="timeline">{(output.selected || []).map((item, index) => <article className="plan-stop" key={item.id}><div className="step">{index + 1}</div><div><p className="muted small">{dateLabel(item.startsAt)} - {dateLabel(item.endsAt)} · {item.area}</p><h2>{item.title}</h2><p>{item.description}</p><div className="plan-meta"><span>¥{money(item.priceCents)} / 人</span><span>余量 {item.available}</span></div></div></article>)}</div>
          <p className="plan-note">提交预约时会重新校验库存和购买资格，规划结果不锁定名额。</p>
        </> : <div className="plan-placeholder"><BrainCircuit size={34}/><h2>{result?.status === 'NO_MATCH' ? '没有满足全部条件的活动' : result?.status === 'NEEDS_REFINEMENT' ? '条件需要调整' : '本次规划未完成'}</h2><p className="muted">{result?.issues?.join('；') || task.errorCode || '请稍后重试。'}</p><button className="outline" onClick={() => setTask(null)}>调整条件</button></div>}
      </div>
    </div>
  </section>
}
