export const SEARCH_FIELDS=['Subject','From','Raw-From','To','Raw-To','Cc','Raw-Cc','Bcc','Raw-Bcc','Sent'] as const;
export const metadataSearchText=(data:Record<string,string>)=>SEARCH_FIELDS.map(k=>data[k]??'').join(' ').toLowerCase();
