import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
import {root,projectRoot,start,stop,request,fixture,sql,compose,invariant,sleep,save,sourceManifest} from './harness.mjs';
if(process.env.UPGRADE_DB_SCHEMA!=='life_choice_verification'||!process.env.UPGRADE_DB_URL?.includes('/life_choice_verification?')||process.env.SPRING_DATA_REDIS_DATABASE!=='13')throw Error('Use verification.sh');
const dir=`${root}/evidence/pipeline-crash-${Date.now()}`;mkdirSync(`${dir}/faults`,{recursive:true});
const activity=Date.now(),total=200,baseUser=activity*1000;let child;
const args=['--upgrade.faults=true',`--upgrade.fault-directory=${dir}/faults`,'--upgrade.relay-batch=100','--upgrade.relay-window=4','--upgrade.rate-user=100','--upgrade.rate-activity=1000000'];
async function until(fn,timeout=90000){const at=Date.now();while(!(await fn())){if(Date.now()-at>timeout)throw Error('Convergence timeout');await sleep(150);}return Date.now()-at;}
async function submit(i){return request('/v2/requests',{user:baseUser+i,method:'POST',body:{requestId:`crashload_${activity}_${i}`,activityId:activity}});}
try {
 child=await start(`${dir}/prepare.log`,[...args,'--upgrade.jobs=false']);await fixture(activity,total);
 const receipts=[];let index=0;await Promise.all(Array.from({length:8},async()=>{while(index<total){const i=index++,r=await submit(i);assert.equal(r.status,202);receipts.push({i,id:r.data.id});}}));
 assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='ACCEPTED'`)),total);
 await stop(child);child=null;writeFileSync(`${dir}/faults/send-before-mark`,'one-shot\n');const began=Date.now();
 try{child=await start(`${dir}/crash.log`,args);}catch(e){assert.match(e.message,/Application exited: 91/);}
 if(child){await until(()=>child.exitCode!==null,15000);assert.equal(child.exitCode,91);}
 const afterCrash=sql(`SELECT (SELECT COUNT(*) FROM ux_outbox WHERE activity_id=${activity} AND sent_at IS NULL),(SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity})`).split('\t').map(Number);
 child=await start(`${dir}/recovery.log`,args);
 await until(()=>Number(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='SUCCEEDED'`))===total);
 await until(()=>Number(sql(`SELECT COUNT(*) FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id WHERE r.activity_id=${activity} AND a.applied_at IS NULL`))===0);
 const recoveryMs=Date.now()-began;
 for(const r of receipts){const replay=await submit(r.i);assert.equal(replay.status,200);assert.equal(replay.data.id,r.id);assert.equal(replay.data.state,'SUCCEEDED');}
 assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity}`)),total);
 const redisStock=Number(compose('exec','-T','redis','redis-cli','-n','13','GET',`ux:reserve:{${activity}}:stock`).trim());assert.equal(redisStock,0);
 const result={activity,total,point:'send-before-mark',sendWindow:4,batch:100,unsentAfterCrash:afterCrash[0],ordersAfterCrash:afterCrash[1],successfulOrders:total,replayedKeys:total,redisStock,recoveryIncludingRestartMs:recoveryMs,invariants:(await invariant()).invariants,manifest:sourceManifest()};save(`${dir}/results.json`,result);save(`${dir}/receipts.json`,receipts);console.log(JSON.stringify({...result,manifest:undefined}));
 for(const r of receipts)assert.equal((await request(`/v2/requests/${r.id}/confirm`,{user:baseUser+r.i,method:'POST'})).status,200);
 console.log(`Evidence: ${dir}`);
}catch(e){save(`${dir}/failure.json`,{message:e.message,stack:e.stack});throw e;}finally{await stop(child);}
