"""DeltaChat transport for bidik (gated; account creation is explicit setup only).

Runs the app-side half of the queue/result exchange over DeltaChat:

- The app holds its OWN chatmail profile (keys only in the app-private
  files dir, android:allowBackup="false").
- Account creation happens ONLY from an explicit setup action,
  never on first launch: add_transport_from_qr("dcaccount:<relay>") mints
  a relay identity with no signup, email, phone or password. Re-running
  with an existing profile is refused to avoid duplicate relay identities.
- The app serves exactly ONE trusted contact: the peer address the user
  enters at pairing (runtime config, never in source). Messages from any
  other address are ignored.

Queue IN: the peer sends a capture-queue JSON document (message body, or
a capture-queue.json attachment). Results OUT: one capture-result JSON
message per page plus its PNG images, then a run summary. Served queue
ids are recorded in a local ledger so a re-synced queue never re-runs.

This module knows nothing about WHO the peer is or how it processes
results. deltachat_rpc_client is imported lazily inside functions so the
pure logic (queue selection, ledger) stays unit-testable without the core.
"""

import json
import logging
import os

log = logging.getLogger("bidik.dc_transport")

STATE_FILE = "dc_transport_state.json"
QUEUE_TYPE = "capture-queue"


# ---------------------------------------------------------------- state

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


def is_served(files_dir, queue_id):
    return queue_id in load_state(files_dir).get("served_queue_ids", [])


def mark_served(files_dir, queue_id):
    st = load_state(files_dir)
    served = st.get("served_queue_ids", [])
    if queue_id not in served:
        served.append(queue_id)
        # keep the ledger bounded; ids are only dedupe markers
        save_state(files_dir, served_queue_ids=served[-200:])
    failed = st.get("failed_pages", {})
    if queue_id in failed:
        del failed[queue_id]
        save_state(files_dir, failed_pages=failed)


def failed_page_ids(files_dir, queue_id):
    """Pages of this queue that failed last run and still need a retry."""
    return load_state(files_dir).get("failed_pages", {}).get(queue_id, [])


def record_run_outcome(files_dir, queue_id, failed_ids):
    """Full success marks the queue served. A partial failure NEVER marks
    it served: only the failed pages are recorded, and the next fetch
    retries exactly those."""
    if not failed_ids:
        mark_served(files_dir, queue_id)
        return
    st = load_state(files_dir)
    failed = st.get("failed_pages", {})
    failed[queue_id] = list(failed_ids)
    save_state(files_dir, failed_pages=failed)


# ------------------------------------------------------- pure logic

def extract_queue_doc(text, file_name=None, file_path=None):
    """Return (queue_id, parsed_dict) when a message carries a queue, else None.

    Queue id: explicit "queue_id" field when present, else a hash of the
    document, so re-sent identical queues dedupe to the same id.
    """
    doc = None
    if text:
        t = text.strip()
        if t.startswith("{"):
            try:
                cand = json.loads(t)
                if cand.get("type") == QUEUE_TYPE:
                    doc = cand
            except Exception:
                pass
    if doc is None and file_name and file_path and file_name == "capture-queue.json":
        try:
            with open(file_path) as f:
                cand = json.load(f)
            if cand.get("type") == QUEUE_TYPE:
                doc = cand
        except Exception:
            pass
    if doc is None:
        return None
    qid = doc.get("queue_id")
    if not qid:
        import hashlib
        qid = hashlib.sha256(
            json.dumps(doc, sort_keys=True).encode()).hexdigest()[:16]
    return qid, doc


def pick_latest_queue(messages, served_ids):
    """messages: newest-first iterable of dicts {id, text, file_name,
    file_path}. Return (queue_id, doc) of the newest unserved queue."""
    for m in messages:
        found = extract_queue_doc(m.get("text"), m.get("file_name"), m.get("file_path"))
        if not found:
            continue
        qid, doc = found
        if qid not in served_ids:
            return qid, doc
    return None


# ------------------------------------------------------- core access

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


def _rpc(native_lib_dir, files_dir):
    from deltachat_rpc_client import DeltaChat, Rpc
    kwargs = {"accounts_dir": os.path.join(files_dir, "accounts")}
    if native_lib_dir:
        # Android/Chaquopy: the rpc server ships as an executable
        # librpcsrv.so in the app's native library directory.
        kwargs["rpc_server_path"] = os.path.join(native_lib_dir, "librpcsrv.so")
    # Desktop: rpc_server_path defaults to deltachat-rpc-server on PATH.
    rpc = Rpc(**kwargs)
    rpc.start()
    return rpc, DeltaChat(rpc)


