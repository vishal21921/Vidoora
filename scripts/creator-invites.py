"""Run privately on the VM as root. No code/password is stored in shell history."""
import argparse, datetime, getpass, hashlib, os, pathlib, secrets, subprocess, urllib.parse, shlex
parser=argparse.ArgumentParser()
parser.add_argument("action",choices=["create","revoke-code","revoke-user"])
parser.add_argument("--user-id",type=int)
args=parser.parse_args()
settings={}
for line in pathlib.Path("/etc/vidoora/api.env").read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        key,value=line.split("=",1);settings[key]=shlex.split(value)[0] if value else ""
url=urllib.parse.urlsplit(settings["DATABASE_URL"].removeprefix("jdbc:"))
env=dict(os.environ,PGHOST=url.hostname,PGPORT=str(url.port or 5432),PGDATABASE=url.path.lstrip("/"),PGUSER=settings["DB_USERNAME"],PGPASSWORD=settings["DB_PASSWORD"])
if args.action=="create":
    code=secrets.token_urlsafe(24)
    hashed=hashlib.sha256(code.encode()).hexdigest()
    sql=f"INSERT INTO creator_invites(token_hash,expires_at) VALUES ('{hashed}',NOW()+INTERVAL '7 days');"
elif args.action=="revoke-code":
    code=getpass.getpass("Unused invitation code (hidden): ")
    hashed=hashlib.sha256(code.strip().encode()).hexdigest()
    sql=f"UPDATE creator_invites SET revoked=TRUE WHERE token_hash='{hashed}' AND redeemed_by IS NULL;"
else:
    if args.user_id is None or args.user_id < 10_000_001:
        parser.error("--user-id must be the public ID shown in the app")
    account_number = args.user_id - 10_000_000
    sql=f"UPDATE users SET role='USER',updated_at=NOW() WHERE public_number={account_number} AND role='CREATOR';"
result=subprocess.run(["psql","-X","-w","-v","ON_ERROR_STOP=1","-q"],input=sql,text=True,env=env,capture_output=True)
if result.returncode: raise SystemExit("Operation failed. Check the private database configuration; no code issued.")
if args.action=="create": print("Single-use creator code (expires in 7 days):",code)
else: print("Operation completed. Existing video data was preserved.")
