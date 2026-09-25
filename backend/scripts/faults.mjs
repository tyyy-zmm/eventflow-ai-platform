import {mkdirSync,writeFileSync} from 'node:fs';
import {once} from 'node:events';
import assert from 'node:assert/strict';
import {root,start,stop,request,fixture,terminal,invariant,sql,compose,sleep,save,kafkaDrained} from './harness.mjs';
const dir=`${root}/evidence/faults-${Date.now()}`;mkdirSync(dir,{recursive:true});
const markers=`${root}/target/faults`;mkdirSync(markers,{recursive:true});
let child;let id=Date.now();const results=[];
const boot=()=>start(`${dir}/server.log`,['--upgrade.faults=true',`--upgrade.fault-directory=${markers}`]);
function arm(point){writeFileSync(`${markers}/${point}`,'one-shot');}
async function died(){if(child.exitCode===null) await Promise.race([once(child,'exit'),sleep(15000).then(()=>{throw new Error('Expected crash did not occur');})]);assert.equal(child.exitCode,91);}
async function check(name,work){
  const at=Date.now();await work();
  for(let i=0;i<50 && Number(sql('SELECT COUNT(*) FROM ux_reservation_action WHERE applied_at IS NULL'))>0;i++)await sleep(100);
  assert.equal(sql('SELECT COUNT(*) FROM ux_reservation_action WHERE applied_at IS NULL'),'0');
  results.push({name,passed:true,durationMs:Date.now()-at,invariants:await invariant()});save(`${dir}/results.json`,results);console.log(`PASS ${name}`);
}
try {
  child=await boot();
  await check('redis-reserve-before-database-crash-reconciles',async()=>{
    const a=++id;await fixture(a,1);const key=`orphan_${a}`;arm('reserve-before-db');
    await request('/v2/requests',{user:a,method:'POST',body:{requestId:key,activityId:a}}).catch(()=>{});await died();
    assert.equal(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${a}`),'0');child=await boot();await sleep(7000);
    const recovered=await request('/v2/requests',{user:a+1,method:'POST',body:{requestId:`recovered_${a}`,activityId:a}});
    assert.equal(recovered.status,202);assert.equal((await terminal(a+1,recovered.data.id)).state,'SUCCEEDED');
    assert.equal(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${a}`),'1');
  });
  await check('accept-commit-before-http-response',async()=>{
    const a=++id;await fixture(a);const key=`accept_${a}`;arm('accept-before-response');
    await request('/v2/requests',{user:a,method:'POST',body:{requestId:key,activityId:a}}).catch(()=>{});await died();child=await boot();
    const replay=await request('/v2/requests',{user:a,method:'POST',body:{requestId:key,activityId:a}});
    assert.equal((await terminal(a,replay.data.id)).state,'SUCCEEDED');
    assert.equal(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${a}`),'1');
  });
  for(const point of ['send-before-mark','commit-before-ack']) await check(point,async()=>{
    const a=++id;await fixture(a);arm(point);
    const r=await request('/v2/requests',{user:a,method:'POST',body:{requestId:`crash_${a}`,activityId:a}}).catch(()=>null);
    await died();child=await boot();
    const requestId=r?.data.id ?? sql(`SELECT id FROM ux_request WHERE activity_id=${a}`);
    assert.equal((await terminal(a,requestId)).state,'SUCCEEDED');
    await sleep(16000);
    await kafkaDrained();
    assert.equal(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${a}`),'1');
    assert.equal(sql(`SELECT COUNT(*) FROM ux_outbox WHERE activity_id=${a} AND sent_at IS NULL`),'0');
  });
  await check('cancel-mid-transaction-rollback',async()=>{
    const a=++id;await fixture(a);const r=await request('/v2/requests',{user:a,method:'POST',body:{requestId:`cancel_${a}`,activityId:a}});
    await terminal(a,r.data.id);arm('cancel-before-commit');
    await request(`/v2/requests/${r.data.id}/cancel`,{user:a,method:'POST'}).catch(()=>{});await died();child=await boot();
    assert.equal((await request(`/v2/requests/${r.data.id}/order`,{user:a})).data.state,'PENDING_CONFIRM');
    for(let i=0;i<3;i++) assert.equal((await request(`/v2/requests/${r.data.id}/cancel`,{user:a,method:'POST'})).data.state,'CANCELLED');
    assert.equal(sql(`SELECT available FROM ux_activity WHERE id=${a}`),'10000');
  });
  await check('kafka-outage-durable-acceptance-recovery',async()=>{
    const a=++id;await fixture(a);compose('stop','kafka');
    const r=await request('/v2/requests',{user:a,method:'POST',body:{requestId:`offline_${a}`,activityId:a}});
    assert.equal(r.status,202);assert.equal(r.data.state,'ACCEPTED');
    const expiring=await request('/v2/requests',{user:a+1,method:'POST',body:{requestId:`expires_${a}`,activityId:a}});
    assert.equal(expiring.status,202);
    sql(`UPDATE ux_request SET deadline=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id='${expiring.data.id}'`);
    await sleep(6000);
    assert.equal(sql(`SELECT state FROM ux_request WHERE id='${r.data.id}'`),'ACCEPTED');
    assert.equal(sql(`SELECT state FROM ux_request WHERE id='${expiring.data.id}'`),'EXPIRED');
    compose('start','kafka');assert.equal((await terminal(a,r.data.id,90000)).state,'SUCCEEDED');
    await kafkaDrained();
    assert.equal((await terminal(a+1,expiring.data.id)).state,'EXPIRED');
    assert.equal(sql(`SELECT COUNT(*) FROM ux_order WHERE request_id='${expiring.data.id}'`),'0');
  });
  await check('redis-outage-rejects-new-but-allows-cancel-and-query',async()=>{
    const a=++id;await fixture(a);const r=await request('/v2/requests',{user:a,method:'POST',body:{requestId:`redis_${a}`,activityId:a}});await terminal(a,r.data.id);
    compose('stop','redis');
    assert.equal((await request('/v2/requests',{user:a+10,method:'POST',body:{requestId:`new_${a}`,activityId:a}})).status,503);
    assert.equal((await request(`/v2/shops/${a}`,{user:a})).status,503);
    assert.equal((await request(`/v2/requests/${r.data.id}`,{user:a})).status,200);
    assert.equal((await request('/v2/requests',{user:a,method:'POST',body:{requestId:`redis_${a}`,activityId:a}})).data.id,r.data.id);
    assert.equal((await request(`/v2/requests/${r.data.id}/cancel`,{user:a,method:'POST'})).data.state,'CANCELLED');
    assert.equal((await request(`/v2/admin/shops/${a}`,{admin:true,method:'PUT',body:{name:'updated-offline',description:'persisted invalidation'}})).status,200);
    assert.ok(Number(sql('SELECT COUNT(*) FROM ux_invalidation'))>0);compose('start','redis');await sleep(2500);
    assert.equal((await request(`/v2/shops/${a}`,{user:a})).data.name,'updated-offline');
    assert.equal(sql(`SELECT COUNT(*) FROM ux_invalidation WHERE cache_key IN ('ux:shop:{${a}}','ux:sold:{${a}}')`),'0');
    for(let i=0;i<30 && Number(sql(`SELECT COUNT(*) FROM ux_reservation_action WHERE request_id='${r.data.id}' AND applied_at IS NULL`))>0;i++)await sleep(200);
    assert.equal(sql(`SELECT COUNT(*) FROM ux_reservation_action WHERE request_id='${r.data.id}' AND applied_at IS NULL`),'0');
  });
  await check('authorization-and-idempotent-replay',async()=>{
    const a=++id;await fixture(a);const body={requestId:`auth_${a}`,activityId:a};
    const r=await request('/v2/requests',{user:a,method:'POST',body});await terminal(a,r.data.id);
    assert.equal((await request(`/v2/requests/${r.data.id}`,{user:a+1})).status,404);
    assert.equal((await request('/v2/admin/status',{user:a})).status,403);
    assert.equal((await request('/v2/requests',{user:a,method:'POST',body})).data.id,r.data.id);
    const other=a+100000;await fixture(other);
    assert.equal((await request('/v2/requests',{user:a,method:'POST',body:{...body,activityId:other}})).status,409);
  });
  await check('http-burst-admission-and-correctness',async()=>{
    const a=++id;await fixture(a,100);
    const samples=[];let next=0;
    await Promise.all(Array.from({length:32},async()=>{
      for(;;){const i=next++;if(i>=1000)return;
        const r=await request('/v2/requests',{user:1000000+i,method:'POST',body:{requestId:`burst_${a}_${i}`,activityId:a}});
        samples.push({i,status:r.status,retryAfter:r.retryAfter,requestId:r.data.id,error:r.data.error});
      }
    }));
    for(let i=0;i<300 && Number(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${a} AND state='ACCEPTED'`))>0;i++)await sleep(100);
    assert.equal(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${a} AND state='ACCEPTED'`),'0');
    const orders=Number(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${a}`));assert.ok(orders>0&&orders<=100);
    assert.ok(Number(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${a}`))<=100);
    const limited=samples.filter(r=>r.status===429||r.status===503);assert.ok(limited.length>0);assert.ok(limited.every(r=>r.retryAfter==='1'));
    assert.ok(samples.every(r=>[200,202,409,429,503].includes(r.status)));
    save(`${dir}/burst-raw.json`,samples);save(`${dir}/burst-summary.json`,{orders,statuses:samples.reduce((acc,r)=>({...acc,[r.status]:(acc[r.status]??0)+1}),{})});
  });
  console.log(`Evidence: ${dir}`);
} catch(e) {save(`${dir}/failure.json`,{message:e.message,stack:e.stack,completed:results});throw e;}
finally {compose('start','redis','kafka');await stop(child);}
