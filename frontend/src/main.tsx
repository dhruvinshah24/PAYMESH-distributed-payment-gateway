import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, AlertTriangle, ArrowRight, Banknote, CheckCircle2, ChevronRight, Clock3, Database, GitBranch, HeartPulse, Layers, LayoutDashboard, Play, RefreshCw, Server, ShieldCheck, SlidersHorizontal, Zap } from 'lucide-react';
import './style.css';

const API=(import.meta.env.VITE_API_URL||'http://localhost:8080/api').replace(/\/$/,'');
async function api(path:string, init?:RequestInit){const r=await fetch(API+path,init);const text=await r.text();let data:any;try{data=text?JSON.parse(text):{}}catch{data={raw:text}};if(!r.ok)throw new Error(data.error||'Request failed');return data}
const post=(p:string,b?:any)=>api(p,{method:'POST',headers:{'Content-Type':'application/json'},body:b?JSON.stringify(b):undefined});
const money=(n:any)=>new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2}).format(Number(n||0));

function App(){
 const [page,setPage]=useState('Overview'); const [state,setState]=useState<any>({}); const [payments,setPayments]=useState<any[]>([]); const [events,setEvents]=useState<any[]>([]); const [cons,setCons]=useState<any>({}); const [ledger,setLedger]=useState<any>({}); const [settlement,setSettlement]=useState<any>({}); const [clock,setClock]=useState<any>({}); const [election,setElection]=useState<any>({}); const [toast,setToast]=useState(''); const [busy,setBusy]=useState(false);
 const [amount,setAmount]=useState('45000'); const [method,setMethod]=useState('UPI'); const [processor,setProcessor]=useState(''); const [idem,setIdem]=useState('');
 const [e6State,setE6State]=useState<any>({}); const [e6Cons,setE6Cons]=useState<any>({}); const [e6Payments,setE6Payments]=useState<any>({primary:[],replica:[]}); const [e6Events,setE6Events]=useState<any[]>([]); const [e6Busy,setE6Busy]=useState(false); const [e6Progress,setE6Progress]=useState<any>(null);
 const refresh=async()=>{try{const [s,p,e,c,l,se,el]=await Promise.all([api('/state'),api('/payments'),api('/events'),api('/consistency'),api('/ledger/verify'),api('/settlement'),api('/election')]);setState(s);setPayments(p);setEvents(e);setCons(c);setLedger(l);setSettlement(se);setElection(el)}catch(err:any){setToast(err.message+' — start MySQL + Spring Boot')}};
 useEffect(()=>{refresh();const t=setInterval(refresh,2500);return()=>clearInterval(t)},[]);
 const action=async(a:string)=>{setBusy(true);try{await post('/control/'+a);setToast(a.replaceAll('_',' ')+' completed');await refresh()}catch(e:any){setToast(e.message)}finally{setBusy(false)}};
 const createPayment=async()=>{setBusy(true);try{const r=await post('/payments',{orderId:'ORDER-'+Date.now(),customerId:'C-1001',amount:Number(amount.replaceAll(',','')),currency:'INR',method,processor:processor||undefined,idempotencyKey:idem||'IDM-'+Date.now()});setToast(`${r.paymentId} • ${r.status} • ${r.processor} • ${r.node}`);await refresh()}catch(e:any){setToast(e.message)}finally{setBusy(false)}};
 const runDemo=async()=>{setBusy(true);try{await action('RESET');for(const x of [['45000','UPI'],['25000','CARD'],['75000','NETBANKING']]){await post('/payments',{orderId:'DEMO-'+Date.now()+Math.random(),customerId:'C-1001',amount:Number(x[0]),currency:'INR',method:x[1],idempotencyKey:'DEMO-'+x[0]+'-'+Date.now()+Math.random()});}await action('FAIL_PRIMARY');await action('START_ELECTION');await action('PROMOTE_BACKUP');await post('/payments',{orderId:'DEMO-1004',customerId:'C-1001',amount:50000,currency:'INR',method:'UPI',processor:'UPI-Processor',idempotencyKey:'DEMO-PAY-1004-'+Date.now()});await action('RECOVER_PRIMARY');await action('RESTORE_REPLICATION');setToast('Experiment 5 full failover demo completed')}catch(e:any){setToast(e.message)}finally{setBusy(false);refresh()}};

 const refreshE6=async()=>{try{const [s,c,p,e]=await Promise.all([api('/experiment6/state'),api('/experiment6/consistency'),api('/experiment6/payments'),api('/experiment6/events')]);setE6State(s);setE6Cons(c);setE6Payments(p);setE6Events(e)}catch(err:any){setToast(err.message+' — start MySQL + Spring Boot')}};
 useEffect(()=>{if(page!=='Experiment 6')return;refreshE6();const t=setInterval(refreshE6,2500);return()=>clearInterval(t)},[page]);
 const e6Action=async(name:string)=>{setE6Busy(true);try{
   if(name==='RESET')await post('/experiment6/reset');
   else if(name==='FAIL_REPLICA')await post('/experiment6/replica/fail');
   else if(name==='RECOVER_REPLICA')await post('/experiment6/replica/recover');
   else if(name==='RESYNC')await post('/experiment6/resync');
   else if(name==='SET_SYNC')await post('/experiment6/model/SYNCHRONOUS');
   else if(name==='SET_ASYNC')await post('/experiment6/model/ASYNCHRONOUS');
   else if(name==='CHECK_CONSISTENCY')await api('/experiment6/consistency');
   setToast(name.replaceAll('_',' ')+' completed');await refreshE6()
 }catch(e:any){setToast(e.message)}finally{setE6Busy(false)}};
 const e6Write=async(model:'sync'|'async',paymentId:string,amt:number,mth:string)=>{setE6Busy(true);try{
   const r=await post('/experiment6/'+model,{paymentId:paymentId||undefined,amount:amt,method:mth});
   setToast(`${r.paymentId} • ${r.status} • ${r.committed?'COMMITTED':'BLOCKED'}${r.latencyMs!=null?' • '+r.latencyMs+'ms':''}${r.queuedDelayMs!=null?' • queued '+r.queuedDelayMs+'ms':''}`);
   await refreshE6()
 }catch(e:any){setToast(e.message)}finally{setE6Busy(false)}};
 const runE6Demo=async()=>{
   setE6Busy(true);
   const total=15; let step=0;
   const go=async(label:string,fn:()=>Promise<any>)=>{step++;setE6Progress({step,total,label});await fn()};
   try{
     await go('Reset experiment',()=>post('/experiment6/reset'));
     await go('Select SYNCHRONOUS',()=>post('/experiment6/model/SYNCHRONOUS'));
     await go('Write PAY-6001 ₹45,000 GPay',()=>post('/experiment6/sync',{paymentId:'PAY-6001',amount:45000,method:'GPay'}));
     await go('Write PAY-6002 ₹25,000 Credit Card',()=>post('/experiment6/sync',{paymentId:'PAY-6002',amount:25000,method:'Credit Card'}));
     await go('Check consistency (expect CONSISTENT)',()=>api('/experiment6/consistency'));
     await go('Switch to ASYNCHRONOUS',()=>post('/experiment6/model/ASYNCHRONOUS'));
     await go('Write PAY-6003 ₹75,000 HDFC Bank',()=>post('/experiment6/async',{paymentId:'PAY-6003',amount:75000,method:'HDFC Bank'}));
     await go('Check consistency immediately (expect TEMPORARY LAG)',()=>api('/experiment6/consistency'));
     await go('Wait for asynchronous replication',()=>new Promise(r=>setTimeout(r,2800)));
     await go('Check consistency again (expect CONSISTENT)',()=>api('/experiment6/consistency'));
     await go('Fail replica NODE-2',()=>post('/experiment6/replica/fail'));
     await go('Attempt synchronous write PAY-6004 ₹50,000 PhonePe (expect BLOCKED)',()=>post('/experiment6/sync',{paymentId:'PAY-6004',amount:50000,method:'PhonePe'}));
     await go('Recover replica NODE-2',()=>post('/experiment6/replica/recover'));
     await go('Resynchronize replica',()=>post('/experiment6/resync'));
     await go('Final consistency check',()=>api('/experiment6/consistency'));
     setToast('Experiment 6 full scenario completed')
   }catch(e:any){setToast(e.message)}
   finally{setE6Busy(false);setE6Progress(null);refreshE6()}
 };
 const success=payments.filter(p=>p.status==='SUCCESS').length; const volume=payments.reduce((a,p)=>a+Number(p.amount||0),0);
 return <div className="shell">
  <aside className="sidebar"><div className="brand"><div className="brandMark">P</div><div><div className="brandName">PAYMESH</div><div className="brandSub">Distributed Payment Fabric</div></div></div>
   <div className="navLabel">CONTROL PLANE</div>{['Overview','Payments','Experiment 5','Experiment 6','Nodes & Election','Clock Synchronization','Ledger & Settlement','System Logs'].map(x=><button key={x} className={page===x?'nav active':'nav'} onClick={()=>setPage(x)}>{x==='Overview'?<LayoutDashboard/>:x==='Payments'?<Banknote/>:x==='Experiment 5'?<ShieldCheck/>:x==='Experiment 6'?<Layers/>:x==='Nodes & Election'?<GitBranch/>:x==='Clock Synchronization'?<Clock3/>:x==='Ledger & Settlement'?<Database/>:<Activity/>}<span>{x}</span>{page===x&&<ChevronRight className="navArrow"/>}</button>)}
   <div className="sideBottom"><div className="miniStatus"><span className="dot"></span> Gateway online</div><button className="demoBtn" onClick={runDemo} disabled={busy}><Play size={15}/> Run full viva demo</button></div>
  </aside>
  <main className="main"><header><div><div className="eyebrow">DISTRIBUTED PAYMENT GATEWAY / CONTROL CENTER</div><h1>{page}</h1></div><div className="headerRight"><span className="pill"><span className="pulse"></span> LIVE</span><button className="iconBtn" onClick={refresh}><RefreshCw size={17}/></button></div></header>
   {toast&&<div className="toast"><Zap size={16}/>{toast}<button onClick={()=>setToast('')}>×</button></div>}
   {page==='Overview'&&<Overview state={state} payments={payments} cons={cons} success={success} volume={volume} action={action} busy={busy} setPage={setPage} />}
   {page==='Payments'&&<Payments payments={payments} amount={amount} setAmount={setAmount} method={method} setMethod={setMethod} processor={processor} setProcessor={setProcessor} idem={idem} setIdem={setIdem} create={createPayment}/>} 
   {page==='Experiment 5'&&<Experiment state={state} cons={cons} payments={payments} action={action} busy={busy} runDemo={runDemo}/>}
   {page==='Experiment 6'&&<Experiment6 e6State={e6State} e6Cons={e6Cons} e6Payments={e6Payments} e6Events={e6Events} e6Busy={e6Busy} e6Progress={e6Progress} e6Action={e6Action} e6Write={e6Write} runE6Demo={runE6Demo}/>}
   {page==='Nodes & Election'&&<Nodes state={state} action={action} election={election}/>}
   {page==='Clock Synchronization'&&<Clock clock={clock} setClock={setClock} action={action}/>} 
   {page==='Ledger & Settlement'&&<Ledger ledger={ledger} settlement={settlement} action={action}/>} 
   {page==='System Logs'&&<Logs events={events}/>} 
  </main></div>
}

