#!/usr/bin/env python3
# Reemplaza el asset corrupto de una release por el APK local verificado.
# Metodo canonico: raw octet-stream (SIN multipart -> imposible contaminar boundaries).
# Uso: python fix_asset.py <tag> <ruta_apk_local> <nombre_asset>
import sys, json, os, subprocess, urllib.request, urllib.parse, hashlib

TAG, APK, ASSET_NAME = sys.argv[1], sys.argv[2], sys.argv[3]

def gh(url, method="GET", data=None, headers=None, token=None):
    h = {"Accept": "application/vnd.github+json", "User-Agent": "euge-fix"}
    if token: h["Authorization"] = "Bearer " + token
    if headers: h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    return urllib.request.urlopen(req, timeout=300)

# token sin echo
cred = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n",
                      capture_output=True, text=True)
tok = dict(l.split("=", 1) for l in cred.stdout.splitlines() if "=" in l)["password"]

rel = json.load(gh(f"https://api.github.com/repos/Chande9/la-musica/releases/tags/{TAG}", token=tok))
rid = rel["id"]
bad = [a for a in rel["assets"] if a["name"] == ASSET_NAME]
print(f"release {TAG} id={rid} | asset corrupto: {[ (a['id'], a['size']) for a in bad ]}")

data = open(APK, "rb").read()
sha = hashlib.sha256(data).hexdigest()
print(f"local: {len(data)} bytes sha256={sha[:16]}")

for a in bad:
    gh(f"https://api.github.com/repos/Chande9/la-musica/releases/assets/{a['id']}",
       method="DELETE", token=tok)
    print(f"DELETE asset {a['id']} OK")

q = urllib.parse.urlencode({"name": ASSET_NAME, "label": ASSET_NAME})
up = gh(f"https://uploads.github.com/repos/Chande9/la-musica/releases/{rid}/assets?{q}",
        method="POST", data=data,
        headers={"Content-Type": "application/octet-stream", "Content-Length": str(len(data))},
        token=tok)
res = json.load(up)
print(f"UPLOAD: state={res.get('state')} size={res.get('size')} digest={res.get('digest')}")

ok_size = res.get("size") == len(data)
ok_sha = res.get("digest") == "sha256:" + sha
print(f"VERIFY: size_exacto={ok_size} sha256_match={ok_sha}")
sys.exit(0 if (ok_size and ok_sha and res.get("state") == "uploaded" and not bad == []) else 1)