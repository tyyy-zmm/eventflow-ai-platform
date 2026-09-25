import {randomBytes} from 'node:crypto';
import {writeFileSync,existsSync} from 'node:fs';
const file=new URL('../../.env',import.meta.url);
if(!existsSync(file)) {
  const content=['MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD','REDIS_PASSWORD','APP_AUTH_SECRET']
    .map(name=>`${name}=${randomBytes(32).toString('hex')}`).join('\n')+'\n';
  writeFileSync(file,content,{mode:0o600,flag:'wx'});
  console.log('Created private local configuration; no credentials printed.');
} else console.log('Existing local configuration retained.');
