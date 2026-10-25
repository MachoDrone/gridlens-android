'use strict';
const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const crypto = require('node:crypto');
const vm = require('node:vm');
const {spawnSync} = require('node:child_process');
const {EventEmitter} = require('node:events');
const path = require.resolve('../android/app/src/main/assets/reward-reader/reader.cjs');
const reader = require(path);
const source = fs.readFileSync(path, 'utf8');
const pair = crypto.generateKeyPairSync('ed25519');
const publicBytes = pair.publicKey.export({type:'spki',format:'der'}).subarray(-32);
const owner = reader.base58(publicBytes);
const authorization = owner + ':' + reader.base58(crypto.sign(null, Buffer.from(owner), pair.privateKey));
const args = ['--gridlens-approved-rewards', owner];

function signFixture(options = {}) {
  const seed = pair.privateKey.export({type:'pkcs8',format:'der'}).subarray(-32);
  const wallet = options.wallet || Buffer.from(JSON.stringify([...seed,...publicBytes]));
  const counts = {opens:0,closes:0}; let offset = 0;
  const fakeFS = {constants:fs.constants,readFileSync(path) {
    if (path === '/proc/self/limits') return options.core ? 'Max core file size unlimited unlimited bytes\n' : 'Max core file size 0 0 bytes\n';
    if (path === '/proc/self/coredump_filter') return options.filter ? '00000033\n':'00000000\n';
    throw Error('Unexpected filesystem read');
  },lstatSync() {return {isDirectory:()=>true,isSymbolicLink:()=>Boolean(options.symlink)};},
    openSync(path,flags) {counts.opens++;assert.equal(path,'/synthetic-memory/.nosana/nosana_key.json');assert.equal(flags,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW|fs.constants.O_NONBLOCK);return 41;},
    fstatSync() {return {size:wallet.length,isFile:()=>!options.special};},
    readSync(fd,buffer,start,length) {const n=Math.min(length,wallet.length-offset);wallet.copy(buffer,start,offset,offset+n);offset+=n;return n;},
    closeSync() {counts.closes++;}};
  const module = {exports:{}};
  const process = {env:{PATH:'/usr/local/bin:/usr/bin:/bin',LANG:'C',...(options.env||{})},argv:['node','fixture']};
  vm.runInNewContext(source,{Buffer,module,process,Date,setTimeout,clearTimeout,require(name) {
    if (name === 'node:fs') return fakeFS;
    if (name === 'node:os') return {userInfo:()=>({homedir:'/synthetic-memory'})};
    return require(name);
  }});
  return {reader:module.exports,counts};
}
function deps(options = {}) {
  const calls = {spawns:0,requests:[],signerExited:false};
  const now = options.now || (()=>1800000000000);
  return {calls,now,safeguards(){if(options.safeguards)throw Error('private error');},
    spawnSync(command,argv,opts) {
      calls.spawns++; assert.ok(opts.timeout > 0 && opts.timeout <= 3000);
      assert.equal(opts.killSignal,'SIGKILL'); assert.equal(opts.maxBuffer,256);
      assert.deepEqual(opts.env,{PATH:'/usr/local/bin:/usr/bin:/bin',LANG:'C'});
      assert.deepEqual(opts.stdio,['ignore','pipe','ignore']);
      assert.ok(!argv.join(' ').includes(authorization)); calls.signerExited=true;
      return options.child || {status:0,stdout:Buffer.from(authorization)};
    },https:{request(url,opts,callback) {
      assert.equal(calls.signerExited,true,'HTTPS starts only after signer exits');
      calls.requests.push(url);
      assert.equal(url,'https://dashboard.k8s.prd.nos.ci/api/nodes/rewards');
      assert.equal(opts.method,'GET'); assert.equal(opts.agent,false);
      assert.equal(opts.rejectUnauthorized,true); assert.equal(opts.minVersion,'TLSv1.2');
      assert.equal(opts.headers.Authorization,authorization);
      const req=new EventEmitter();req.destroy=()=>{};
      req.end=()=>queueMicrotask(()=>{
        if(options.networkError){req.emit('error',Error(authorization));return;}
        if(options.neverRespond)return;
        const response=new EventEmitter(); response.statusCode=options.status||200;
        response.headers=options.headers||{}; response.destroy=()=>{}; callback(response);
        if(response.statusCode===200){response.emit('data',Buffer.from(options.body===undefined?' {"claimableUptimeNosRewards":123.456789} ':options.body));response.emit('end');}
      });return req;
    }}};
}

