// MD5 is retained only for specification checksum comparison, not authentication.
export function md5(bytes: Uint8Array): string {
  const length = Math.ceil((bytes.length + 9) / 64) * 64;
  const padded = new Uint8Array(length); padded.set(bytes); padded[bytes.length] = 128;
  const view = new DataView(padded.buffer);
  view.setUint32(length - 8, (bytes.length * 8) >>> 0, true);
  view.setUint32(length - 4, Math.floor(bytes.length / 536870912), true);
  const shifts = [7,12,17,22,5,9,14,20,4,11,16,23,6,10,15,21];
  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (let offset = 0; offset < length; offset += 64) {
    let a = a0, b = b0, c = c0, d = d0;
    for (let i = 0; i < 64; i++) {
      let f: number, g: number;
      if (i < 16) { f = (b & c) | (~b & d); g = i; }
      else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
      else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
      else { f = c ^ (b | ~d); g = (7 * i) % 16; }
      const sum = (a + f + Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) + view.getUint32(offset + g * 4, true)) | 0;
      const s = shifts[Math.floor(i / 16) * 4 + i % 4]!;
      [a, b, c, d] = [d, (b + ((sum << s) | (sum >>> (32 - s)))) | 0, b, c];
    }
    a0 = (a0 + a) | 0; b0 = (b0 + b) | 0; c0 = (c0 + c) | 0; d0 = (d0 + d) | 0;
  }
  const out = new DataView(new ArrayBuffer(16));
  [a0,b0,c0,d0].forEach((v,i) => out.setInt32(i * 4,v,true));
  return [...new Uint8Array(out.buffer)].map(x => x.toString(16).padStart(2,'0')).join('');
}
export async function sha256(bytes: Uint8Array): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes.slice().buffer))].map(v => v.toString(16).padStart(2, '0')).join('');
}

// Large, user-requested extraction checks yield between chunks and use only a
// final 128-byte padding buffer rather than duplicating the whole attachment.
export async function md5Responsive(bytes:Uint8Array,check:()=>void=()=>{}):Promise<string>{
  const shifts=[7,12,17,22,5,9,14,20,4,11,16,23,6,10,15,21];
  const constants=Int32Array.from({length:64},(_,i)=>Math.floor(Math.abs(Math.sin(i+1))*4294967296));
  let a0=0x67452301,b0=0xefcdab89,c0=0x98badcfe,d0=0x10325476;
  const block=(view:DataView,offset:number)=>{
    let a=a0,b=b0,c=c0,d=d0;
    for(let i=0;i<64;i++){
      let f:number,g:number;
      if(i<16){f=(b&c)|(~b&d);g=i;}else if(i<32){f=(d&b)|(~d&c);g=(5*i+1)%16;}else if(i<48){f=b^c^d;g=(3*i+5)%16;}else{f=c^(b|~d);g=(7*i)%16;}
      const sum=(a+f+constants[i]!+view.getUint32(offset+g*4,true))|0,s=shifts[Math.floor(i/16)*4+i%4]!;
      [a,b,c,d]=[d,(b+((sum<<s)|(sum>>>(32-s))))|0,b,c];
    }
    a0=(a0+a)|0;b0=(b0+b)|0;c0=(c0+c)|0;d0=(d0+d)|0;
  };
  const full=bytes.length-bytes.length%64,view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
  for(let offset=0;offset<full;offset+=64){if(offset%(1024*1024)===0){check();await new Promise<void>(resolve=>setTimeout(resolve,0));check();}block(view,offset);}
  const tail=new Uint8Array(bytes.length-full<56?64:128);tail.set(bytes.subarray(full));tail[bytes.length-full]=128;
  const last=new DataView(tail.buffer);last.setUint32(tail.length-8,(bytes.length*8)>>>0,true);last.setUint32(tail.length-4,Math.floor(bytes.length/536870912),true);
  for(let i=0;i<tail.length;i+=64)block(last,i);check();
  const output=new DataView(new ArrayBuffer(16));[a0,b0,c0,d0].forEach((v,i)=>output.setInt32(i*4,v,true));
  return [...new Uint8Array(output.buffer)].map(n=>n.toString(16).padStart(2,'0')).join('');
}
