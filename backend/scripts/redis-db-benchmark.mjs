import {mkdirSync} from 'node:fs';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {root,start,stop,request,fixture,invariant,sql,rootSql,sleep,save} from './harness.mjs';

const smoke=process.argv.includes('--smoke');
const rounds=smoke?1:Number(process.env.REDIS_DB_ROUNDS??3);
const requests=smoke?200:Number(process.env.REDIS_DB_REQUESTS??1000);
const stock=smoke?20:Number(process.env.REDIS_DB_STOCK??100);
const concurrency=Number(process.env.REDIS_DB_CONCURRENCY??64);
if(!smoke && (rounds<3 || requests<1000)) throw new Error('Formal runs require at least 3 rounds and 1000 requests');
if(stock<=0 || stock>=requests) throw new Error('Stock must be positive and lower than requests');

const dir=`${root}/evidence/redis-db-${Date.now()}`;mkdirSync(dir,{recursive:true});
const results=[];let child;let sequence=Date.now();
const quantile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*p)-1)]??null;};

async function load(mode,activity) {
  const samples=new Array(requests);let cursor=0;
  const started=performance.now();
  async function worker() {
    while(true) {
      const i=cursor++;if(i>=requests)return;
      const sent=performance.now();
      try {
        const response=await request(`/v2/benchmark/${mode}`,{user:1_000_000+i,method:'POST',body:{requestId:`${mode}_${activity}_${i}`,activityId:activity}});
        samples[i]={i,status:response.status,latencyMs:performance.now()-sent,state:response.data.state??null,
          reason:response.data.reason??response.data.error??null,enteredDatabase:response.data.enteredDatabase===true};
      } catch(error) {
        samples[i]={i,status:0,latencyMs:performance.now()-sent,error:error.message,enteredDatabase:false};
      }
    }
  }
  await Promise.all(Array.from({length:concurrency},worker));
  return {samples,elapsedSeconds:(performance.now()-started)/1000};
}

function statementMetrics() {
  const raw=rootSql(`SELECT REPLACE(REPLACE(DIGEST_TEXT,'\\n',' '),'\\t',' '),COUNT_STAR,SUM_ROWS_EXAMINED,SUM_ROWS_AFFECTED,ROUND(SUM_TIMER_WAIT/1000000000,3)
    FROM performance_schema.events_statements_summary_by_digest
    WHERE SCHEMA_NAME='upgrade' AND (
      DIGEST_TEXT LIKE '%ux_activity%' OR DIGEST_TEXT LIKE '%ux_request%' OR
      DIGEST_TEXT LIKE '%ux_order%' OR DIGEST_TEXT LIKE '%ux_outbox%' OR
      DIGEST_TEXT LIKE '%ux_reservation_action%')
    ORDER BY COUNT_STAR DESC;`);
  const rows=raw.split('\n').filter(Boolean).map(line=>{const [digest,count,examined,affected,totalMs]=line.split('\t');return {digest,count:Number(count),rowsExamined:Number(examined),rowsAffected:Number(affected),totalMs:Number(totalMs)};});
  return {rows,statementCount:rows.reduce((n,row)=>n+row.count,0),rowsExamined:rows.reduce((n,row)=>n+row.rowsExamined,0),rowsAffected:rows.reduce((n,row)=>n+row.rowsAffected,0)};
}

try {
  child=await start(`${dir}/server.log`,['--upgrade.jobs=false']);
  save(`${dir}/environment.json`,{started:new Date().toISOString(),platform:os.platform(),arch:os.arch(),cpus:os.cpus().length,
    model:os.cpus()[0]?.model,totalMemory:os.totalmem(),heap:'384m',pool:12,httpThreads:48,rounds,requests,stock,concurrency,
    scope:'Local single-node Redis/MySQL A/B. HMAC benchmark identity excludes customer-session SQL. Synchronous order completion isolates the admission decision; Kafka is not part of this experiment.'});
  for(let round=1;round<=rounds;round++) {
    const modes=round%2===1?['mysql-first','redis-first']:['redis-first','mysql-first'];
    for(const mode of modes) {
      const activity=++sequence;await fixture(activity,stock);await sleep(300);
      rootSql('TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;');
      const loaded=await load(mode,activity);
      const db=statementMetrics();
      const counts=JSON.parse(sql(`SELECT JSON_OBJECT('requests',COUNT(*),'orders',SUM(state='SUCCEEDED'),'rejected',SUM(state='REJECTED')) FROM ux_request WHERE activity_id=${activity}`));
      const statusCounts={},reasonCounts={};
      for(const sample of loaded.samples) {
        statusCounts[sample.status]=(statusCounts[sample.status]??0)+1;
        const reason=sample.reason??sample.state??'UNKNOWN';reasonCounts[reason]=(reasonCounts[reason]??0)+1;
      }
      const result={round,mode,activity,requests,stock,concurrency,elapsedSeconds:loaded.elapsedSeconds,
        entryQps:requests/loaded.elapsedSeconds,latencyP50:quantile(loaded.samples.map(x=>x.latencyMs),.5),
        latencyP95:quantile(loaded.samples.map(x=>x.latencyMs),.95),latencyP99:quantile(loaded.samples.map(x=>x.latencyMs),.99),
        databaseEntrants:loaded.samples.filter(x=>x.enteredDatabase).length,statusCounts,reasonCounts,dbRows:counts,sql:db,
        invariants:(await invariant()).invariants};
      if(result.databaseEntrants!==(mode==='redis-first'?stock:requests)) throw new Error(`Unexpected DB entrants: ${JSON.stringify(result)}`);
      if(Number(counts.orders)!==stock || result.invariants.stockViolations!==0 || result.invariants.stateViolations!==0)
        throw new Error(`Correctness check failed: ${JSON.stringify(result)}`);
      save(`${dir}/${round}-${mode}-raw.json`,loaded.samples);results.push(result);save(`${dir}/results.json`,results);
      console.log(`DONE round=${round} mode=${mode} dbEntrants=${result.databaseEntrants} sql=${db.statementCount} qps=${result.entryQps.toFixed(1)} p95=${result.latencyP95.toFixed(2)}ms`);
    }
  }
  const byMode=mode=>results.filter(r=>r.mode===mode);
  const median=values=>quantile(values,.5),worst=values=>Math.max(...values);
  const baseline=byMode('mysql-first'),redis=byMode('redis-first');
  const summary={databaseEntrantReduction:1-median(redis.map(x=>x.databaseEntrants))/median(baseline.map(x=>x.databaseEntrants)),
    sqlStatementReduction:1-median(redis.map(x=>x.sql.statementCount))/median(baseline.map(x=>x.sql.statementCount)),
    mysqlFirst:{medianQps:median(baseline.map(x=>x.entryQps)),worstP95:worst(baseline.map(x=>x.latencyP95)),medianSql:median(baseline.map(x=>x.sql.statementCount))},
    redisFirst:{medianQps:median(redis.map(x=>x.entryQps)),worstP95:worst(redis.map(x=>x.latencyP95)),medianSql:median(redis.map(x=>x.sql.statementCount))}};
  save(`${dir}/summary.json`,summary);
  console.log(`SUMMARY dbEntrantReduction=${(summary.databaseEntrantReduction*100).toFixed(1)}% sqlReduction=${(summary.sqlStatementReduction*100).toFixed(1)}% evidence=${dir}`);
} catch(error) {
  save(`${dir}/failure.json`,{message:error.message,stack:error.stack,completed:results});throw error;
} finally { await stop(child); }
