"""Prepare fictional mail for the documentation screenshots; never uses a mailbox."""
from email.message import EmailMessage
from pathlib import Path
import argparse
import hashlib
import json

ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work',type=Path,default=Path('/tmp/eapdf-user-manual'))
    args=parser.parse_args();args.work.mkdir(parents=True,exist_ok=True,mode=0o700)
    directory=args.work/'emails';directory.mkdir(exist_ok=True,mode=0o700)
    messages=[('October planning meeting','Hello Alex,\n\nOur planning meeting is on Thursday at 10:00.\nPlease read the attached agenda before the meeting.\n\nThank you,\nMorgan'),('Re: October planning meeting','Hello Morgan,\n\nThursday at 10:00 works for me. I will bring the updated schedule.\n\nBest wishes,\nAlex')]
    for index,(subject,body) in enumerate(messages):
        message=EmailMessage()
        message['From']='Morgan Reed <morgan@example.org>' if index==0 else 'Alex Lee <alex@example.org>'
        message['To']='Alex Lee <alex@example.org>' if index==0 else 'Morgan Reed <morgan@example.org>'
        message['Subject']=subject
        message['Date']='Wed, 28 Oct 2026 09:00:00 -0400' if index==0 else 'Wed, 28 Oct 2026 09:30:00 -0400'
        message['Message-ID']=f'<manual-{index+1}@example.org>'
        message.set_content(body)
        if index==0:
            # Explicit boundary makes future recreation independent of email's random boundary.
            message.add_attachment(b'Planning meeting agenda\n1. Review the schedule\n2. Assign next steps\n',maintype='text',subtype='plain',filename='agenda.txt')
            message.set_boundary('eapdf-manual-fictional-agenda-20261031')
        (directory/f'20261028_{index+1}.eml').write_bytes(message.as_bytes())
    config=json.loads((ROOT/'config/eapdf_config.json').read_text())
    config['creator']='Example Archives - manual demonstration'
    config['signing']['enabled']=False
    config['premis']['enabled']=False
    (args.work/'config.json').write_text(json.dumps(config,indent=2)+'\n')
    print('Fictional documentation inputs:',directory)

if __name__=='__main__':main()
