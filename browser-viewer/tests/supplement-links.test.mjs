import {test} from 'node:test';
import assert from 'node:assert/strict';
import {SupplementLinks,contentKey} from '../dist/chromium/supplement-links.js';
test('identical child source and supplement bytes establish one owner without Message-ID',()=>{
 const links=new SupplementLinks();links.source('source',['email']);assert.equal(links.child(['source'],['headers']),true);
 assert.deepEqual(links.owners(['headers']),['email']);assert.equal(links.owners(['different']),undefined);
 assert.notEqual(contentKey(1,'a'),contentKey(2,'a'));
});
test('duplicate outer sources, conflicting child sources and conflicting header owners stay unresolved',()=>{
 const links=new SupplementLinks();links.source('shared',['a']);links.source('shared',['b']);assert.equal(links.child(['shared'],['header']),false);
 links.source('one',['a']);links.source('two',['b']);assert.equal(links.child(['one','two'],['header']),false);
 links.child(['one'],['header']);links.child(['two'],['header']);assert.equal(links.owners(['header']),undefined);
});
test('missing identities, alternative streams and partial scans cannot establish uniqueness',()=>{
 const links=new SupplementLinks();links.source('source',['owner']);assert.equal(links.child([],['h']),false);assert.equal(links.child(['unknown'],['h']),false);
 links.child(['source'],['h']);assert.equal(links.owners(['h','unknown']),undefined);assert.equal(links.owners([]),undefined);
 links.incomplete();assert.equal(links.owners(['h']),undefined);
});

test('an unidentified duplicate source prevents a false unique owner',()=>{
 const links=new SupplementLinks();links.source('same',['owner']);links.source('same',[]);
 assert.equal(links.child(['same'],['headers']),false);assert.equal(links.owners(['headers']),undefined);
});
