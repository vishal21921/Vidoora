"""Run privately as root on the Vidoora VM. Never paste an app password into chat."""
import getpass,json,os,pathlib,re,shutil
if os.geteuid()!=0: raise SystemExit("Run this helper with sudo on the VM")
username=input("Sender Gmail address: ").strip().lower()
if not re.fullmatch(r"[a-z0-9._%+-]+@gmail\.com",username):raise SystemExit("Enter the sender Gmail address")
password="".join(getpass.getpass("Gmail APP password (hidden, not the login password): ").split())
if not re.fullmatch(r"[a-zA-Z]{16}",password):raise SystemExit("Expected the 16-letter Gmail app password")
p=pathlib.Path("/etc/vidoora/api.env")
backup=p.with_name("api.env.before-recovery-email")
if not backup.exists():shutil.copyfile(p,backup);backup.chmod(0o600)
lines=[line for line in p.read_text().splitlines() if not line.startswith(("PASSWORD_RESET_GMAIL_USER=","PASSWORD_RESET_GMAIL_APP_PASSWORD=","PASSWORD_RESET_RESEND_KEY=","PASSWORD_RESET_FROM="))]
lines += ["PASSWORD_RESET_GMAIL_USER="+json.dumps(username),"PASSWORD_RESET_GMAIL_APP_PASSWORD="+json.dumps(password)]
temp=p.with_name("api.env.next")
fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,"w") as f:f.write("\n".join(lines)+"\n")
os.replace(temp,p);p.chmod(0o600)
password=None
print("Recovery sender saved privately. Restart vidoora-api and test recovery with your own test account.")
