// Keep the actual shared generic API regressions; omit archive-optimization tests.
import ts from '../node_modules/typescript/lib/typescript.js';
export function selectHookTests(text,kind) {
 const source=ts.createSourceFile('tests.mjs',text,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
 const selected=kind==='core'? /^(preserves nonzero|ordinary\/missing|missing parents|nested indirect|broken child|non-page|complex DPM|MissingDataException)/ : /^(discovers catalog|decompression limit|zero-byte|object and collection cycles|normal decoder|ordinary asset|embedded actions)/;
 return '// Generated from shared hook regressions; update through the Lite exporter.\n'+source.statements.filter(statement=>{
  if(ts.isExpressionStatement(statement)&&ts.isCallExpression(statement.expression)&&statement.expression.expression.getText(source)==='test')return selected.test(statement.expression.arguments[0]?.text??'');
  return true;
 }).map(statement=>statement.getFullText(source)).join('');
}
