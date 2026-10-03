import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
const origin = process.env.LIFE_URL || 'http://127.0.0.1:4177'
const output = resolve(process.env.LIFE_EVIDENCE || 'test-output/life')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, ...(process.env.LIFE_BROWSER_CHANNEL ? { channel: process.env.LIFE_BROWSER_CHANNEL } : {}) })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const results = [], errors = []
page.on('pageerror', e => errors.push(e.message))
const username = `ui_${randomUUID().replaceAll('-', '').slice(0, 16)}`
const password = `Local-test-${randomUUID()}`
async function test(name, work) {
  await work(); results.push({ name, passed: true }); console.log(`PASS ${name}`)
}
async function until(check, message, timeout = 15000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { if (await check()) return; await page.waitForTimeout(150) }
  throw new Error(message)
}
async function screenshot(name) {
  const toast = page.getByRole('button', { name: '关闭提示', exact: true })
  if (await toast.isVisible() && !await page.locator('dialog').count()) await toast.click()
  await page.screenshot({ path: resolve(output, `${name}.png`), fullPage: !await page.locator('dialog').count() })
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'horizontal overflow')
  assert(await page.locator('img').evaluateAll(images => images.every(img => img.complete && img.naturalWidth > 0)), 'broken image')
  const overlaps = await page.locator('button').evaluateAll(buttons => buttons.filter(b => {
    if (!b.checkVisibility()) return false
    return b.scrollWidth > b.clientWidth + 2 || b.scrollHeight > b.clientHeight + 2
  }).map(b => b.textContent))
  assert.deepEqual(overlaps, [], 'button content overflow')
}
async function openOffer(shop, index = 0) {
  await page.getByRole('button', { name: '发现好店', exact: true }).click()
  await page.getByRole('button', { name: `查看${shop}` }).click()
  await page.getByRole('button', { name: '立即抢券', exact: true }).nth(index).waitFor()
}
async function pendingOrder(shop, title) {
  const item = page.locator('article.order').filter({ has: page.getByRole('heading', { name: shop, exact: true }) }).filter({ hasText: title }).first()
  await item.getByRole('button', { name: '确认订单', exact: true }).waitFor({ timeout: 15000 })
  return item
}
try {
  await test('public catalog, filters and desktop layout', async () => {
    await page.goto(`${origin}/`)
    await page.getByRole('button', { name: '查看西岸餐桌' }).waitFor()
    await screenshot('desktop-catalog')
    await page.getByRole('button', { name: '更多', exact: true }).click()
    await page.getByRole('button', { name: '轻食', exact: true }).click()
    await until(async () => await page.locator('.shop-card').count() === 1, 'category filter failed')
    await page.getByRole('button', { name: '全部', exact: true }).click()
    await page.getByRole('textbox', { name: '搜索商家' }).fill('没有这家店')
    await page.getByRole('heading', { name: '没有找到匹配的商家' }).waitFor()
    await page.getByRole('button', { name: '清空搜索' }).click()
    await until(async () => await page.locator('.shop-card').count() === 12, 'catalog reset failed')
  })
  await test('register, session cookie and reload restore', async () => {
    await page.getByRole('button', { name: '登录 / 注册', exact: true }).click()
    await page.getByRole('button', { name: '还没有账号？创建账号' }).click()
    await page.getByRole('textbox', { name: '用户名', exact: true }).fill(username)
    await page.getByRole('textbox', { name: '昵称', exact: true }).fill('本地验收')
    await page.locator('input[name=password]').fill(password)
    await page.getByRole('button', { name: '注册并登录', exact: true }).click()
    await page.getByRole('button', { name: '退出登录' }).waitFor()
    const cookie = (await context.cookies()).find(x => x.name === 'life_session')
    assert(cookie?.httpOnly && cookie.sameSite === 'Strict' && cookie.path === '/v2')
    assert.equal(await page.evaluate(() => document.cookie.includes('life_session')), false)
    await page.reload(); await page.getByRole('button', { name: '退出登录' }).waitFor()
  })
  await test('real async order and local confirmation', async () => {
    await openOffer('西岸餐桌')
    await page.getByText('营业时间', { exact: true }).waitFor()
    await page.getByRole('heading', { name: '预约与使用须知', exact: true }).waitFor()
    await page.getByText('口碑摘要', { exact: true }).waitFor()
    await screenshot('desktop-offers')
    await page.getByRole('button', { name: '立即抢券', exact: true }).first().click()
    const item = await pendingOrder('西岸餐桌', '单人到店代金券')
    await screenshot('desktop-pending-order')
    await item.getByRole('button', { name: '确认订单', exact: true }).click()
    await until(async () => (await item.locator('.status').innerText()) === '已确认', 'confirmation not reflected')
  })
  await test('cancel and one-purchase restriction', async () => {
    await openOffer('西岸餐桌', 1)
    await page.getByRole('button', { name: '立即抢券', exact: true }).nth(1).click()
    const item = await pendingOrder('西岸餐桌', '双人分享组合券')
    await item.getByRole('button', { name: '取消订单', exact: true }).click()
    await page.getByRole('button', { name: '确认取消', exact: true }).click()
    await until(async () => (await item.locator('.status').innerText()) === '已取消', 'cancel not reflected')
    await openOffer('西岸餐桌', 1)
    await page.getByRole('button', { name: '立即抢券', exact: true }).nth(1).click()
    await page.getByRole('status').filter({ hasText: '取消或过期后也不能再次购买' }).waitFor()
    await until(async () => await page.locator('.recovery-row').count() === 0, 'explicit rejection left uncertain intent')
  })
  await test('lost POST response, reload and same-key retry', async () => {
    let dropped = false, firstKey, firstId
    const sent = []
    const interceptPost = async route => {
      if (route.request().method() !== 'POST') return route.continue()
      sent.push(route.request().postDataJSON().requestId)
      if (!dropped) {
        dropped = true; firstKey = sent[0]
        const response = await route.fetch(); assert.equal(response.status(), 202)
        firstId = (await response.json()).id
        await route.abort('connectionreset')
      } else await route.continue()
    }
    const blockReads = route => route.abort('internetdisconnected')
    await page.route('**/v2/requests', interceptPost)
    await page.route('**/v2/account/requests**', blockReads)
    await openOffer('青柠小馆')
    await page.getByRole('button', { name: '立即抢券', exact: true }).first().click()
    await page.getByRole('button', { name: '原请求重试', exact: true }).waitFor()
    await until(async () => dropped && firstId, 'request not committed before loss')
    await until(async () => await page.getByRole('button', { name: '原请求重试', exact: true }).isEnabled(), 'request did not unlock')
    await page.reload(); await page.getByRole('button', { name: '退出登录' }).waitFor()
    await page.getByRole('button', { name: '我的订单', exact: true }).click()
    await page.getByRole('button', { name: '原请求重试', exact: true }).waitFor()
    await screenshot('desktop-uncertain-recovery')
    await page.getByRole('button', { name: '原请求重试', exact: true }).click()
    await until(async () => sent.length === 2, 'retry not sent')
    assert.deepEqual(sent, [firstKey, firstKey])
    await page.unroute('**/v2/account/requests**', blockReads)
    await page.unroute('**/v2/requests', interceptPost)
    const item = await pendingOrder('青柠小馆', '单人到店代金券')
    const data = await page.evaluate(async key => (await (await fetch(`/v2/account/requests/by-key/${key}`)).json()).item, firstKey)
    assert.equal(data.id, firstId)
    assert.equal(await page.locator('article.order').filter({ hasText: '青柠小馆' }).count(), 1)
    await item.getByRole('button', { name: '确认订单', exact: true }).click()
    await until(async () => (await item.locator('.status').innerText()) === '已确认', 'recovered order not confirmed')
  })
  await test('mobile catalog, dialog, keyboard and orders layout', async () => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole('button', { name: '发现好店', exact: true }).click()
    await screenshot('mobile-catalog')
    await openOffer('街角小食')
    await screenshot('mobile-offers')
    await page.keyboard.press('Escape'); assert.equal(await page.locator('dialog').count(), 0)
    await page.getByRole('button', { name: /我的订单/ }).click()
    await page.locator('article.order').first().waitFor()
    await screenshot('mobile-orders')
  })
  await test('blocked local storage stops purchase before sending', async () => {
    let posts = 0
    const intercept = route => { if (route.request().method() === 'POST') posts++; return route.continue() }
    await page.route('**/v2/requests', intercept)
    await page.evaluate(() => { window.originalSetItem = Storage.prototype.setItem; Storage.prototype.setItem = () => { throw new Error('blocked for test') } })
    await openOffer('街角小食')
    await page.getByRole('button', { name: '立即抢券', exact: true }).first().click()
    await page.getByRole('status').filter({ hasText: '本次尚未发送下单请求' }).waitFor()
    assert.equal(posts, 0)
    await page.evaluate(() => { Storage.prototype.setItem = window.originalSetItem; delete window.originalSetItem })
    await page.unroute('**/v2/requests', intercept)
  })
  await test('logout, another account isolation and original account login', async () => {
    const staleTab = await context.newPage()
    await staleTab.goto(`${origin}/`)
    await staleTab.getByText('本地验收', { exact: true }).waitFor()
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('button', { name: '登录 / 注册', exact: true }).click()
    await page.getByRole('button', { name: '还没有账号？创建账号' }).click()
    await page.getByRole('textbox', { name: '用户名', exact: true }).fill(`ui_${randomUUID().replaceAll('-', '').slice(0, 16)}`)
    await page.getByRole('textbox', { name: '昵称', exact: true }).fill('第二账号')
    await page.locator('input[name=password]').fill(password)
    await page.getByRole('button', { name: '注册并登录', exact: true }).click()
    await page.getByRole('button', { name: '退出登录' }).waitFor()
    await page.getByRole('button', { name: '我的订单', exact: true }).click()
    await page.getByRole('heading', { name: '这里还没有订单' }).waitFor()
    assert.equal(await page.locator('.recovery-row,article.order').count(), 0)
    await staleTab.bringToFront()
    await staleTab.getByRole('button', { name: '我的订单', exact: true }).click()
    await staleTab.getByRole('heading', { name: '欢迎回来', exact: true }).waitFor()
    assert.equal(await staleTab.locator('article.order,.recovery-row').count(), 0)
    await staleTab.close(); await page.bringToFront()
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('button', { name: '登录 / 注册', exact: true }).click()
    await page.getByRole('textbox', { name: '用户名', exact: true }).fill(username)
    await page.locator('input[name=password]').fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByRole('button', { name: '退出登录' }).waitFor()
    await page.getByRole('button', { name: '我的订单', exact: true }).click()
    await until(async () => await page.locator('article.order').count() === 3, 'own order history missing')
  })
  await test('planning uses the same signed-in account and renders stub suggestions', async () => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.getByRole('button', { name: '智能规划', exact: true }).click()
    await page.getByRole('button', { name: '生成行程建议', exact: true }).click()
    await page.getByText('可执行方案', { exact: true }).waitFor({ timeout: 20000 })
    assert(await page.locator('.plan-stop').count() > 0)
    await screenshot('desktop-planning')
  })
  assert.deepEqual(errors, [], 'browser runtime errors')
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ at: new Date().toISOString(), results, errors }, null, 2))
} catch (error) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {})
  await writeFile(resolve(output, 'results.json'), JSON.stringify({ at: new Date().toISOString(), results, errors, failure: error.message }, null, 2))
  throw error
} finally { await browser.close() }
