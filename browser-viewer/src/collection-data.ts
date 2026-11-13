export type Scalar=string|number|boolean|null;
export type Values=readonly (readonly [string,Scalar,Scalar])[];
export type PortfolioField={key:string;label:string;subtype:string;order:number|null;visible:boolean};
export type PortfolioEntry={key:string;keyHex:string;filePath:string;name:Scalar;description:Scalar;created:Scalar;modified:Scalar;size:Scalar;compressedSize:Scalar;values:Values};
export type PortfolioFolder={thumbnail?:boolean;path:string;parent:string|null;number:Scalar;name:Scalar;description:Scalar;created:Scalar;modified:Scalar;values:Values};
export type PortfolioData={initial:string|null;initialHex:string|null;view:string;layouts:Scalar[];fields:PortfolioField[];sort:Scalar[];ascending:Scalar[];ascendingAll?:boolean;folders:PortfolioFolder[];entries:PortfolioEntry[];split:{direction:Scalar;position:Scalar}|null;colors:[string,Scalar[]][];notices:string[]};