function Overview({state,payments,cons,success,volume,action,busy,setPage}:any){return <>
 <section className="hero"><div><span className="tag">EXPERIMENT 5 / PRIMARY-BACKUP</span><h2>Resilient payments,<br/><em>observable by design.</em></h2><p>PAYMESH connects payment processing, replication, election, logical clocks and ledger verification into one runnable distributed-systems lab.</p><button onClick={()=>setPage('Experiment 5')} className="primaryBtn">Open fault-tolerance lab <ArrowRight size={16}/></button></div><div className="topology"><NodeCard node="NODE-1" role={state.activePrimary==='NODE-1'?'PRIMARY':'BACKUP'} alive={state.primaryAlive}/><div className="repLine"><span>SYNC</span><i></i><i></i><i></i></div><NodeCard node="NODE-2" role={state.activePrimary==='NODE-2'?'PRIMARY':'BACKUP'} alive={state.backupAlive}/></div></section>
 <div className="metrics"><Metric label="Payment volume" value={money(volume)} sub={`${payments.length} transactions`}/><Metric label="Successful" value={success} sub="captured payment events"/><Metric label="Replication" value={cons.consistent?'CONSISTENT':'DRIFT'} sub={`${cons.primaryCount||0} primary / ${cons.backupCount||0} backup`}/><Metric label="Lamport clock" value={state.lamport||0} sub="logical event time"/></div>
 <section className="grid2"><div className="panel"><PanelTitle icon={<ShieldCheck/>} title="Resilience status" action="Experiment 5"/><div className="statusRows"><StatusRow label="Primary node" value={state.primaryAlive?'HEALTHY':'FAILED'} ok={state.primaryAlive}/><StatusRow label="Backup node" value={state.backupAlive?'HEALTHY':'FAILED'} ok={state.backupAlive}/><StatusRow label="Replication link" value={state.replicationEnabled?'SYNCHRONOUS':'DROPPED'} ok={state.replicationEnabled}/><StatusRow label="Coordinator" value={state.coordinator||'—'} ok={true}/></div></div><div className="panel"><PanelTitle icon={<GitBranch/>} title="Distributed control"/><div className="controlGrid"><Control label="Fail primary" on={()=>action('FAIL_PRIMARY')} disabled={busy}/><Control label="Elect coordinator" on={()=>action('START_ELECTION')} disabled={busy}/><Control label="Promote backup" on={()=>action('PROMOTE_BACKUP')} disabled={busy}/><Control label="Resync" on={()=>action('RESTORE_REPLICATION')} disabled={busy}/></div></div></section>
 <section className="panel"><PanelTitle icon={<Activity/>} title="Recent payment events" action="Live"/><PaymentTable rows={payments.slice(0,6)}/></section>
 </>}
