/** Prefix sums of measured row corrections, with logarithmic updates and lookups. */
export class RowHeights {
  private sums:Float64Array;
  private measured=new Map<number,number>();
  constructor(private count:number,private estimate:number){this.sums=new Float64Array(count+1);}
  offset(index:number):number{
    let result=index*this.estimate;
    for(let i=index;i>0;i-=i&-i)result+=this.sums[i]!;
    return result;
  }
  set(index:number,height:number):void{
    const delta=height-(this.measured.get(index)??this.estimate);this.measured.set(index,height);
    for(let i=index+1;i<=this.count;i+=i&-i)this.sums[i]!+=delta;
  }
}
