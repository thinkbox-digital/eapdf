"""RFC6068 URI encoding must not change the compared mailbox."""
import json,sys
from pathlib import Path
from xml.etree import ElementTree as ET
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT));import validate_eapdf as v
cases=[('plus','mailto:foo%2Bbar@example.org','mailto:foo+bar@example.org'),('quoted','mailto:%22not..atom%22@example.org','mailto:"not..atom"@example.org'),('percent-once','mailto:foo%252Bbar@example.org','mailto:foo%2Bbar@example.org'),('local-case','mailto:Case@example.org','mailto:case@example.org')]
rows=[]
for name,uri,source in cases:
 root=ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:m="{v.NS_PDFMAILMETA}" xmlns:f="{v.NS_FOAF}"><rdf:Description><m:To><rdf:Seq><rdf:li rdf:parseType="Resource"><f:mbox/></rdf:li></rdf:Seq></m:To></rdf:Description></rdf:RDF>')
 root.find('.//'+v.qn(v.NS_FOAF,'mbox')).set(v.qn(v.NS_RDF,'resource'),uri)
 report=v.Report(name+'.pdf');v.compare_agent_property(root,report,'To',[{'name':'','mbox':source}],'MESSAGE_TO_XMP','MESSAGE_TO_FOAF')
 rows.append(dict(case=name,uri=uri,source=source,items=report.items));print(name,[(i['code'],i['level']) for i in report.items],flush=True)
(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
