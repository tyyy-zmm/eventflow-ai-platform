import {createHmac} from 'node:crypto';
import {spawn,execFileSync} from 'node:child_process';
import {mkdirSync,openSync,closeSync,writeFileSync} from 'node:fs';
import {once} from 'node:events';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
export const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
export const root=fileURLToPath(new URL('..',import.meta.url));
export const projectRoot=fileURLToPath(new URL('../..',import.meta.url));
export const base=process.env.BENCH_BASE_URL??'http://127.0.0.1:8093';
export function token(user=1,role='user') {
  const payload=`${user}:${role}:${Math.floor(Date.now()/1000)+7200}`;
  return `${payload}.${createHmac('sha256',process.env.UPGRADE_AUTH_SECRET??process.env.APP_AUTH_SECRET).update(payload).digest('base64url')}`;
}
export async function request(route,{user=1,admin=false,method='GET',body}={}) {
  const res=await fetch(base+route,{method,headers:{Authorization:`Bearer ${token(user,admin?'admin':'user')}`,'Content-Type':'application/json'},
    body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(8000)});
  const text=await res.text();let data;try{data=JSON.parse(text);}catch{data={raw:text.slice(0,200)};}
  return {status:res.status,data,retryAfter:res.headers.get('retry-after')};
}
export function sql(query) {
  return execFileSync('docker',['compose','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -ulife_choice -D life_choice --batch --raw --skip-column-names'],
    {cwd:projectRoot,input:query,encoding:'utf8',timeout:15000}).trim();
}
export function rootSql(query) {
  return execFileSync('docker',['compose','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names'],
    {cwd:projectRoot,input:query,encoding:'utf8',timeout:15000}).trim();
}
export function compose(...args) { return execFileSync('docker',['compose',...args],{cwd:projectRoot,encoding:'utf8',timeout:90000,stdio:['ignore','pipe','pipe']}); }
export async function start(log,extra=[]) {
  mkdirSync(path.dirname(log),{recursive:true});const fd=openSync(log,'a',0o600);
  const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java'):'java';
  const child=spawn(java,['-Xmx384m','-jar','target/backend-upgrade-1.0.0.jar','--upgrade.benchmark=true',...extra],{cwd:root,env:process.env,stdio:['ignore',fd,fd]});
  closeSync(fd);
  for(let i=0;i<120;i++) {
    if(child.exitCode!==null) throw new Error(`Application exited: ${child.exitCode}; inspect ${log}`);
    try { if((await request('/v2/admin/status',{admin:true})).status===200) return child; }catch{}
    await sleep(250);
  }
  await stop(child);throw new Error('Application startup timeout');
}
export async function stop(child) {
  if(!child || child.exitCode!==null || child.signalCode!==null) return;
  const ended=once(child,'exit');child.kill('SIGTERM');
  const kill=setTimeout(()=>child.kill('SIGKILL'),10000);
  await ended;clearTimeout(kill);
}
export async function fixture(id,stock=10000) {
  const r=await request(`/v2/admin/fixtures/${id}?stock=${stock}`,{admin:true,method:'POST'});
  if(r.status!==200) throw new Error(`Fixture: ${JSON.stringify(r)}`);
}
export async function terminal(user,id,timeout=90000) {
  const started=Date.now();
  while(Date.now()-started<timeout) {
    const r=await request(`/v2/requests/${id}`,{user});
    if(r.status===200 && r.data.state!=='ACCEPTED') return r.data;
    await sleep(150);
  }
  throw new Error(`Request did not finish: ${id}`);
}
export async function invariant() {
  const r=await request('/v2/admin/status',{admin:true});
  if(r.status!==200 || r.data.invariants.stockViolations!==0 || r.data.invariants.stateViolations!==0)
    throw new Error(`Invariant violation: ${JSON.stringify(r)}`);
  return r.data;
}
export async function kafkaDrained(timeout=90000) {
  const started=Date.now();
  while(Date.now()-started<timeout) {
    const output=compose('exec','-T','kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','localhost:29092','--describe','--group','upgrade-orders-v1');
    const rows=output.split('\n').map(line=>line.trim().split(/\s+/)).filter(row=>row[0]==='upgrade-orders-v1'&&row[1]==='upgrade-orders-v1');
    if(rows.length>=1 && rows.every(row=>row[5]==='0')) return;
    await sleep(1000);
  }
  throw new Error('Kafka offsets did not catch up after restart');
}
export function save(file,data) { writeFileSync(file,JSON.stringify(data,null,2)+'\n'); }