function NodeCard({node,role,alive}:any){return <div className={'nodeCard '+(!alive?'down':'')}><div className="nodeTop"><Server size={17}/><span>{node}</span><b>{alive?'●':'×'}</b></div><strong>{role}</strong><small>{alive?'Ready to process':'Failure injected'}</small></div>}
function Metric({label,value,sub}:any){return <div className="metric"><span>{label}</span><strong>{value}</strong><small>{sub}</small></div>}
function PanelTitle({icon,title,action}:any){return <div className="panelTitle"><span className="panelIcon">{icon}</span><h3>{title}</h3>{action&&<span className="panelAction">{action}</span>}</div>}
function StatusRow({label,value,ok}:any){return <div className="statusRow"><span>{label}</span><b className={ok?'ok':'bad'}>{ok?<CheckCircle2 size={15}/>:<AlertTriangle size={15}/>} {value}</b></div>}
function Control({label,on,disabled}:any){return <button className="control" onClick={on} disabled={disabled}>{label}<ChevronRight size={14}/></button>}

function Payments({payments,amount,setAmount,method,setMethod,processor,setProcessor,idem,setIdem,create}:any){return <><section className="panel formPanel"><PanelTitle icon={<Banknote/>} title="Create payment" action="Real backend transaction"/><div className="formGrid"><label>Amount (INR)<input value={amount} onChange={e=>setAmount(e.target.value)}/></label><label>Method<select value={method} onChange={e=>setMethod(e.target.value)}><option value="UPI">UPI</option><option value="CARD">Card</option><option value="NETBANKING">Net Banking</option></select></label><label>Processor override<select value={processor} onChange={e=>setProcessor(e.target.value)}><option value="">Smart route</option><option>UPI-Processor</option><option>Card-Processor</option><option>Banking-Processor</option></select></label><label>Idempotency key<input placeholder="Auto-generated if blank" value={idem} onChange={e=>setIdem(e.target.value)}/></label></div><button className="primaryBtn" onClick={create}>Process payment <ArrowRight size={16}/></button></section><section className="panel"><PanelTitle icon={<Activity/>} title="Payment ledger" action={`${payments.length} shown`}/><PaymentTable rows={payments}/></section></>}
function PaymentTable({rows}:any){return <div className="tableWrap"><table><thead><tr><th>Payment</th><th>Amount</th><th>Method</th><th>Processor</th><th>Status</th><th>Node</th><th>Lamport</th></tr></thead><tbody>{rows.length?rows.map((p:any)=><tr key={p.payment_id}><td><b>{p.payment_id}</b><small>{p.order_id}</small></td><td>{money(p.amount)}</td><td>{p.method}</td><td>{p.processor_id||'—'}</td><td><span className={'status '+(p.status==='SUCCESS'?'success':'pending')}>{p.status}</span></td><td>{p.primary_node_id}</td><td>{p.lamport_ts}</td></tr>):<tr><td colSpan={7} className="empty">No payments yet.</td></tr>}</tbody></table></div>}

