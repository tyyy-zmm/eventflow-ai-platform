import assert from 'node:assert/strict';
import path from 'node:path';
import {root,start,stop,request,fixture,terminal,invariant,save,sleep,sql} from './harness.mjs';

if(!process.env.UPGRADE_TEST_DB_URL || process.env.UPGRADE_DB_SCHEMA!=='life_choice_verification') throw new Error('Use verification.sh payment-demo with the isolated verification database');
const dir=path.join(root,'evidence',`payment-demo-${Date.now()}`),checks=[];
let child;
async function ok(route,options) {
  const r=await request('/v2'+route,options);assert.ok(r.status>=200&&r.status<300,JSON.stringify(r));return r.data;
}
async function buy(activity,user) {
  const r=await ok('/requests',{user,method:'POST',body:{requestId:`demo_${activity}_${user}`,activityId:activity}});
  assert.equal((await terminal(user,r.id)).state,'SUCCEEDED');
  const p=await ok('/account/payments',{user,method:'POST',body:{requestId:r.id}});
  return {request:r.id,payment:p.id,user};
}
async function receipt(p,channel) {return ok(`/account/payments/${p.payment}/sandbox-receipt`,{user:p.user,method:'POST',body:{channelId:channel,amountCents:990}});}
async function refunded(p) {
  for(let i=0;i<80;i++) {
    const state=await ok(`/account/payments/${p.payment}`,{user:p.user});
    if(state.state==='REFUNDED') return;
    await sleep(200);
  }
  throw new Error('Refund did not complete');
}
try {
  child=await start(path.join(dir,'server.log'),['--upgrade.sandbox-payments=true']);
  const activity=Date.now();await fixture(activity,10);
  const paid=await buy(activity,activity+1);
  assert.equal((await receipt(paid,`paid_${activity}`)).outcome,'APPLIED');
  assert.equal((await receipt(paid,`paid_${activity}`)).outcome,'APPLIED');
  checks.push({scenario:'payment and duplicate callback',passed:true});
  const late=await buy(activity,activity+2);
  await ok(`/requests/${late.request}/cancel`,{user:late.user,method:'POST'});
  await receipt(late,`late_${activity}`);await refunded(late);
  assert.equal((await receipt(late,`late_${activity}`)).outcome,'REFUNDED');
  checks.push({scenario:'close before payment and refund replay',passed:true});
  const expired=await buy(activity,activity+3);
  assert.match(expired.request,/^[a-f0-9-]{36}$/);
  sql(`UPDATE ux_order SET confirm_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE request_id='${expired.request}'; UPDATE ux_close_task SET due_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)),next_enqueue_at=CURRENT_TIMESTAMP(3) WHERE request_id='${expired.request}';`);
  let state;
  for(let i=0;i<80;i++) {
    state=sql(`SELECT state FROM ux_order WHERE request_id='${expired.request}'`);
    if(state==='EXPIRED') break;
    await sleep(200);
  }
  assert.equal(state,'EXPIRED');await receipt(expired,`expired_${activity}`);await refunded(expired);
  checks.push({scenario:'background delayed close then late payment refund',passed:true});
  let remaining;
  for(let i=0;i<80;i++) {
    remaining=(await ok(`/admin/fixtures/${activity}/reservation`,{admin:true})).remaining;
    if(remaining===9) break;
    await sleep(200);
  }
  assert.equal(remaining,9);checks.push({scenario:'Redis stock converges after two closed orders and one paid order',passed:true});
  const status=await invariant();
  save(path.join(dir,'results.json'),{at:new Date().toISOString(),mode:'simulated provider; no real money',checks,invariants:status.invariants});
  console.log(JSON.stringify({checks,evidence:dir}));
} finally {await stop(child);}