def _account(dc):
    for a in dc.get_all_accounts():
        if a.is_configured():
            return a
    raise RuntimeError("no configured account - run provision() first")


def provision(native_lib_dir, files_dir, relay):
    """One-time, explicit-setup account creation. Network-using."""
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
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
        if not addr:
            raise RuntimeError("account configured but its address is unreadable")
        save_state(files_dir, provisioned=True, self_addr=addr)
        log.info("bidik transport account created")
        return addr
    finally:
        rpc.close()


def self_address(native_lib_dir, files_dir):
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        return _account_addr(_account(dc))
    finally:
        rpc.close()


def _complete_pairing_impl(acct, files_dir, qr_text, timeout_secs=180):
    import time
    from queue import Empty
    acct.start_io()
    # Drop any event from before this attempt so a stale or unrelated
    # SecurejoinJoinerProgress can never satisfy the wait below.
    acct._rpc.clear_all_events(acct.id)
    chat = acct.secure_join(qr_text)
    contacts = chat.get_contacts()
    if len(contacts) != 1:
        raise RuntimeError(
            "join chat has %d contacts, expected exactly 1" % len(contacts))
    peer_id = contacts[0].id
    # Bounded wait for THIS join's completion. Snapshot fields are NOT a
    # valid signal: the 2.69 basic chat snapshot has no is_protected key
    # at all, and its is_encrypted is True even on the self chats. The
    # completion event is SecurejoinJoinerProgress with progress == 1000
    # AND contactId == this join chat's contact (runtime-verified: the
    # event payload is {"contactId": N, "kind": ..., "progress": ...}).
    # Anything else - other progress values, other contacts, events that
    # predate the scan - is ignored.
    events = acct._rpc.get_queue(acct.id)
    deadline = time.time() + timeout_secs
    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            raise RuntimeError("secure-join handshake did not complete in time")
        try:
            event = events.get(timeout=min(2.0, remaining))
        except Empty:
            continue
        if (event or {}).get("kind") == "SecurejoinJoinerProgress" \
                and event.get("progress") == 1000 \
                and event.get("contactId") == peer_id:
            break
    addr = getattr(contacts[0].get_snapshot(), "address", None)
    if not addr:
        raise RuntimeError("paired peer contact has no readable address")
    save_state(files_dir, peer_contact_id=peer_id, peer_addr=addr)
    return {"status": "ok", "peer_addr": addr}


def complete_pairing(native_lib_dir, files_dir, qr_text, timeout_secs=90):
    """Join the peer's secure-join invite QR (scanned by the user).
    Blocks until the handshake completes, then stores the peer's
    key-contact id and address for _peer_chat. Network-using.

    Chatmail requires end-to-end encryption: a bare-address contact
    cannot be sent to ("e2e encryption unavailable"), so secure-join QR
    is the only pairing path. Returns a JSON string
    {"status":"ok","peer_addr":...}."""
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        return json.dumps(
            _complete_pairing_impl(_account(dc), files_dir, qr_text, timeout_secs))
    finally:
        rpc.close()


def _peer_chat(acct, files_dir, peer_addr):
    """Resolve the 1:1 chat with the paired peer.

    Runtime-verified against chatmail core 2.69: create_contact(addr) +
    Contact.create_chat() can mint a SEPARATE empty address-chat even when
    an encrypted key-contact chat with that address already exists, and
    reads there see nothing. Pairing stores the peer's contact id (key
    contact established by secure-join); prefer it. Address lookup falls
    back to get_contact_by_addr + get_chat_by_contact, which return the
    existing encrypted chat.
    """
    contact_id = load_state(files_dir).get("peer_contact_id")
    if contact_id:
        chat = acct.get_chat_by_contact(acct.get_contact_by_id(contact_id))
        if chat is not None:
            return chat
    contact = acct.get_contact_by_addr(peer_addr)
    if contact is not None:
        chat = acct.get_chat_by_contact(contact)
        if chat is not None:
            return chat
        return contact.create_chat()
    raise RuntimeError(
        "no established contact/chat with peer %s - complete QR pairing "
        "first. acct.create_contact(addr).create_chat() is never called "
        "here: on core 2.69 it can mint a separate empty address-chat "
        "even when the encrypted key-contact chat exists, and reads "
        "there see nothing." % peer_addr)


def _recent_message_dicts(acct, chat, limit=50):
    out = []
    for m in chat.get_messages()[-limit:]:
        msg = m.get_message_info() if hasattr(m, "get_message_info") else None
        snap = m.get_snapshot() if hasattr(m, "get_snapshot") else None
        text = ""
        fname = None
        fpath = None
        if snap is not None:
            text = getattr(snap, "text", "") or ""
            f = getattr(snap, "file", None)
            if f:
                fpath = f
                fname = os.path.basename(f)
        out.append({"id": getattr(m, "id", None), "text": text,
                    "file_name": fname, "file_path": fpath})
    out.reverse()  # newest first
    return out


