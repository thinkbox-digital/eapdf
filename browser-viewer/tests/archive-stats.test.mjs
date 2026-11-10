import test from 'node:test';import assert from 'node:assert/strict';
import {archiveStats} from '../dist/chromium/archive-stats.js';
function doc(profile,description){return {run:p=>p,pdf:{getMetadata:async()=>({metadata:{get:()=>description}})},inspection:{identification:{declaredProfile:{value:profile}},messages:[{metadataRecordId:'one'},{metadataRecordId:'two'}],metadataRecords:[{id:'one',fields:{NumberAttachments:{state:'present',value:'2'}}},{id:'two',fields:{NumberAttachments:{state:'present',value:'1'}}}],collection:{value:null}}};}
test('all archive profiles expose totals without deduplicating messages',async()=>{for(const p of ['m','mi','c','ci'])assert.equal(await archiveStats(doc(p,'EA-PDF email archive; messages=2; folders=4; attachments=3; conformance='+p)),'2 messages · 4 folders · 3 attachments');});
test('missing old aggregate folder metadata stays unknown',async()=>assert.equal(await archiveStats(doc('m','EA-PDF email archive; messages=2; conformance=m')),'2 messages · Unknown folders · 3 attachments'));
test('single messages have no archive totals',async()=>assert.equal(await archiveStats(doc('s','')),undefined));
