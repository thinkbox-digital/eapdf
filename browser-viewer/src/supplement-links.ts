// No filenames, sequence numbers, subjects or Message-ID guesses are used here.
export class SupplementLinks {
  private readonly sources=new Map<string,Set<string>>();
  private readonly unidentifiedSources=new Set<string>();
  private readonly candidates=new Map<string,Set<string>>();
  private complete=true;
  source(content:string,owners:readonly string[]):void {
    if(owners.length!==1)this.unidentifiedSources.add(content);
    const ids=this.sources.get(content)??new Set<string>();owners.forEach(id=>ids.add(id));this.sources.set(content,ids);
  }
  child(sources:readonly string[],supplements:readonly string[]):boolean {
    const owners=new Set<string>();
    if(!sources.length)return false;
    for(const content of sources){const ids=this.sources.get(content);if(ids?.size!==1||this.unidentifiedSources.has(content))return false;ids.forEach(id=>owners.add(id));}
    if(owners.size!==1)return false;
    for(const content of supplements){const ids=this.candidates.get(content)??new Set<string>();owners.forEach(id=>ids.add(id));this.candidates.set(content,ids);}
    return true;
  }
  incomplete():void {this.complete=false;}
  owners(contents:readonly string[]):readonly string[]|undefined {
    if(!this.complete||!contents.length)return undefined;
    const owners=new Set<string>();
    for(const content of contents){const ids=this.candidates.get(content);if(ids?.size!==1)return undefined;ids.forEach(id=>owners.add(id));}
    return owners.size===1?[...owners]:undefined;
  }
}
export const contentKey=(size:number,sha256:string):string=>`${size}:${sha256}`;
