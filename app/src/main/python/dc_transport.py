"""DeltaChat transport for the capture companion (DRAFT - gated, not wired).

Runs the app-side half of the queue/result exchange over DeltaChat:

- The app holds its OWN chatmail profile (keys only in the app-private
  files dir, android:allowBackup="false").
- Account creation is an EXPLICIT owner action from the setup screen,
  never first-launch automatic. Provisioning follows the itdapk bot
  pattern: add_transport_from_qr("dcaccount:nine.testrun.org") mints a
  relay identity with no signup, email, phone or password; re-onboarding
  with an existing profile is refused to avoid duplicate identities.
- The app serves exactly ONE trusted contact: the peer address the owner
  enters in the setup screen (runtime config, never in source). Messages
  from any other address are ignored.

Queue IN: the peer sends a message whose body (or capture-queue.json
attachment) is a capture-queue JSON document (see QueueProtocol.kt).
Results OUT: one message per page with the result JSON + PNG attachments,
then a run summary.

This module is intentionally free of any knowledge about WHO the peer is
or how it processes results - that is the peer's own business.

STATUS: scaffold. Not started by the app until the transport pairing
workflow is approved and enabled.
"""

import json
import logging
import os
import threading
import time

log = logging.getLogger("capture.dc_transport")

STATE_FILE = "dc_transport_state.json"


def _state_path(files_dir):
    return os.path.join(files_dir, STATE_FILE)


def load_state(files_dir):
    try:
        with open(_state_path(files_dir)) as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(files_dir, **kv):
    st = load_state(files_dir)
    st.update(kv)
    tmp = _state_path(files_dir) + ".tmp"
    with open(tmp, "w") as f:
        json.dump(st, f)
    os.replace(tmp, _state_path(files_dir))


def _account_addr(acct):
    # Core deprecated the "addr" config key (multi-transport): read the
    # transport list first, fall back to get_config("addr").
    try:
        for t in acct.list_transports() or []:
            addr = (t or {}).get("addr")
            if addr:
                return addr
    except Exception:
        pass
    return acct.get_config("addr")


def provision(native_lib_dir, files_dir, relay):
    """One-time, EXPLICIT-owner-action account creation. Network-using.

    Refuses to run when any profile already exists in an unconfigured
    state (re-onboarding would mint a new relay identity).
    """
    from deltachat_rpc_client import DeltaChat, Rpc

    rpc = Rpc(accounts_dir=files_dir, native_lib_dir=native_lib_dir)
    rpc.start()
    try:
        dc = DeltaChat(rpc)
        accounts = dc.get_all_accounts()
        configured = [a for a in accounts if a.is_configured()]
        if configured:
            addr = _account_addr(configured[0])
            if not addr:
                raise RuntimeError("existing account has no readable transport address")
            save_state(files_dir, provisioned=True, self_addr=addr)
            return addr
        if accounts:
            raise RuntimeError(
                "found %d existing profile(s) but none configured; refusing "
                "to re-onboard (would mint a new relay identity)" % len(accounts))
        acct = dc.add_account()
        acct.add_transport_from_qr("dcaccount:%s" % relay)
        if not acct.is_configured():
            raise RuntimeError("relay did not configure the account")
        addr = _account_addr(acct)
        save_state(files_dir, provisioned=True, self_addr=addr)
        log.info("capture transport account created")
        return addr
    finally:
        rpc.close()


def serve_loop(native_lib_dir, files_dir, peer_addr, on_queue, send_result, poll_secs=5):
    """Long-running loop: watch for the peer's latest queue, hand results back.

    on_queue(pages) is called when a new capture-queue message arrives from
    the trusted peer; send_result(text, attachment_paths) ships one result.
    Only messages from peer_addr are considered. DRAFT: message-selection
    details (which chat, dedupe of already-served queues) land with the
    pairing workflow approval.
    """
    raise NotImplementedError("transport loop lands with the approved pairing workflow")