test('reward signer stays in a synthetic filesystem and signs only matched public identity',()=>{
  const fixture=signFixture();assert.equal(fixture.reader.sign(owner).toString(),authorization);
  assert.deepEqual(fixture.counts,{opens:1,closes:1});
});
for(const options of [{core:true},{filter:true},{env:{NODE_OPTIONS:'--require injected'}},{env:{HOME:'/injected'}}])
  test('reward safeguards precede key reads '+JSON.stringify(options),()=>{const f=signFixture(options);assert.throws(()=>f.reader.sign(owner));assert.equal(f.counts.opens,0);});
test('reward owner mismatch is detected without a usable signature',()=>{
  const fixture=signFixture();assert.throws(()=>fixture.reader.sign('11111111111111111111111111111111'),e=>e.identityMismatch===true);
  assert.equal(fixture.counts.closes,1);
});
for(const options of [{symlink:true},{special:true},{wallet:Buffer.from('not JSON')},{wallet:Buffer.alloc(4097)},{wallet:Buffer.from('[01,'+'0,'.repeat(62)+'0]')}])
  test('reward reader refuses unsafe wallet '+Object.keys(options)[0],()=>assert.throws(()=>signFixture(options).reader.sign(owner)));
test('reward byte parser never converts the private buffer to text',()=>{
  const input=Buffer.from(JSON.stringify(Array(64).fill(2)));input.toString=()=>{throw Error('forbidden immutable key');};
  assert.deepEqual(reader.walletBytes(input,input.length),Buffer.alloc(64,2));
});
test('exact reward decimal and actual completion time are returned without unrelated fields',async()=>{
  let time=1800000000000;const d=deps({now:()=>++time,body:'{"claimableUptimeNosRewards":123.456789,"secret":"DO NOT RETURN"}'});
  const result=await reader.main(args,d);
  assert.deepEqual(result,{schema:1,address:owner,observedAtMs:1800000000002,status:'ok',amount:'123.456789'});
  assert.equal(d.calls.spawns,1);assert.equal(d.calls.requests.length,1);
  assert.ok(!JSON.stringify(result).includes(authorization));assert.ok(!JSON.stringify(result).includes('DO NOT RETURN'));
});
test('confirmed zero remains a successful zero',async()=>{
  const result=await reader.main(args,deps({body:'{"claimableUptimeNosRewards":0}'}));
  assert.equal(result.status,'ok');assert.equal(result.amount,'0.000000');
});
test('decimal normalization is exact without unsafe binary float conversions',()=>{
  for(const [token,expected] of [['2.000001','2.000001'],['1e-6','0.000001'],['1.2300000','1.230000'],['1e2','100.000000'],['0e-99','0.000000'],['9223372036854.775807','9223372036854.775807']])
    assert.equal(reader.amountFromResponse('{"claimableUptimeNosRewards":'+token+'}'),expected);
  for(const token of ['0.0000001','1e-999','-0','-1','"0"','null','true','9223372036854.775808','NaN','Infinity'])
    assert.throws(()=>reader.amountFromResponse('{"claimableUptimeNosRewards":'+token+'}'));
});
test('nested and duplicate reward fields cannot supply or overwrite exact top-level amount',()=>{
  for(const value of ['{"nested":{"claimableUptimeNosRewards":10}}','{"claimableUptimeNosRewards":1,"claimableUptimeNosRewards":2}',
    '{"claimableUptimeNosRewards":1,"claimableUptimeNosRew\\u0061rds":2}','[]','null']) assert.throws(()=>reader.amountFromResponse(value));
  assert.equal(reader.amountFromResponse('{"ignored":[{"nested":"},\\\"escaped"}],"claimableUptimeNosRewards":1.2}'),'1.200000');
});
for(const status of [301,302,307,401,403,429,503])
  test('reward HTTP '+status+' never redirects/retries or invents zero',async()=>{
    const d=deps({status,headers:{'retry-after':'864000'}});const result=await reader.main(args,d);
    assert.equal(d.calls.requests.length,1);assert.equal(result.amount,undefined);
    assert.equal(result.retryAfterSeconds,864000);
    assert.equal(result.status,[401,403].includes(status)?'authentication-failed':status===429?'rate-limited':'unavailable');
  });
