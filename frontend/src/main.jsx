import React, { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ArrowRight, BrainCircuit, CalendarDays, Camera, Check, ChevronLeft, ChevronRight, Clock3, Dumbbell, Eye, EyeOff, Film, Grid2X2, Info, ListOrdered, LogOut, MapPin, Mic2, Navigation, Palette, RefreshCw, Search, ShieldCheck, Sparkles, Star, Ticket, Timer, Trophy, UserRound, Users, X } from 'lucide-react'
import { api, bindAccount, explain, readPending, remember, forget } from './api'
import Planner from './Planner'
import Payment from './Payment'
import './styles.css'
import './ticket.css'

const sandboxPayments = import.meta.env.VITE_SANDBOX_PAYMENTS === 'true'

const money = cents => (cents / 100).toFixed(2).replace(/\.00$/, '')
const when = value => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
const sessionWhen = value => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false })
const labels = { ACCEPTED: '排队处理中', SUCCEEDED: '已生成订单', REJECTED: '未抢到', EXPIRED: '已过期', PENDING_CONFIRM: '待支付', CONFIRMED: '出票成功', CANCELLED: '已取消' }
const waitlistLabels = { WAITING: '候补中', PROMOTING: '正在补位', PROMOTED: '已补位', CANCELLED: '已退出', EXPIRED: '已结束' }
const phase = item => item.orderState || item.state
const distance = meters => meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`
const categoryIcons = { '音乐演出': Mic2, '戏剧舞台': Ticket, '校园赛事': Trophy, '讲座论坛': BrainCircuit, '社团活动': Users, '展览市集': Camera, '运动体验': Dumbbell, '电影放映': Film, '创意工坊': Palette }
const categoryOrder = ['音乐演出', '戏剧舞台', '校园赛事', '讲座论坛', '社团活动', '展览市集', '运动体验', '电影放映', '创意工坊']
function Modal({ title, children, close, wide = false }) {
  const ref = useRef(null)
  useEffect(() => {
    const previous = document.activeElement
    ref.current.showModal()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = overflow; previous?.focus() }
  }, [])
  return <dialog ref={ref} className={wide ? 'modal wide' : 'modal'} onCancel={e => { e.preventDefault(); close() }} onClick={e => { if (e.target === ref.current) close() }}>
    <div className="modal-head"><h2>{title}</h2><button className="icon" aria-label="关闭" title="关闭" onClick={close}><X size={21}/></button></div>{children}
  </dialog>
}
function AuthForm({ close, done }) {
  const [register, setRegister] = useState(false), [visible, setVisible] = useState(false)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  async function submit(event) {
    event.preventDefault(); if (busy) return
    setBusy(true); setError('')
    const fields = Object.fromEntries(new FormData(event.currentTarget))
    try { done(await api(`/auth/${register ? 'register' : 'login'}`, { method: 'POST', body: fields })) }
    catch (e) { setError(explain(e)) } finally { setBusy(false) }
  }
  return <Modal title={register ? '创建趣票账号' : '欢迎回来'} close={close}>
    <form onSubmit={submit} className="auth-form">
      <label>用户名<input name="username" autoComplete="username" required minLength={4} maxLength={24} pattern="[a-zA-Z][a-zA-Z0-9_]{3,23}" placeholder="4–24 位字母、数字或下划线"/></label>
      {register && <label>昵称<input name="displayName" autoComplete="nickname" required maxLength={24} placeholder="怎么称呼你"/></label>}
      <label>密码<div className="password"><input name="password" type={visible ? 'text' : 'password'} autoComplete={register ? 'new-password' : 'current-password'} required minLength={12} maxLength={72} placeholder="至少 12 个字符"/><button type="button" className="icon" aria-label={visible ? '隐藏密码' : '显示密码'} onClick={() => setVisible(!visible)}>{visible ? <EyeOff size={18}/> : <Eye size={18}/>}</button></div></label>
      {error && <p className="error" role="alert">{error}</p>}
      <button className="primary" disabled={busy}>{busy ? '正在处理…' : register ? '注册并登录' : '登录'}</button>
      <button className="text-button" type="button" disabled={busy} onClick={() => { setRegister(!register); setError('') }}>{register ? '已有账号？去登录' : '还没有账号？创建账号'}</button>
      <p className="muted small">请使用专门的演示账号，不要填写其他网站正在使用的密码。</p>
    </form>
  </Modal>
}
function App() {
  const [session, setSession] = useState(null), [ready, setReady] = useState(false), [auth, setAuth] = useState(false)
  const [view, setView] = useState('discover'), [notice, setNotice] = useState(''), [authError, setAuthError] = useState('')
  const [query, setQuery] = useState(''), [category, setCategory] = useState(''), [area, setArea] = useState(''), [sort, setSort] = useState('recommended'), [page, setPage] = useState(1), [showAllCategories, setShowAllCategories] = useState(false)
  const [catalog, setCatalog] = useState(null), [catalogError, setCatalogError] = useState(''), [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState(null), [detail, setDetail] = useState(null), [detailError, setDetailError] = useState('')
  const [orders, setOrders] = useState(null), [ordersError, setOrdersError] = useState(''), [filter, setFilter] = useState(''), [orderPage, setOrderPage] = useState(1)
  const [paymentOrder, setPaymentOrder] = useState(null), [historyPage, setHistoryPage] = useState(1)
  const [history, setHistory] = useState(null), [historyError, setHistoryError] = useState(''), [showHistory, setShowHistory] = useState(false)
  const [waitlists, setWaitlists] = useState(null), [waitlistsError, setWaitlistsError] = useState('')
  const [pending, setPending] = useState([]), [busy, setBusy] = useState(false), [cancel, setCancel] = useState(null), [tick, setTick] = useState(Date.now()), [refresh, setRefresh] = useState(0)
  const currentUser = useRef(null), lock = useRef(false), skew = useRef(0), generation = useRef(0)
  const user = session?.user.id
  function acceptSession(value) { currentUser.current = value?.user.id || null; bindAccount(value?.user.id); setSession(value); setPaymentOrder(null); setHistoryPage(1); setOrders(null); setHistory(null); setShowHistory(false); setWaitlists(null); setPending(readPending(value?.user.id)); setAuthError('') }
  useEffect(() => { let active = true; api('/auth/me').then(x => { if (active) acceptSession(x) }).catch(e => { if (active && e.status !== 401) setAuthError('登录状态暂时无法读取，请刷新重试。') }).finally(() => { if (active) setReady(true) }); return () => { active = false } }, [])
  useEffect(() => { const timer = setInterval(() => setTick(Date.now()), 1000); return () => clearInterval(timer) }, [])
  useEffect(() => { if (!notice) return; const timer = setTimeout(() => setNotice(''), 8000); return () => clearTimeout(timer) }, [notice])
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setCatalogError('')
    const timer = setTimeout(() => api(`/catalog/shops?${new URLSearchParams({ q: query, category, area, sort, page })}`, { signal: controller.signal }).then(setCatalog).catch(e => { if (!controller.signal.aborted) setCatalogError(explain(e)) }).finally(() => { if (!controller.signal.aborted) setLoading(false) }), 200)
    return () => { clearTimeout(timer); controller.abort() }
  }, [query, category, area, sort, page, refresh])
  useEffect(() => {
    if (!selected) return
    const controller = new AbortController(); setDetail(null); setDetailError('')
    api(`/catalog/shops/${selected}`, { signal: controller.signal }).then(x => { setDetail(x); skew.current = Date.parse(x.serverTime) - Date.now() }).catch(e => { if (!controller.signal.aborted) setDetailError(explain(e)) })
    return () => controller.abort()
  }, [selected, refresh])
  function sessionFailure(error) {
    if (error.status === 401 || error.code === 'SESSION_CHANGED') { acceptSession(null); setAuth(true) }
    return explain(error)
  }
  useEffect(() => {
    if (!user || view !== 'orders') return
    const controller = new AbortController(); let running = false
    const owner = user
    async function load() {
      if (running || document.hidden) return
      running = true
      try {
        const result = await api(`/account/requests?${new URLSearchParams({ page: orderPage, state: filter })}`, { signal: controller.signal })
        if (currentUser.current !== owner || controller.signal.aborted) return
        skew.current = Date.parse(result.serverTime) - Date.now(); setOrders(result); setOrdersError('')
        const stored = readPending(owner)
        for (const intent of stored) {
          let item = result.items.find(x => x.requestKey === intent.key)
          if (!item) { try { item = (await api(`/account/requests/by-key/${encodeURIComponent(intent.key)}`, { signal: controller.signal })).item } catch (e) { if (e.status !== 404) throw e } }
          if (item && item.state !== 'ACCEPTED') forget(owner, intent.key)
        }
        if (currentUser.current === owner && !controller.signal.aborted) setPending(readPending(owner))
      } catch (e) { if (!controller.signal.aborted && currentUser.current === owner) setOrdersError(sessionFailure(e)) }
      finally { running = false }
    }
    load(); const timer = setInterval(load, 3000)
    document.addEventListener('visibilitychange', load)
    return () => { controller.abort(); clearInterval(timer); document.removeEventListener('visibilitychange', load) }
  }, [user, view, orderPage, filter, refresh])
  useEffect(() => {
    if (!user || view !== 'orders' || !showHistory) return
    const controller = new AbortController(); const owner = user
    const load = () => api(`/account/order-history?${new URLSearchParams({ page: historyPage })}`, { signal: controller.signal }).then(result => {
      if (currentUser.current === owner && !controller.signal.aborted) { setHistory(result); setHistoryError('') }
    }).catch(e => { if (!controller.signal.aborted && currentUser.current === owner) setHistoryError(sessionFailure(e)) })
    load(); const timer = setInterval(load, 3000)
    return () => { controller.abort(); clearInterval(timer) }
  }, [user, view, showHistory, historyPage, refresh])
  useEffect(() => {
    if (!user || view !== 'orders') return
    const controller = new AbortController(); const owner = user
    const load = () => api('/account/waitlists', { signal: controller.signal }).then(result => {
      if (currentUser.current === owner && !controller.signal.aborted) { setWaitlists(result); setWaitlistsError('') }
    }).catch(e => { if (!controller.signal.aborted && currentUser.current === owner) setWaitlistsError(sessionFailure(e)) })
    load(); const timer = setInterval(load, 3000)
    return () => { controller.abort(); clearInterval(timer) }
  }, [user, view, refresh])
  useEffect(() => {
    const sync = event => { if (event.key === `life.pending.v1.${user}`) setPending(readPending(user)) }
    window.addEventListener('storage', sync); return () => window.removeEventListener('storage', sync)
  }, [user])
  async function purchase(offer, shop, existing) {
    if (!session) { setAuth(true); return }
    if (lock.current) return
    lock.current = true; setBusy(true)
    const owner = user, attempt = ++generation.current
    let intent, persisted = false
    try {
      intent = existing || readPending(owner).find(x => x.activityId === offer.id) || { key: crypto.randomUUID(), activityId: offer.id, offerTitle: offer.title, shopName: shop.name, createdAt: new Date().toISOString() }
      remember(owner, intent)
      persisted = true
      setPending(readPending(owner)); setSelected(null); setView('orders'); setFilter(''); setOrderPage(1)
      const result = await api('/requests', { method: 'POST', csrf: session.csrfToken, body: { requestId: intent.key, activityId: intent.activityId } })
      if (currentUser.current !== owner) return
      if (result.state !== 'ACCEPTED') forget(owner, intent.key)
      setNotice(result.state === 'ACCEPTED' ? '请求已受理，正在确认库存。' : `请求状态：${labels[result.state] || result.state}`)
    } catch (e) {
      const rejectedBeforeAcceptance = ['INVALID_REQUEST', 'ACTIVITY_NOT_FOUND', 'ACTIVITY_CLOSED', 'SOLD_OUT', 'ALREADY_PURCHASED'].includes(e.code)
      if (persisted && rejectedBeforeAcceptance) forget(owner, intent.key)
      if (e.code === 'SOLD_OUT' && shop?.id) { setView('discover'); setSelected(shop.id) }
      else if (!persisted) setSelected(null)
      if (currentUser.current === owner) setNotice(e.code === 'SOLD_OUT' ? '本场刚刚售罄，可以加入候补队列。' : persisted ? `${sessionFailure(e)}${rejectedBeforeAcceptance ? '' : ' 原请求已保留，可查结果或重试。'}` : '无法保存请求，请检查浏览器本地存储或先处理待确认请求。本次尚未发送下单请求。')
    } finally {
      if (currentUser.current === owner) { setPending(readPending(owner)); setRefresh(x => x + 1) }
      if (attempt === generation.current) { lock.current = false; setBusy(false) }
    }
  }
  async function check(intent) {
    if (lock.current) return
    const owner = user
    lock.current = true; setBusy(true)
    try {
      const { item } = await api(`/account/requests/by-key/${intent.key}`)
      if (currentUser.current !== owner) return
      if (item.state !== 'ACCEPTED') forget(owner, intent.key)
      setNotice(`最新状态：${labels[phase(item)]}`); setPending(readPending(owner)); setRefresh(x => x + 1)
    } catch (e) { if (currentUser.current === owner) setNotice(sessionFailure(e)) }
    finally { lock.current = false; setBusy(false) }
  }
  async function joinWaitlist(offer) {
    if (!session) { setAuth(true); return }
    if (lock.current) return
    lock.current = true; setBusy(true); const owner = user
    try {
      const entry = await api('/waitlists', { method: 'POST', csrf: session.csrfToken, body: { activityId: offer.id } })
      if (currentUser.current !== owner) return
      setSelected(null); setView('orders'); setFilter(''); setOrderPage(1)
      setNotice(`已加入候补，当前第 ${entry.position} 位。名额释放后将尝试自动补位，请以最终结果为准。`)
    } catch (e) { if (currentUser.current === owner) setNotice(sessionFailure(e)) }
    finally { lock.current = false; setBusy(false); if (currentUser.current === owner) setRefresh(x => x + 1) }
  }
  async function cancelWaitlist(entry) {
    if (lock.current) return
    lock.current = true; setBusy(true); const owner = user
    try {
      await api(`/waitlists/${entry.id}/cancel`, { method: 'POST', csrf: session.csrfToken })
      if (currentUser.current === owner) setNotice('已退出候补队列。')
    } catch (e) { if (currentUser.current === owner) setNotice(sessionFailure(e)) }
    finally { lock.current = false; setBusy(false); if (currentUser.current === owner) setRefresh(x => x + 1) }
  }
  async function transition(item, action) {
    if (lock.current) return
    lock.current = true; setBusy(true); const owner = user
    try {
      await api(`/requests/${item.id}/${action}`, { method: 'POST', csrf: session.csrfToken })
      const result = await api(`/account/requests/${item.id}`)
      if (currentUser.current === owner) setNotice(`订单状态：${labels[phase(result.item)]}`)
    } catch (e) { if (currentUser.current === owner) setNotice(`${sessionFailure(e)} 请以刷新后的订单状态为准。`) }
    finally { lock.current = false; setBusy(false); setCancel(null); if (currentUser.current === owner) setRefresh(x => x + 1) }
  }
  async function logout() {
    if (busy) return
    try { await api('/auth/logout', { method: 'POST', csrf: session.csrfToken }); acceptSession(null); setView('discover'); setNotice('已退出登录。') }
    catch (e) { if (e.status === 401) acceptSession(null); setNotice(explain(e)) }
  }
  function goOrders() { if (!session) setAuth(true); setSelected(null); setView('orders') }
  const unresolved = pending.filter(x => !orders?.items.some(item => item.requestKey === x.key))
  const allCategories = catalog?.categories || []
  const visibleCategories = showAllCategories ? allCategories : categoryOrder.filter(x => allCategories.includes(x))
  return <>
    <header className="topbar"><div className="top-inner"><a className="brand" href="/" aria-label="趣票首页"><Ticket size={25}/><span>趣票</span></a><span className="demo-label">校园演示</span>
      <nav aria-label="主导航"><button aria-label="活动广场" className={view === 'discover' ? 'active' : ''} onClick={() => setView('discover')}><Grid2X2 size={17}/>活动</button><button aria-label="活动规划" className={view === 'plan' ? 'active' : ''} onClick={() => { setSelected(null); setView('plan') }}><Sparkles size={17}/>规划</button><button aria-label="我的票夹" className={view === 'orders' ? 'active' : ''} onClick={goOrders}><Ticket size={17}/>票夹{orders?.pendingCount > 0 && <span className="count">{orders.pendingCount}</span>}</button></nav>
      <div className="account">{session ? <><span><UserRound size={16}/>{session.user.displayName}</span><button className="icon" onClick={logout} disabled={busy} title="退出登录" aria-label="退出登录"><LogOut size={18}/></button></> : <button className="outline" disabled={!ready} onClick={() => setAuth(true)}><UserRound size={16}/>{ready ? '登录 / 注册' : '连接中…'}</button>}</div>
    </div></header>
    <main className="page">
      {authError && <p className="error" role="alert">{authError}</p>}
      {view === 'discover' ? <>
        <div className="heading home-heading"><div><p className="eyebrow"><span className="city-pill"><MapPin size={13}/>上海</span>校园文体活动</p><h1>发现校园好现场</h1><p className="muted">演出、赛事、讲座和社团活动，一站抢票。</p></div><div className="search"><Search size={20}/><input aria-label="搜索校园活动" placeholder="搜索活动、场馆或类型" value={query} maxLength={80} onChange={e => { setQuery(e.target.value); setPage(1) }}/>{query && <button className="icon" aria-label="清空搜索" onClick={() => setQuery('')}><X size={16}/></button>}</div></div>
        {catalog?.items?.[0] && !query && !category && !area && <button className="ticket-hero" onClick={() => setSelected(catalog.items[0].id)} aria-label={`查看本周热门活动${catalog.items[0].name}`}><img src={catalog.items[0].imagePath} alt=""/><span className="ticket-hero-shade"/><span className="ticket-hero-copy"><small>本周热门 · 限量开票</small><strong>{catalog.items[0].name}</strong><span>{catalog.items[0].area} · ¥{money(catalog.items[0].fromPrice)} 起</span><b>立即查看 <ArrowRight size={16}/></b></span></button>}
        <section className="scene-section"><div className="section-heading"><div><h2>活动分类</h2><p className="muted small">找到你感兴趣的校园现场</p></div></div><div className="scene-grid" role="group" aria-label="活动分类"><button aria-pressed={category === ''} className={category === '' ? 'selected' : ''} onClick={() => { setCategory(''); setPage(1) }}><span><Grid2X2 size={21}/></span><b>全部</b></button>{visibleCategories.map(x => { const Icon = categoryIcons[x] || Sparkles; return <button aria-pressed={category === x} className={category === x ? 'selected' : ''} onClick={() => { setCategory(x); setPage(1) }} key={x}><span><Icon size={21}/></span><b>{x}</b></button> })}<button className="more-scenes" aria-expanded={showAllCategories} onClick={() => setShowAllCategories(x => !x)}><span><Grid2X2 size={21}/></span><b>{showAllCategories ? '收起' : '更多'}</b></button></div></section>
        {catalog?.items?.length > 0 && !query && !category && !area && <section className="deal-band"><div className="section-heading"><div><h2>热门开票</h2><p className="muted small">校园限量名额，售完可候补</p></div><span className="muted small">每日更新</span></div><div className="deal-list">{catalog.items.slice(0, 3).map(shop => <button className="deal-item" key={shop.id} onClick={() => setSelected(shop.id)}><img src={shop.imagePath} alt=""/><span><b>{shop.name}</b><small>{shop.category} · 余票实时更新</small></span><strong>¥{money(shop.fromPrice)}<small>起</small></strong><ArrowRight size={16}/></button>)}</div></section>}
        <div className="catalog-toolbar"><div><h2>全部活动</h2><p className="muted small">综合热度、开票价格与剩余名额推荐</p></div><span className="muted small">{loading ? '更新中…' : `${catalog?.total || 0} 场活动`}</span></div>
        <div className="catalog-controls"><label>校区<select aria-label="选择校区" value={area} onChange={e => { setArea(e.target.value); setPage(1) }}><option value="">全部校区</option>{(catalog?.areas || []).map(x => <option key={x}>{x}</option>)}</select></label><div className="sort-tabs" role="group" aria-label="活动排序">{[['recommended','综合推荐'],['price','价格优先'],['availability','余票优先']].map(([key,label]) => <button key={key} aria-pressed={sort === key} className={sort === key ? 'selected' : ''} onClick={() => { setSort(key); setPage(1) }}>{label}</button>)}</div></div>
        {catalogError ? <div className="empty"><p className="error">{catalogError}</p><button className="outline" onClick={() => setRefresh(x => x + 1)}><RefreshCw size={16}/>重新加载</button></div> : loading && !catalog ? <div className="shop-grid" aria-label="加载中">{[1, 2, 3].map(x => <div className="skeleton" key={x}/>)}</div> : catalog?.items.length ? <div className={`shop-grid ${loading ? 'updating' : ''}`}>{catalog.items.map(shop => <button key={shop.id} className="shop-card" onClick={() => setSelected(shop.id)} aria-label={`查看${shop.name}`}><div className="shop-image"><img src={shop.imagePath} alt={shop.name + '活动示意'}/><span>{shop.category}</span>{shop.totalAvailable > 0 && <em>余 {shop.totalAvailable} 张</em>}</div><div className="shop-content"><p className="muted small"><MapPin size={13}/>{shop.area}</p><h2>{shop.name}</h2><div className="shop-rating"><strong><Star size={13} fill="currentColor"/>{shop.rating.toFixed(1)}</strong><span>{shop.reviewCount} 人关注</span><span>近期参与 {shop.monthlySales}</span><span><Navigation size={12}/>{distance(shop.distanceMeters)}</span></div><p className="description">{shop.description}</p><div className="shop-facts"><span><Ticket size={14}/>{shop.offerCount} 个票档</span><span>余 {shop.totalAvailable} 张</span>{shop.nextSessionAt && <span><CalendarDays size={14}/>{sessionWhen(shop.nextSessionAt)}</span>}</div><div className="shop-bottom"><span>{shop.fromPrice != null ? <><b className="price"><small>¥</small>{money(shop.fromPrice)}</b><span className="muted small"> 起</span></> : <span className="muted">暂无在售票档</span>}</span><span className="shop-action">查看票档 <ArrowRight size={17}/></span></div></div></button>)}</div> : <div className="empty"><Search size={30}/><h2>没有找到匹配的活动</h2><p className="muted">换个活动名称、分类或校区试试。</p></div>}
        {catalog?.total > 12 && <Pager page={page} total={catalog.total} size={12} change={setPage}/>}
      </> : view === 'plan' ? <Planner session={session} openAuth={() => setAuth(true)} sessionFailure={sessionFailure}/> : <>
        <div className="heading"><div><p className="eyebrow">MY TICKETS</p><h1>我的票夹</h1><p className="muted">抢票结果、付款状态和候补进度，都在这里。</p></div><button className="outline" onClick={() => setRefresh(x => x + 1)} disabled={!session || busy}><RefreshCw size={16}/>刷新</button></div>
        {!session ? <div className="empty"><UserRound size={32}/><h2>登录后查看你的订单</h2><button className="primary" onClick={() => setAuth(true)}>登录 / 注册</button></div> : <>
          {unresolved.length > 0 && <section className="recovery"><h2><Clock3 size={19}/>结果待确认</h2><p className="muted small">网络中断不代表下单失败。查结果或重试都会使用原请求。</p>{unresolved.map(intent => <div className="recovery-row" key={intent.key}><div><strong>{intent.shopName} · {intent.offerTitle}</strong><p className="muted small">{when(intent.createdAt)} · {intent.key.slice(0, 8)}</p></div><div className="actions"><button className="outline" disabled={busy} onClick={() => check(intent)}>查结果</button><button className="outline" disabled={busy} onClick={() => purchase({ id: intent.activityId }, {}, intent)}>原请求重试</button></div></div>)}</section>}
          {waitlistsError && <p className="error" role="alert">{waitlistsError}</p>}
          {waitlists?.items?.some(x => ['WAITING', 'PROMOTING'].includes(x.state)) && <section className="waitlist-panel"><div className="waitlist-heading"><div><h2><ListOrdered size={19}/>我的候补</h2><p className="muted small">名额释放后将尝试自动补位，成功后会生成待支付订单。</p></div></div>{waitlists.items.filter(x => ['WAITING', 'PROMOTING'].includes(x.state)).map(entry => <article className="waitlist-row" key={entry.id}>{entry.imagePath && <img src={entry.imagePath} alt=""/>}<div><strong>{entry.shopName} · {entry.offerTitle}</strong><p className="muted small">{entry.state === 'WAITING' ? `当前第 ${entry.position} 位` : '正在为你锁定释放的名额'} · {when(entry.createdAt)}</p></div><span className={`status ${entry.state.toLowerCase()}`}>{waitlistLabels[entry.state]}</span>{entry.state === 'WAITING' && <button className="outline compact" disabled={busy} onClick={() => cancelWaitlist(entry)}>退出候补</button>}</article>)}</section>}
          <section className="history-access"><div><h2>历史订单</h2><p className="muted small">查看已同步的订单记录。最新状态和操作请以当前订单列表为准。</p></div><button className="outline" onClick={() => { setShowHistory(x => !x); setHistoryPage(1); setHistory(null); setHistoryError('') }}>{showHistory ? '收起历史查询' : '查看历史查询'}</button></section>
          {showHistory && <section className="history-panel" aria-label="历史订单结果">{historyError ? <p className="error" role="alert">{historyError}</p> : !history ? <p className="empty">正在加载历史订单…</p> : history.items.length ? <><p className="history-consistency"><ShieldCheck size={15}/>订单记录 · 共 {history.total} 条</p><div className="history-list">{history.items.map(item => <article className="history-row" key={item.id}>{item.imagePath && <img src={item.imagePath} alt=""/>}<div><strong>{item.shopName || '活动订单'}</strong><p>{item.offerTitle || `活动 ${item.activityId}`}</p><p className="muted small">{when(item.createdAt)} · 订单 {item.id.slice(0, 8)}</p></div><b>¥{money(item.priceCents)}</b><span className={`status ${item.state.toLowerCase()}`}>{labels[item.state] || item.state}</span></article>)}</div>{history.total > 20 && <Pager page={historyPage} total={history.total} size={20} change={setHistoryPage}/>}</> : <div className="empty"><Ticket size={28}/><h2>暂无历史订单</h2><p className="muted">新订单会由后台同步，稍后自动刷新。</p></div>}</section>}
          <div className="order-tabs" role="group" aria-label="订单筛选">{[['', '全部订单'], ['pending', '处理中'], ['confirmed', '已出票'], ['closed', '已关闭']].map(([key, label]) => <button key={key} className={filter === key ? 'selected' : ''} aria-pressed={filter === key} onClick={() => { setFilter(key); setOrderPage(1); setOrders(null) }}>{label}</button>)}</div>
          {ordersError && <p className="error" role="alert">{ordersError}</p>}
          {!orders && !ordersError ? <p className="empty">正在读取订单…</p> : orders?.items.length ? <div className="order-list">{orders.items.map(item => {
            const state = phase(item), left = Math.max(0, Math.ceil((Date.parse(item.confirmUntil) - tick - skew.current) / 1000))
            const progress = state === 'ACCEPTED' ? 1 : state === 'PENDING_CONFIRM' ? 2 : state === 'CONFIRMED' ? 3 : 1
            return <article className="order" key={item.id}><div className="order-top"><span>{when(item.createdAt)} <span className="request-number">· 请求 {item.id.slice(0, 8)}</span></span><span className={`status ${state.toLowerCase()}`}>{labels[state]}</span></div><div className="order-body">{item.imagePath && <img src={item.imagePath} alt={item.shopName || '校园活动'}/>}<div className="order-info"><h2>{item.shopName || '活动订单'}</h2><p>{item.offerTitle || `活动 ${item.activityId}`}</p><p className="muted small">{state === 'PENDING_CONFIRM' ? left > 0 ? `剩余 ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')} 支付` : '支付时间已到，正在核对状态…' : state === 'ACCEPTED' ? '正在确认余票，尚未生成订单' : state === 'REJECTED' ? explain({ code: item.reason }) : state === 'CONFIRMED' ? '出票完成 · 请按活动要求入场' : '订单已关闭'}</p></div><b className="order-price">¥{money(item.priceCents || 0)}</b></div><div className={`order-progress ${['REJECTED','EXPIRED','CANCELLED'].includes(state) ? 'closed' : ''}`}>{[['请求受理',1],['余票确认',2],['出票完成',3]].map(([name,step]) => <span key={name} className={progress >= step ? 'done' : ''}><i>{progress > step ? <Check size={12}/> : step}</i><b>{name}</b></span>)}</div>{state === 'PENDING_CONFIRM' && <div className="order-footer"><span className="muted small">每人每票档限购一次，关闭后不可重购</span><div className="actions"><button className="outline" disabled={busy} onClick={() => setCancel(item)}>取消订单</button><button className="primary" disabled={busy || left === 0} onClick={() => sandboxPayments ? setPaymentOrder(item) : transition(item, 'confirm')}><Check size={16}/>{sandboxPayments ? '模拟支付' : '确认出票'}</button></div></div>}{sandboxPayments && item.orderState && state !== 'PENDING_CONFIRM' && <div className="order-footer"><span className="muted small">查看模拟付款及退款进度</span><button className="outline" onClick={() => setPaymentOrder(item)}>付款记录</button></div>}</article>
          })}</div> : !ordersError && <div className="empty"><Ticket size={32}/><h2>票夹还是空的</h2><button className="text-button" onClick={() => setView('discover')}>去活动广场 <ArrowRight size={16}/></button></div>}
          {orders?.total > 20 && <Pager page={orderPage} total={orders.total} size={20} change={setOrderPage}/>}
        </>}
      </>}
    </main>
    <footer className="footer"><span>趣票</span><span>校园活动抢票演示 · 活动为示例数据 · 不涉及真实支付</span></footer>
    {selected && <Modal wide title={detail?.shop.name || '活动详情'} close={() => setSelected(null)}>{detailError ? <p className="error">{detailError}</p> : !detail ? <p className="empty">加载中…</p> : <div className="shop-detail">
      <div className="detail-hero"><img className="detail-photo" src={detail.shop.imagePath} alt={detail.shop.name + '活动示意'}/><div className="detail-score"><strong><Star size={14} fill="currentColor"/>{detail.shop.rating.toFixed(1)}</strong><span>{detail.shop.reviewCount} 人关注</span><span>近期参与 {detail.shop.monthlySales}</span></div></div>
      <div className="detail-meta"><div className="detail-title-row"><span className="tag">{detail.shop.category}</span><span className="muted small"><Navigation size={13}/>{distance(detail.shop.distanceMeters)}</span></div><p>{detail.shop.description}</p><p className="muted small"><MapPin size={14}/>{detail.shop.address}</p><div className="detail-facts"><span><Clock3 size={15}/><b>开放时间</b>{detail.profile.businessHours}</span><span><ShieldCheck size={15}/><b>抢票保障</b>余票与购买资格实时校验</span></div>{detail.profile.highlights.length > 0 && <div className="highlight-list">{detail.profile.highlights.map(x => <span key={x}>{x}</span>)}</div>}</div>
      <section className="review-summary"><div><Star size={18} fill="currentColor"/><strong>{detail.shop.rating.toFixed(1)}</strong><span>活动热度</span></div><p>{detail.profile.reviewSummary}</p></section>
      <section className="service-notice"><h3><Info size={17}/>抢票与入场须知</h3><p>{detail.profile.serviceNotice}</p></section>
      <h3 className="offer-section-title">可选票档 <span className="muted small">{detail.offers.length} 个在售</span></h3>{detail.offers.map(offer => {
      const now = tick + skew.current, open = now >= Date.parse(offer.startsAt) && now < Date.parse(offer.endsAt), available = offer.available > 0 && open
      return <section className="offer" key={offer.id}><div className="offer-header"><div><h3>{offer.title}</h3><p className="muted small">票面价 ¥{money(offer.faceValueCents)} · 剩余 {offer.available} / {offer.capacity} 张</p></div><b className="price"><small>¥</small>{money(offer.priceCents)}</b></div><p className="terms">{offer.terms}</p><div className="offer-bottom"><span className="muted small"><Timer size={13}/>{when(offer.endsAt)} 截止</span>{offer.available === 0 && open ? <button className="outline waitlist-button" disabled={busy || !ready} onClick={() => joinWaitlist(offer)}><Users size={16}/>{session ? '加入候补' : '登录后候补'}</button> : <button className="primary" disabled={!available || busy || !ready} onClick={() => purchase(offer, detail.shop)}>{!available ? '不在抢票时间' : session ? '立即抢票' : '登录后抢票'}<ArrowRight size={16}/></button>}</div></section>
    })}{!detail.offers.length && <p className="empty">当前暂无在售票档。</p>}</div>}</Modal>}
    {auth && <AuthForm close={() => setAuth(false)} done={value => { acceptSession(value); setAuth(false); setNotice('登录成功。'); setRefresh(x => x + 1) }}/>}
    {paymentOrder && session && <Modal title="订单支付" close={() => setPaymentOrder(null)}><Payment key={`${user}:${paymentOrder.id}`} item={paymentOrder} session={session} changed={() => setRefresh(x => x + 1)} sessionFailure={sessionFailure}/></Modal>}
    {cancel && <Modal title="取消这笔订单？" close={() => { if (!busy) setCancel(null) }}><p>取消后名额将回补，但你不能再次购买同一活动。</p><p className="muted small">{cancel.shopName} · {cancel.offerTitle}</p><div className="modal-actions"><button className="outline" disabled={busy} onClick={() => setCancel(null)}>保留订单</button><button className="danger" disabled={busy} onClick={() => transition(cancel, 'cancel')}>{busy ? '处理中…' : '确认取消'}</button></div></Modal>}
    {notice && <div className="toast" role="status"><span>{notice}</span><button className="icon" aria-label="关闭提示" onClick={() => setNotice('')}><X size={18}/></button></div>}
  </>
}
function Pager({ page, total, size, change }) { return <div className="pager"><button className="icon" title="上一页" aria-label="上一页" disabled={page === 1} onClick={() => change(page - 1)}><ChevronLeft size={19}/></button><span>{page} / {Math.ceil(total / size)}</span><button className="icon" title="下一页" aria-label="下一页" disabled={page * size >= total} onClick={() => change(page + 1)}><ChevronRight size={19}/></button></div> }
createRoot(document.getElementById('root')).render(<App/> )
