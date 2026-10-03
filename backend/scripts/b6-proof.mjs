import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,readdirSync,statSync} from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {root,sql,save} from './harness.mjs';

const directory=path.join(root,'evidence/b6-20260922');
const query=`SELECT JSON_OBJECT(
 'stockViolations',(SELECT COUNT(*) FROM ux_activity a WHERE available<0 OR available>capacity OR available+(SELECT COUNT(*) FROM ux_order o WHERE o.activity_id=a.id AND o.state IN ('PENDING_CONFIRM','CONFIRMED'))<>capacity),
 'stateViolations',(SELECT COUNT(*) FROM ux_request r WHERE (state='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM ux_order o WHERE o.request_id=r.id)) OR (state<>'SUCCEEDED' AND EXISTS(SELECT 1 FROM ux_order o WHERE o.request_id=r.id))),
 'duplicateOrders',(SELECT COUNT(*) FROM (SELECT activity_id,user_id FROM ux_order GROUP BY activity_id,user_id HAVING COUNT(*)>1) d),
 'pending',(SELECT COUNT(*) FROM ux_request WHERE state='ACCEPTED'))`;
const invariants=JSON.parse(sql(query));
const tests=readdirSync(path.join(directory,'backend')).filter(x=>x.endsWith('.txt')).map(file=>{
  const text=readFileSync(path.join(directory,'backend',file),'utf8');
  const match=text.match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);
  assert(match,`Missing test summary: ${file}`);
  return {suite:file,run:Number(match[1]),failed:Number(match[2]),errors:Number(match[3]),skipped:Number(match[4])};
});
const browser=JSON.parse(readFileSync(path.join(directory,'browser/results.json'),'utf8'));
const hashes={};
function digest(file) {
  if(statSync(file).isDirectory()) for(const child of readdirSync(file)) digest(path.join(file,child));
  else hashes[path.relative(root,file)]=createHash('sha256').update(readFileSync(file)).digest('hex');
}
for(const file of ['src','pom.xml','scripts/local.sh','../frontend/src','../frontend/public',
  '../frontend/vite.config.js','../frontend/tests/customer-flow.mjs']) digest(path.resolve(root,file));
const result={at:new Date().toISOString(),environment:{platform:os.platform(),arch:os.arch(),cpu:os.cpus()[0].model,memoryGiB:os.totalmem()/1024**3,node:process.version},
  invariants,tests,browser,hashes,scope:'B6 customer authentication, storefront and order UI only; no performance claim or Agent changes'};
save(path.join(directory,'summary.json'),result);
assert(tests.every(x=>x.failed===0&&x.errors===0&&x.skipped===0));
assert.equal(browser.failure,undefined);assert(browser.results.every(x=>x.passed));
assert(Object.values(invariants).every(x=>x===0),JSON.stringify(invariants));
console.log(JSON.stringify({invariants,backendTests:tests.reduce((sum,x)=>sum+x.run,0),browserScenarios:browser.results.length},null,2));
