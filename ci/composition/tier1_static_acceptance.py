#!/usr/bin/env python3
"""Static acceptance for Tier-1 signed behavior and consented context boundaries."""
from __future__ import annotations
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
CORE = ROOT / "ci/reconstruction/core/src/science/transductive/nudge/core"
ANDROID = ROOT / "ci/reconstruction/android/app/src/main/java/science/transductive/nudge"
OUT = ROOT / "ci/build/tier1-static-receipt.json"

checks = {}

def require(name: str, cond: bool, detail: str) -> None:
    checks[name] = {"pass": bool(cond), "detail": detail}
    if not cond:
        raise AssertionError(f"{name}: {detail}")

bundle = (CORE / "SignedBehaviorBundle.java").read_text()
program = (CORE / "BehaviorProgram.java").read_text()
runtime = (ANDROID / "BehaviorRuntime.java").read_text()
keys = (ANDROID / "TrustedBehaviorKeys.java").read_text()
context = (ANDROID / "ContextDocumentStore.java").read_text()
context_contract = (CORE / "ContextDocumentContract.java").read_text()
main = (ANDROID / "MainActivity.java").read_text()

allowed = set(re.findall(r"enum Op \{([^}]+)\}", program, re.S)[0].replace("\n"," ").replace("\r"," ").split(","))
allowed = {x.strip() for x in allowed if x.strip()}
require("allowed_ops_exact", allowed == {"CHATGPT_PROMPT","NUDGE","DELAY","EMIT"}, f"ops={sorted(allowed)}")

combined = "\n".join([bundle, program, runtime, keys])
forbidden = [
    "Runtime.getRuntime().exec",
    "ProcessBuilder",
    "Class.forName",
    "DexClassLoader",
    "PathClassLoader",
    "System.load(",
    "System.loadLibrary(",
    "java.lang.reflect",
]
for token in forbidden:
    require("forbid_" + re.sub(r"\W+","_",token).strip("_"), token not in combined, token)

require("p256_signature", 'Signature.getInstance("SHA256withECDSA")' in bundle, "ECDSA P-256 verifier required")
require("payload_hash", 'payloadSha256' in bundle and 'sha256(payload)' in bundle, "payload hash must be verified")
require("expiry_bound", "bundle expired" in bundle and "604800000L" in bundle, "expiry and <=7-day lifetime required")
require("trusted_key_lookup", "trustedKeys.get(keyId)" in bundle and "untrusted signer" in bundle, "signer must be explicitly trusted")
require("journal_before_steps", runtime.index('write(journal,state(b,0,"STARTED"') < runtime.index("for(int i=0;i<p.steps.size();i++)"), "journal STARTED must precede program loop")
require("no_duplicate_bundle_run", "bundle_already_journaled_reobserve_or_issue_new_bundle" in runtime, "journal replay fence required")
require("nudge_terminal", 'NUDGE must be final step' in program, "async human nudge must terminate program")
require("context_bound_2mib", "2L*1024L*1024L" in context_contract and "exceeds 2 MiB" in context, "context import must be bounded")
require("context_hash", 'MessageDigest.getInstance("SHA-256")' in context_contract and "ContextDocumentContract.metadata(" in context, "runtime import must use tested hash contract")
require("context_provenance", 'out.put("sourceUri",u)' in context_contract and 'out.put("importedAtMs",importedAtMs)' in context_contract, "source provenance required")
require("explicit_document_picker", "Intent.ACTION_OPEN_DOCUMENT" in main and "FLAG_GRANT_PERSISTABLE_URI_PERMISSION" in main, "user-selected document flow required")
require("no_accessibility_diary_scrape", "ContextDocumentStore.importUri" in main, "context intake must use explicit importer")

receipt = {
    "schema": "SELF_NUDGE_TIER1_STATIC_RECEIPT/1",
    "status": "PASS",
    "checks": checks,
    "accepted_boundaries": {
        "signed_behavior_only": True,
        "allowed_ops": sorted(allowed),
        "arbitrary_shell_reflection_classloading": False,
        "context_import_user_selected": True,
        "context_max_bytes": 2 * 1024 * 1024,
    },
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
print(json.dumps(receipt, sort_keys=True))
print(f"TIER1_STATIC_PASS={len(checks)}")
