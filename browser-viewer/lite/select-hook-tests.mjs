// Keep the actual shared generic API regressions; omit archive-optimization tests.
import {parse} from 'acorn';
export function selectHookTests(text,kind) {
 const source=parse(text,{ecmaVersion:'latest',sourceType:'module'});
 const selected=kind==='core'? /^(preserves nonzero|ordinary\/missing|missing parents|nested indirect|broken child|non-page|complex DPM|MissingDataException)/ : /^(discovers catalog|decompression limit|zero-byte|object and collection cycles|normal decoder|ordinary asset|embedded actions)/;
 let previous=0;const result=[];
 for(const statement of source.body) {
  const expression=statement.type==='ExpressionStatement'?statement.expression:null;
  const isTest=expression?.type==='CallExpression'&&expression.callee.type==='Identifier'&&expression.callee.name==='test';
  if(!isTest||selected.test(expression.arguments[0]?.value??''))result.push(text.slice(previous,statement.end));
  previous=statement.end;
 }
 return '// Generated from shared hook regressions; update through the Lite exporter.\n'+result.join('');
}
