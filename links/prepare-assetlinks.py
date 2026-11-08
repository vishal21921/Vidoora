import argparse,json,pathlib,re
p=argparse.ArgumentParser();p.add_argument("play_signing_sha256");a=p.parse_args()
if not re.fullmatch(r"(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}",a.play_signing_sha256):p.error("Use the public Play APP SIGNING SHA-256 fingerprint, not the upload key")
target=pathlib.Path(__file__).parent/"public/.well-known/assetlinks.json";target.parent.mkdir(exist_ok=True)
target.write_text(json.dumps([{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"com.vidoora.app","sha256_cert_fingerprints":[a.play_signing_sha256.upper()]}}],indent=2))
print("Public assetlinks association prepared.")