def _fetch_queue_impl(acct, files_dir, peer_addr, sync_wait_secs=8):
    # One-shot fetch runs its own IO: without start_io the account never
    # syncs and a just-sent queue is invisible (verified live). The wait
    # is bounded; "empty" remains a legitimate fast answer when nothing
    # is on the relay.
    acct.start_io()
    if sync_wait_secs > 0:
        import time
        time.sleep(sync_wait_secs)
    # Only the 1:1 chat with the paired peer is ever read; queues arriving
    # anywhere else (other contacts, groups) are invisible to the app.
    chat = _peer_chat(acct, files_dir, peer_addr)
    served = load_state(files_dir).get("served_queue_ids", [])
    picked = pick_latest_queue(_recent_message_dicts(acct, chat), served)
    if not picked:
        return {"status": "empty"}
    qid, doc = picked
    retry = failed_page_ids(files_dir, qid)
    return {"status": "ok", "queue_id": qid, "queue": doc, "retry_pages": retry}


def fetch_queue(native_lib_dir, files_dir, peer_addr):
    """Return a JSON string: {"status":"empty"} or
    {"status":"ok","queue_id":...,"queue":<queue doc>,"retry_pages":[...]}."""
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        return json.dumps(_fetch_queue_impl(_account(dc), files_dir, peer_addr))
    finally:
        rpc.close()


def record_queue_outcome(native_lib_dir, files_dir, queue_id, failed_ids_json):
    record_run_outcome(files_dir, queue_id, json.loads(failed_ids_json or "[]"))
    return "ok"


# deltachat_rpc_client.const.MessageState (2.69): OUT_PENDING=20,
# OUT_FAILED=24, OUT_DELIVERED=26, OUT_MDN_RCVD=28. Snapshots carry the
# INT, not a label: compare numerically, never by substring.
_OUT_DONE = (26, 28)
_OUT_FAILED = 24


def _wait_delivered(msg, timeout_secs=90):
    """Block until THIS outgoing message is accepted by the relay (or
    fails). Waits on the message handle returned by send_text /
    send_message, never on chat.get_messages()[-1]: that last-row form
    races (the just-queued message may not be listed yet, so an older
    delivered row ends the wait) and then rpc.close() drops the still
    pending message. Runtime-verified: closing the rpc session right
    after send risks losing the message before the SMTP job runs."""
    import time
    deadline = time.time() + timeout_secs
    while time.time() < deadline:
        state = msg.get_snapshot().state
        if state in _OUT_DONE:
            return True
        if state == _OUT_FAILED:
            return False
        time.sleep(2)
    return False


def _send_result_impl(acct, files_dir, peer_addr, result_json, attachment_paths):
    acct.start_io()
    chat = _peer_chat(acct, files_dir, peer_addr)
    first = chat.send_text(result_json)
    try:
        page_id = json.loads(result_json).get("page_id", "page")
    except Exception:
        page_id = "page"
    last = None
    for p in attachment_paths:
        # Caption ties each image back to its result message; the result
        # JSON (sent first) also lists the capture filenames in order.
        # send_message(text=..., file=...) is the only caption-carrying
        # send in 2.69: send_file() takes no text argument, and a silent
        # TypeError fallback here once shipped files with no caption.
        caption = "capture-result-file:%s:%s" % (page_id, os.path.basename(p))
        last = chat.send_message(text=caption, file=p)
    # One delivery wait for the whole result: SMTP jobs drain in order, so
    # the last file leaving the device covers the text sent before it. A
    # cold session can take over 45s for the first relay connection
    # (measured live), hence the generous bounded wait.
    if not _wait_delivered(last or first):
        raise RuntimeError("result was not accepted by the relay in time")
    return "ok"


def send_result(native_lib_dir, files_dir, peer_addr, result_json, attachment_paths_json):
    """Send one page's result JSON + PNG files to the peer."""
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        return _send_result_impl(
            _account(dc), files_dir, peer_addr, result_json,
            json.loads(attachment_paths_json or "[]"))
    finally:
        rpc.close()


def send_summary(native_lib_dir, files_dir, peer_addr, text):
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        acct = _account(dc)
        acct.start_io()
        chat = _peer_chat(acct, files_dir, peer_addr)
        msg = chat.send_text(text)
        if not _wait_delivered(msg):
            raise RuntimeError("summary message was not accepted by the relay in time")
        return "ok"
    finally:
        rpc.close()