function Experiment({state,cons,payments,action,busy,runDemo}:any){return <><section className="experimentHero"><div><span className="tag">LIVE LAB / FAULT TOLERANCE</span><h2>Primary → Backup → Election → Recovery</h2><p>The demo makes the distributed state transition visible. Synchronous replication means the backup acknowledgement is part of the commit path.</p></div><button className="primaryBtn" onClick={runDemo} disabled={busy}><Play size={16}/> Run complete scenario</button></section><section className="stepper"><Step n="01" title="Normal operation" text="NODE-1 primary" done={state.primaryAlive&&state.activePrimary==='NODE-1'}/><Step n="02" title="Inject failure" text="Heartbeat timeout" done={!state.primaryAlive}/><Step n="03" title="Bully election" text="NODE-2 wins" done={state.coordinator==='NODE-2'}/><Step n="04" title="Failover payment" text="New primary accepts" done={state.activePrimary==='NODE-2'}/><Step n="05" title="Resync" text="Consistency = 0 mismatch" done={cons.consistent}/></section><div className="grid2"><div className="panel"><PanelTitle icon={<Server/>} title="Node state"/><div className="nodeLarge"><NodeCard node="NODE-1" role={state.activePrimary==='NODE-1'?'PRIMARY':'FORMER PRIMARY'} alive={state.primaryAlive}/><NodeCard node="NODE-2" role={state.activePrimary==='NODE-2'?'PRIMARY':'BACKUP'} alive={state.backupAlive}/></div></div><div className="panel"><PanelTitle icon={<ShieldCheck/>} title="Consistency proof"/><div className="proof"><div><span>Primary records</span><strong>{cons.primaryCount||0}</strong></div><div><span>Backup records</span><strong>{cons.backupCount||0}</strong></div><div><span>Field mismatches</span><strong className={cons.mismatches?'badText':'goodText'}>{cons.mismatches||0}</strong></div><div className="proofResult">{cons.consistent?<><CheckCircle2/> DATA IS CONSISTENT</>:<><AlertTriangle/> REPLICATION DRIFT</>}</div></div></div></div><section className="panel"><PanelTitle icon={<Activity/>} title="Failure injection controls"/><div className="controlGrid big"><Control label="Fail NODE-1" on={()=>action('FAIL_PRIMARY')} disabled={busy}/><Control label="Recover NODE-1" on={()=>action('RECOVER_PRIMARY')} disabled={busy}/><Control label="Start Bully election" on={()=>action('START_ELECTION')} disabled={busy}/><Control label="Promote NODE-2" on={()=>action('PROMOTE_BACKUP')} disabled={busy}/><Control label="Drop replication" on={()=>action('DROP_REPLICATION')} disabled={busy}/><Control label="Set synchronous" on={()=>action('SET_SYNC')} disabled={busy}/><Control label="Set asynchronous" on={()=>action('SET_ASYNC')} disabled={busy}/><Control label="Restore + resync" on={()=>action('RESTORE_REPLICATION')} disabled={busy}/><Control label="Fail backup" on={()=>action('FAIL_BACKUP')} disabled={busy}/><Control label="Reset lab" on={()=>action('RESET')} disabled={busy}/></div></section></>}
function Step({n,title,text,done}:any){return <div className={'step '+(done?'done':'')}><span>{done?<CheckCircle2/>:n}</span><b>{title}</b><small>{text}</small></div>}

