export function requestPassword(incorrect:boolean,signal:AbortSignal):Promise<string>{
  return new Promise((resolve,reject)=>{
    if(signal.aborted){reject(new Error('Password entry cancelled.'));return;}
    const dialog=document.createElement('dialog'),form=document.createElement('form'),title=document.createElement('h2'),input=document.createElement('input'),submit=document.createElement('button'),cancel=document.createElement('button');
    title.id='pdf-password-title';title.textContent=incorrect?'Incorrect password. Try again.':'This PDF requires a password';dialog.setAttribute('aria-labelledby',title.id);
    input.type='password';input.autocomplete='off';input.setAttribute('aria-label','PDF password');submit.textContent='Open PDF';submit.type='submit';cancel.textContent='Cancel';cancel.type='button';
    const finish=(value?:string)=>{signal.removeEventListener('abort',abort);input.value='';dialog.close();dialog.remove();if(value===undefined)reject(new Error('Password entry cancelled.'));else resolve(value);};
    const abort=()=>finish();signal.addEventListener('abort',abort,{once:true});
    cancel.addEventListener('click',()=>finish());dialog.addEventListener('cancel',e=>{e.preventDefault();finish();});form.addEventListener('submit',e=>{e.preventDefault();finish(input.value);});
    form.append(title,input,submit,cancel);dialog.append(form);document.body.append(dialog);dialog.showModal();input.focus();
  });
}
