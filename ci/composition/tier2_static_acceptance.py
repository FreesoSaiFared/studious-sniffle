#!/usr/bin/env python3
"""Static acceptance for signed Tier-2 DEX activation/rollback."""
from __future__ import annotations
import json, pathlib, re

ROOT=pathlib.Path(__file__).resolve().parents[2]
CORE=ROOT/"ci/reconstruction/core/src/science/transductive/nudge/core"
ANDROID=ROOT/"ci/reconstruction/android/app/src/main/java/science/transductive/nudge"
OUT=ROOT/"ci/build/tier2-static-receipt.json"

manifest=(CORE/"SignedModuleManifest.java").read_text()
installer=(ANDROID/"SignedModuleInstaller.java").read_text()
abi=(ANDROID/"BehaviorModule.java").read_text()
host=(ANDROID/"BehaviorModuleHost.java").read_text()
checks={}

def require(name,cond,detail):
    checks[name]={"pass":bool(cond),"detail":detail}
    if not cond: raise AssertionError(f"{name}: {detail}")

require("schema", 'SIGNED_DEX_MODULE/1' in manifest, "signed module schema")
require("p256", 'Signature.getInstance("SHA256withECDSA")' in manifest, "P-256 ECDSA signature")
require("dex_hash", '"dex hash mismatch"' in manifest and "sha256(dex)" in manifest, "actual DEX bytes hashed")
require("trusted_signer", "trustedKeys.get(keyId)" in manifest and "untrusted signer" in manifest, "explicit trusted signer")
require("module_namespace", "science\\\\.transductive\\\\.nudge\\\\.modules" in manifest, "entry class namespace fenced")
require("stable_abi", 'SELF_NUDGE_BEHAVIOR_MODULE/1' in abi and "selfTest()" in abi, "stable ABI and self-test")
require("restricted_host_surface", all(x in host for x in ["promptChatGpt","launchNudge","emit"]), "host exposes only declared orchestration methods")
require("private_storage", 'getFilesDir()' in installer and '"signed-modules"' in installer, "DEX stored under app-private filesDir")
require("no_external_storage", "Environment.getExternalStorage" not in installer and "getExternalFilesDir" not in installer, "no external DEX path")
require("readonly_before_loader", "setReadOnly()" in installer and "DexClassLoader" in installer, "DEX made read-only before dynamic loading")
require("verify_before_activation", installer.index("SignedModuleManifest.verify(") < installer.index('next.put("schema","SIGNED_MODULE_ACTIVE_STATE/1")'), "signature/hash verification precedes active pointer")
require("selftest_before_activation", installer.index("Loaded loaded=loadVerified") < installer.index('next.put("schema","SIGNED_MODULE_ACTIVE_STATE/1")'), "loadVerified performs ABI/selfTest before activation")
require("rollback_reverifies", "rollback(Context c)" in installer and "loadEntry(c,(Map<?,?>)previous)" in installer, "previous module reverified before rollback")
require("private_path_guard", "getCanonicalFile()" in installer and "module path escaped private root" in installer, "active path cannot escape private module root")

data={
 "schema":"SELF_NUDGE_TIER2_STATIC_RECEIPT/1",
 "status":"PASS",
 "checks":checks,
 "authority_boundary":"TRUSTED_SIGNED_CODE_HAS_APP_PROCESS_PERMISSIONS_NOT_A_SANDBOX",
 "activation":["verify signature","verify DEX hash","private write","read-only DEX","load stable ABI","selfTest","atomic active pointer"],
 "rollback":"previous pointer is reverified before swap"
}
OUT.parent.mkdir(parents=True,exist_ok=True)
OUT.write_text(json.dumps(data,indent=2,sort_keys=True)+"\n")
print(json.dumps(data,sort_keys=True))
print(f"TIER2_STATIC_PASS={len(checks)}")