function Experiment6({e6State,e6Cons,e6Payments,e6Events,e6Busy,e6Progress,e6Action,e6Write,runE6Demo}:any){
 const [pid,setPid]=useState(''); const [amt,setAmt]=useState('45000'); const [mth,setMth]=useState('GPay');
 const model=e6State.activeModel||'SYNCHRONOUS'; const replicaAlive=e6State.replica?.alive;
 const methods=['GPay','PhonePe','Paytm','Credit Card','Debit Card','HDFC Bank','ICICI Bank','SBI','Axis Bank'];
 return <>
  <section className="experimentHero"><div><span className="tag">EXPERIMENT 6 / DATA CONSISTENCY &amp; REPLICATION</span><h2>Data Consistency &amp; Replication Models</h2><p>Implementation of Data Consistency and Replication Models. Application-level synchronous and asynchronous primary-backup replication with measurable replica lag and a deterministic consistency checker — independent of Experiment 5's election/failover state.</p>{e6Progress&&<div className="note">STEP {e6Progress.step} / {e6Progress.total} — {e6Progress.label}</div>}</div><button className="primaryBtn" onClick={runE6Demo} disabled={e6Busy}><Play size={16}/> Run Full Experiment 6</button></section>

  <div className="metrics cols5">
   <Metric label="Active model" value={model} sub="synchronous / asynchronous"/>
   <Metric label="Primary" value={e6State.primary?.nodeId||'NODE-1'} sub={e6State.primary?.alive===false?'OFFLINE':'ONLINE'}/>
   <Metric label="Replica" value={e6State.replica?.nodeId||'NODE-2'} sub={replicaAlive===false?'OFFLINE':'ONLINE'}/>
   <Metric label="Consistency" value={e6State.consistency?.consistent?'CONSISTENT':'INCONSISTENT'} sub={`${e6State.consistency?.mismatches||0} mismatches`}/>
   <Metric label="Replica lag" value={(e6State.replication?.lagMs||0)+' ms'} sub={`${e6State.replication?.pendingRecords||0} pending`}/>
  </div>

  <section className="panel"><PanelTitle icon={<Layers/>} title="Replication model"/>
   <div className="modelToggle">
    <button className={'modelBtn '+(model==='SYNCHRONOUS'?'active':'')} onClick={()=>e6Action('SET_SYNC')} disabled={e6Busy}>SYNCHRONOUS</button>
    <button className={'modelBtn '+(model==='ASYNCHRONOUS'?'active':'')} onClick={()=>e6Action('SET_ASYNC')} disabled={e6Busy}>ASYNCHRONOUS</button>
   </div>
   <p className="note">{model==='SYNCHRONOUS'?'Primary waits for replica acknowledgement before commit.':'Primary commits immediately. Replica updates in background.'}</p>
  </section>

  <section className="panel"><PanelTitle icon={<GitBranch/>} title="Replication pipeline"/>
   <div className="topology"><NodeCard node={e6State.primary?.nodeId||'NODE-1'} role="PRIMARY" alive={e6State.primary?.alive!==false}/><div className="repLine"><span>{model}</span><i></i><i></i><i></i></div><NodeCard node={e6State.replica?.nodeId||'NODE-2'} role="REPLICA" alive={replicaAlive!==false}/></div>
  </section>

  <div className="grid2">
   <div className="panel"><PanelTitle icon={<Database/>} title="Primary database" action="NODE-1"/><Exp6PaymentTable side="primary" primary={e6Payments.primary} replica={e6Payments.replica}/></div>
   <div className="panel"><PanelTitle icon={<Database/>} title="Replica database" action="NODE-2"/><Exp6PaymentTable side="replica" primary={e6Payments.primary} replica={e6Payments.replica}/></div>
  </div>

  <div className="panel"><PanelTitle icon={<ShieldCheck/>} title="Consistency verification"/>
   <div className="proof">
    <div><span>Primary records</span><strong>{e6Cons.primaryCount||0}</strong></div>
    <div><span>Replica records</span><strong>{e6Cons.replicaCount||0}</strong></div>
    <div><span>Mismatches</span><strong className={e6Cons.mismatches?'badText':'goodText'}>{e6Cons.mismatches||0}</strong></div>
    <div className="proofResult">{e6Cons.consistent?<><CheckCircle2/> DATA CONSISTENT</>:replicaAlive===false?<><AlertTriangle/> REPLICA UNAVAILABLE</>:<><AlertTriangle/> TEMPORARILY INCONSISTENT</>}</div>
   </div>
   {!e6Cons.consistent&&e6Cons.details?.length>0&&<p className="note">Reason: {e6Cons.details.map((d:any)=>`${d.paymentId} (${d.type})`).join(', ')}</p>}
  </div>

  <section className="panel"><PanelTitle icon={<Activity/>} title="Control panel"/>
   <div className="controlGrid big">
    <Control label="Reset experiment" on={()=>e6Action('RESET')} disabled={e6Busy}/>
    <Control label="Synchronous write" on={()=>e6Write('sync','',Math.floor(30000+Math.random()*50000),'GPay')} disabled={e6Busy}/>
    <Control label="Asynchronous write" on={()=>e6Write('async','',Math.floor(30000+Math.random()*50000),'PhonePe')} disabled={e6Busy}/>
    <Control label="Check consistency" on={()=>e6Action('CHECK_CONSISTENCY')} disabled={e6Busy}/>
    <Control label="Fail replica" on={()=>e6Action('FAIL_REPLICA')} disabled={e6Busy||replicaAlive===false}/>
    <Control label="Recover replica" on={()=>e6Action('RECOVER_REPLICA')} disabled={e6Busy||replicaAlive!==false}/>
    <Control label="Resynchronize" on={()=>e6Action('RESYNC')} disabled={e6Busy||replicaAlive===false}/>
   </div>
  </section>

  <section className="panel formPanel"><PanelTitle icon={<Banknote/>} title="Payment test panel" action="Real backend transaction"/>
   <div className="formGrid">
    <label>Payment ID<input placeholder="Auto-generated if blank" value={pid} onChange={e=>setPid(e.target.value)}/></label>
    <label>Amount (INR)<input value={amt} onChange={e=>setAmt(e.target.value)}/></label>
    <label>Payment method<select value={mth} onChange={e=>setMth(e.target.value)}>{methods.map(m=><option key={m}>{m}</option>)}</select></label>
   </div>
   <div className="btnRow">
    <button className="primaryBtn" onClick={()=>e6Write('sync',pid,Number(amt.replaceAll(',',''))||0,mth)} disabled={e6Busy}>Run Synchronous Replication <ArrowRight size={16}/></button>
    <button className="primaryBtn" onClick={()=>e6Write('async',pid,Number(amt.replaceAll(',',''))||0,mth)} disabled={e6Busy}>Run Asynchronous Replication <ArrowRight size={16}/></button>
   </div>
  </section>

  <section className="panel"><PanelTitle icon={<Activity/>} title="Live replication events" action="Experiment 6"/><Exp6Timeline events={e6Events}/></section>
 </>
}
function Exp6PaymentTable({side,primary,replica}:any){
 const mine=side==='primary'?(primary||[]):(replica||[]); const other=side==='primary'?(replica||[]):(primary||[]);
 const mineById:Record<string,any>={}; mine.forEach((r:any)=>mineById[r.payment_id]=r);
 const otherById:Record<string,any>={}; other.forEach((r:any)=>otherById[r.payment_id]=r);
 const ids=Array.from(new Set([...mine.map((r:any)=>r.payment_id),...other.map((r:any)=>r.payment_id)])).sort();
 return <div className="tableWrap"><table><thead><tr><th>Payment</th><th>Amount</th><th>Method</th><th>Status</th><th>Version</th><th>Match</th></tr></thead><tbody>
  {ids.length?ids.map(id=>{
    const p=mineById[id]; const o=otherById[id];
    if(!p) return <tr key={id}><td><b>{id}</b></td><td className="empty" colSpan={3}>—</td><td colSpan={2}><b className="badText">MISSING</b></td></tr>;
    const match=!!o&&Number(o.amount)===Number(p.amount)&&o.method===p.method&&o.status===p.status&&Number(o.version)===Number(p.version);
    return <tr key={id}><td><b>{id}</b></td><td>{money(p.amount)}</td><td>{p.method}</td><td><span className={'status '+(p.status==='SUCCESS'?'success':'pending')}>{p.status}</span></td><td>v{p.version}</td><td>{!o?<b className="badText">MISSING</b>:match?<b className="goodText">MATCH</b>:<b className="badText">VERSION_MISMATCH</b>}</td></tr>
  }):<tr><td colSpan={6} className="empty">No Experiment 6 payments yet.</td></tr>}
 </tbody></table></div>
}
function Exp6Timeline({events}:any){return <div className="logs">{events&&events.length?events.map((e:any)=><div className="log" key={e.event_id}><span>L{e.lamport_ts}</span><b>{e.event_type}</b><p>{e.message}</p><small>{String(e.created_at||'')}</small></div>):<div className="empty">No Experiment 6 events yet.</div>}</div>}
function Nodes({state,action,election}:any){const nodes=[state.primaryNode,state.backupNode].filter(Boolean);const terminals=election.terminals||[{id:'T1',priority:10,alive:true},{id:'T2',priority:25,alive:true},{id:'T3',priority:40,alive:true},{id:'T4',priority:15,alive:true},{id:'T5',priority:30,alive:true}];return <><section className="panel"><PanelTitle icon={<GitBranch/>} title="Bully election topology" action="Priority-based / 5 terminals"/><div className="bully">{terminals.map((t:any)=><div className={'terminal '+(t.id===election.coordinator?'active':'')+(t.alive?'':' downTerminal')} key={t.id}>{t.id}<small>priority {t.priority}</small><b>{t.alive?(t.id===election.coordinator?'COORDINATOR':'ACTIVE'):'FAILED'}</b></div>)}</div><p className="note">Academic election layer: T1=10, T2=25, T3=40, T4=15, T5=30. With all terminals alive, T3 coordinates; after T3 fails, T5 is the highest-priority active terminal. The payment gateway's NODE-1/NODE-2 primary-backup layer is kept separate from these five educational election terminals.</p><button className="primaryBtn" onClick={()=>action('START_ELECTION')}>Start Bully election <ArrowRight size={16}/></button></section><div className="metrics"><Metric label="Election coordinator" value={election.coordinator||'—'} sub={`election #${election.elections||0}`}/><Metric label="Gateway primary" value={state.activePrimary||'—'} sub="payment data plane"/><Metric label="NODE-1" value={state.primaryAlive?'UP':'DOWN'} sub="gateway priority 40"/><Metric label="NODE-2" value={state.backupAlive?'UP':'DOWN'} sub="gateway priority 30"/></div><section className="panel"><PanelTitle icon={<Server/>} title="Payment nodes"/><div className="nodeTable">{nodes.map((n:any)=><div className="nodeLine" key={n.id}><Server/><b>{n.id}</b><span>{n.role}</span><span>{n.priority} priority</span><strong className={n.status==='UP'?'goodText':'badText'}>{n.status}</strong></div>)}</div></section></>}
function Clock({clock,setClock,action}:any){return <><section className="panel"><PanelTitle icon={<Clock3/>} title="Berkeley + Lamport lab" action="Physical + logical time"/><p className="note">Berkeley reduces clock-offset differences; Lamport timestamps provide logical event ordering. They solve different problems and are recorded separately.</p><button className="primaryBtn" onClick={async()=>setClock(await api('/clock-sync'))}>Run Berkeley synchronization <ArrowRight size={16}/></button>{clock.nodes&&<div className="clockTable">{clock.nodes.map((n:any)=><div className="clockRow" key={n.node}><b>{n.node}</b><span>offset {n.offset>=0?'+':''}{n.offset}s</span><span>target {clock.targetOffset}s</span><strong>{clock.targetOffset-n.offset>=0?'+':''}{clock.targetOffset-n.offset}s</strong></div>)}</div>}</section><section className="panel"><PanelTitle icon={<Activity/>} title="Lamport lifecycle"/><div className="timeline"><div>Payment Created <b>L=1</b></div><ArrowRight/><div>Smart Routing <b>L=2</b></div><ArrowRight/><div>Payment Attempt <b>L=3</b></div><ArrowRight/><div>Payment Completed <b>L=4</b></div></div></section></>}
function Ledger({ledger,settlement,action}:any){return <><div className="metrics"><Metric label="Ledger verification" value={ledger.balanced?'BALANCED':'DRIFT'} sub={`${ledger.unbalancedTransactions||0} unbalanced transactions`}/><Metric label="Gross volume" value={money(settlement.gross)} sub="successful payments"/><Metric label="Fees" value={money(settlement.fees)} sub="demo 2% fee model"/><Metric label="Net settlement" value={money(settlement.net)} sub="ready for payout"/></div><section className="panel"><PanelTitle icon={<Database/>} title="Double-entry verification"/><div className="proofResult large">{ledger.balanced?<><CheckCircle2/> Every transaction balances: debit = credit</>:<><AlertTriangle/> Unbalanced transactions detected</>}</div></section><section className="panel"><PanelTitle icon={<Banknote/>} title="Settlement pipeline"/><div className="timeline"><div>Capture <b>SUCCESS</b></div><ArrowRight/><div>Settlement cycle <b>READY</b></div><ArrowRight/><div>Netting <b>2% fee</b></div><ArrowRight/><div>Payout <b>READY</b></div></div><button className="primaryBtn" onClick={()=>action('RESYNC')}>Re-run consistency check <RefreshCw size={16}/></button></section></>}
function Logs({events}:any){return <section className="panel"><PanelTitle icon={<Activity/>} title="Distributed system event log" action="Lamport ordered"/><div className="logs">{events.length?events.map((e:any)=><div className="log" key={e.event_id}><span>L{e.lamport_ts}</span><b>{e.event_type}</b><p>{e.message}</p><small>{String(e.created_at||'')}</small></div>):<div className="empty">No events yet.</div>}</div></section>}

createRoot(document.getElementById('root')!).render(<App/>);