for(const body of ['{}','bad JSON','{"claimableUptimeNosRewards":null}','{"claimableUptimeNosRewards":-1}','x'.repeat(16385)])
  test('invalid rewards response stays unknown '+body.slice(0,40),async()=>{
    const result=await reader.main(args,deps({body}));assert.equal(result.status,'unavailable');assert.equal(result.amount,undefined);
  });
test('request errors cannot reflect authorization or raw exception text',async()=>{
  const result=await reader.main(args,deps({networkError:true}));assert.equal(result.status,'unavailable');assert.ok(!JSON.stringify(result).includes(authorization));
});
test('signer failure, timeout, mismatch and unsafe process cause no network',async()=>{
  for(const options of [{child:{error:{code:'ETIMEDOUT'},status:null,stdout:Buffer.alloc(0)}},{child:{status:1,stdout:Buffer.alloc(0)}},{child:{status:2,stdout:Buffer.alloc(0)}},{safeguards:true}]){
    const d=deps(options);const result=await reader.main(args,d);assert.equal(d.calls.requests.length,0);assert.equal(result.amount,undefined);
    assert.equal(result.status,options.child?.status===2?'identity-mismatch':'unavailable');
  }
});
test('explicit fixed approval flag, identity and argument count precede any credential handling',async()=>{
  for(const argv of [[owner],[owner,'rewards'],['--gridlens-approved-rewards',owner,'/wallet'],['--gridlens-approved-rewards','bad'],['--claim',owner]]){
    const d=deps();const result=await reader.main(argv,d);assert.equal(result.status,'unavailable');assert.equal(d.calls.spawns,0);assert.equal(d.calls.requests.length,0);
  }
});
test('reward HTTP date and long cooldowns remain honored',()=>{
  assert.equal(reader.retryAfter('Sun, 25 Oct 2026 11:00:00 GMT',Date.parse('2026-10-25T10:00:00Z')),3600);
  assert.equal(reader.retryAfter('864000'),864000);assert.equal(reader.retryAfter('99999999999999999999'),2147483647);
  assert.equal(reader.retryAfter('invalid'),undefined);
});
test('actual isolated process verifies core safeguards without reading any wallet',()=>{
  const check="const r=require(process.argv[1]);try{r.safeguards();process.stdout.write('verified');}catch{process.exitCode=1;}";
  const result=spawnSync('/usr/bin/env',['-i','PATH=/usr/local/bin:/usr/bin:/bin','LANG=C','/bin/sh','-c',
    'ulimit -S -c 0 && ulimit -H -c 0 && printf 0 > /proc/self/coredump_filter && exec "$@"','gridlens-test',process.execPath,'-e',check,path],
    {encoding:'utf8',timeout:3000});
  assert.equal(result.status,0,result.stderr);assert.equal(result.stdout,'verified');assert.equal(result.stderr,'');
});
test('actual signer timeout precedes all networking',async()=>{
  const d=deps();d.spawnSync=(_command,_argv,options)=>spawnSync(process.execPath,['-e','setInterval(()=>{},1000)'],{...options,timeout:25});
  const result=await reader.main(args,d);assert.equal(result.status,'unavailable');assert.equal(d.calls.requests.length,0);
});
test('deadline expires even if signer consumes entire remaining operation time',async()=>{
  const d=deps();let clock=0;d.monotonic=()=>clock;const spawn=d.spawnSync;
  d.spawnSync=(...argv)=>{clock=12001;return spawn(...argv);};
  const result=await reader.main(args,d);assert.equal(result.status,'unavailable');assert.equal(d.calls.requests.length,0);
});
test('captured child authorization buffer is cleared after the fixed request',async()=>{
  const buffer=Buffer.from(authorization);
  const result=await reader.main(args,deps({child:{status:0,stdout:buffer}}));
  assert.equal(result.status,'ok');assert.ok(buffer.every(byte=>byte===0));
});
test('actual helper without approval fails before opening any key or network',()=>{
  const result=spawnSync(process.execPath,[path],{encoding:'utf8',timeout:3000});
  assert.equal(result.status,0);assert.equal(JSON.parse(result.stdout).status,'unavailable');
  assert.equal(result.stderr,'');
});
test('actual isolated signer child operates only on generated in-memory wallet buffers',()=>{
  const fixture=`
const fixtureFS=require('node:fs'),fixtureCrypto=require('node:crypto'),fixtureOS=require('node:os');
const fixturePair=fixtureCrypto.generateKeyPairSync('ed25519');
const fixturePublic=fixturePair.publicKey.export({format:'der',type:'spki'}).subarray(-32);
const fixtureSeed=fixturePair.privateKey.export({format:'der',type:'pkcs8'}).subarray(-32);
const fixtureWallet=Buffer.from(JSON.stringify([...fixtureSeed,...fixturePublic]));let fixturePosition=0;
const origOpen=fixtureFS.openSync,origRead=fixtureFS.readSync,origStat=fixtureFS.fstatSync,origClose=fixtureFS.closeSync,origLstat=fixtureFS.lstatSync;
fixtureOS.userInfo=()=>({homedir:'/synthetic-in-memory'});
fixtureFS.lstatSync=(path,...args)=>path.startsWith('/synthetic-in-memory')?{isDirectory:()=>true,isSymbolicLink:()=>false}:origLstat(path,...args);
fixtureFS.openSync=(path,...args)=>path==='/synthetic-in-memory/.nosana/nosana_key.json'?987654:origOpen(path,...args);
fixtureFS.fstatSync=(fd,...args)=>fd===987654?{isFile:()=>true,size:fixtureWallet.length}:origStat(fd,...args);
fixtureFS.readSync=(fd,target,start,length,...args)=>{if(fd!==987654)return origRead(fd,target,start,length,...args);const n=Math.min(length,fixtureWallet.length-fixturePosition);fixtureWallet.copy(target,start,fixturePosition,fixturePosition+n);fixturePosition+=n;return n;};
fixtureFS.closeSync=(fd,...args)=>fd===987654?undefined:origClose(fd,...args);
process.argv[1]=(${reader.base58.toString().replaceAll('ALPHABET',JSON.stringify('123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'))})(fixturePublic);
`;
  const result=spawnSync('/usr/bin/env',['-i','PATH=/usr/local/bin:/usr/bin:/bin','LANG=C','/bin/sh','-c',
    'ulimit -S -c 0 && ulimit -H -c 0 && printf 0 > /proc/self/coredump_filter && exec "$@"',
    'gridlens-test',process.execPath,'-e',fixture+reader.signerSource()],{encoding:'utf8',timeout:3000});
  assert.equal(result.status,0,result.stderr);assert.match(result.stdout,/^[1-9A-HJ-NP-Za-km-z]{32,44}:[1-9A-HJ-NP-Za-km-z]{64,88}$/);
  assert.equal(result.stderr,'');
});
