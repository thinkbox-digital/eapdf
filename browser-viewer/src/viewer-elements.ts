// Shared DOM constructors; panels retain their own state and event ownership.
export function element<T extends HTMLElement>(id:string):T {const node=document.getElementById(id);if(!node)throw new Error('Missing element: '+id);return node as T;}
export function text<K extends keyof HTMLElementTagNameMap>(tag:K,value:string){const node=document.createElement(tag);node.textContent=value;return node;}
export function button(label:string,action:()=>void){const node=text('button',label);node.type='button';node.addEventListener('click',action);return node;}

export function visiblePaneControls(pane:HTMLElement):HTMLElement[]{return [...pane.querySelectorAll<HTMLElement>('button,summary,a[href]')].filter(node=>node.getClientRects().length&&!node.closest('[hidden],[inert]')&&!node.matches(':disabled'));}
