"""Pure-logic tests for dc_transport (no DeltaChat core needed)."""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "main", "python"))

import dc_transport as t


class ExtractQueueDocTest(unittest.TestCase):
    def test_body_json(self):
        doc = {"type": "capture-queue", "v": 1, "pages": [{"id": "a", "url": "https://example.com/"}]}
        found = t.extract_queue_doc(json.dumps(doc))
        self.assertIsNotNone(found)
        qid, parsed = found
        self.assertEqual(parsed["pages"][0]["id"], "a")
        self.assertTrue(qid)

    def test_attachment(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "capture-queue.json")
            with open(p, "w") as f:
                json.dump({"type": "capture-queue", "v": 1, "pages": []}, f)
            self.assertIsNotNone(t.extract_queue_doc(None, "capture-queue.json", p))

    def test_rejects_other_content(self):
        self.assertIsNone(t.extract_queue_doc("hello there"))
        self.assertIsNone(t.extract_queue_doc(json.dumps({"type": "other"})))
        self.assertIsNone(t.extract_queue_doc(None, "other.json", "/tmp/nope"))

    def test_same_document_same_id(self):
        doc = {"type": "capture-queue", "v": 1, "pages": [{"id": "a", "url": "https://example.com/"}]}
        a = t.extract_queue_doc(json.dumps(doc))[0]
        b = t.extract_queue_doc(json.dumps(doc))[0]
        self.assertEqual(a, b)

    def test_explicit_queue_id_wins(self):
        doc = {"type": "capture-queue", "v": 1, "queue_id": "q-7", "pages": []}
        self.assertEqual(t.extract_queue_doc(json.dumps(doc))[0], "q-7")


class PickLatestQueueTest(unittest.TestCase):
    def test_newest_unserved_wins(self):
        msgs = [
            {"id": 3, "text": json.dumps({"type": "capture-queue", "queue_id": "q3", "pages": []})},
            {"id": 2, "text": json.dumps({"type": "capture-queue", "queue_id": "q2", "pages": []})},
            {"id": 1, "text": "hello"},
        ]
        self.assertEqual(t.pick_latest_queue(msgs, [])[0], "q3")
        self.assertEqual(t.pick_latest_queue(msgs, ["q3"])[0], "q2")
        self.assertIsNone(t.pick_latest_queue(msgs, ["q3", "q2"]))


class LedgerTest(unittest.TestCase):
    def test_mark_and_check(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertFalse(t.is_served(d, "q1"))
            t.mark_served(d, "q1")
            self.assertTrue(t.is_served(d, "q1"))
            self.assertFalse(t.is_served(d, "q2"))

    def test_partial_failure_never_serves_whole_queue(self):
        with tempfile.TemporaryDirectory() as d:
            t.record_run_outcome(d, "q1", ["page-b"])
            self.assertFalse(t.is_served(d, "q1"))
            self.assertEqual(t.failed_page_ids(d, "q1"), ["page-b"])
            # full success on retry serves and clears the failure record
            t.record_run_outcome(d, "q1", [])
            self.assertTrue(t.is_served(d, "q1"))
            self.assertEqual(t.failed_page_ids(d, "q1"), [])


# ---------------------------------------------------------- fake core

QUEUE = json.dumps({"type": "capture-queue", "v": 1, "queue_id": "q1",
                    "pages": [{"id": "a", "url": "https://example.com/"},
                              {"id": "b", "url": "https://example.com/b"}]})


class FakeSnapshot:
    def __init__(self, text="", file=None, state=26):  # OUT_DELIVERED
        self.text = text
        self.file = file
        self.state = state


class FakeMsg:
    _next = [0]

    def __init__(self, text="", file=None):
        FakeMsg._next[0] += 1
        self.id = FakeMsg._next[0]
        self._snap = FakeSnapshot(text, file)

    def get_snapshot(self):
        return self._snap


class FakeChat:
    def __init__(self):
        self.messages = []
        self.sent = []

    def get_messages(self):
        return self.messages

    def send_text(self, text):
        self.sent.append(("text", text))
        msg = FakeMsg(text)
        self.messages.append(msg)
        return msg

    def send_message(self, text=None, file=None, **kw):
        self.sent.append(("file", file, text))
        msg = FakeMsg(text or "", file)
        self.messages.append(msg)
        return msg


class FakeContact:
    def __init__(self, chat):
        self._chat = chat

    def create_chat(self):
        return self._chat


class FakePairContact:
    def __init__(self, address="peer@example.com", cid=42):
        self.id = cid
        self._address = address

    def get_snapshot(self):
        class S:
            pass
        s = S()
        s.address = self._address
        return s


class FakePairChat:
    def __init__(self, protected=True, contacts=None):
        self._protected = protected
        self._contacts = contacts if contacts is not None else [FakePairContact()]

    def get_basic_snapshot(self):
        class S:
            pass
        s = S()
        s.is_protected = self._protected
        return s

    def get_contacts(self):
        return self._contacts


class _FakeRpc:
    def __init__(self, events):
        self._events = events
        self.cleared = False

    def get_queue(self, account_id):
        return self._events

    def clear_all_events(self, account_id):
        self.cleared = True
        while True:
            try:
                self._events.get_nowait()
            except Exception:
                return


class FakeAcct:
    id = 1

    def __init__(self, peer_chat, other_chat, paired=True):
        import queue as _q
        self.peer_chat = peer_chat
        self.other_chat = other_chat
        self.paired = paired
        self.pair_chat = FakePairChat()
        self.scanned_qrs = []
        self.events = _q.Queue()
        self.events.put({"kind": "SecurejoinJoinerProgress",
                         "progress": 1000, "contactId": 42})
        self._rpc = _FakeRpc(self.events)

    def start_io(self):
        pass

    def is_configured(self):
        return True

    def get_contact_by_addr(self, addr):
        if self.paired and addr == "peer@example.com":
            return FakeContact(self.peer_chat)
        return None

    def get_chat_by_contact(self, contact):
        return contact.create_chat()

    def secure_join(self, qr_text):
        self.scanned_qrs.append(qr_text)
        return self.pair_chat


class PeerPinningAndFetchTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.peer_chat = FakeChat()
        self.other_chat = FakeChat()
        self.acct = FakeAcct(self.peer_chat, self.other_chat)

    def tearDown(self):
        self.dir.cleanup()

    def test_wrong_peer_queue_never_read(self):
        # A queue in some other chat must be invisible.
        self.other_chat.messages.append(FakeMsg(QUEUE))
        self.peer_chat.messages.append(FakeMsg("just chatting"))
        res = t._fetch_queue_impl(self.acct, self.dir.name, "peer@example.com", sync_wait_secs=0)
        self.assertEqual(res["status"], "empty")

    def test_unpaired_peer_raises_instead_of_blind_create(self):
        acct = FakeAcct(self.peer_chat, self.other_chat, paired=False)
        with self.assertRaises(RuntimeError):
            t._fetch_queue_impl(acct, self.dir.name, "peer@example.com", sync_wait_secs=0)

    def test_fetch_returns_retry_pages_after_partial_failure(self):
        self.peer_chat.messages.append(FakeMsg(QUEUE))
        t.record_run_outcome(self.dir.name, "q1", ["b"])
        res = t._fetch_queue_impl(self.acct, self.dir.name, "peer@example.com", sync_wait_secs=0)
        self.assertEqual(res["status"], "ok")
        self.assertEqual(res["queue_id"], "q1")
        self.assertEqual(res["retry_pages"], ["b"])
        # full queue still carried; the caller filters to retry pages
        self.assertEqual(len(res["queue"]["pages"]), 2)

    def test_served_queue_not_refetched(self):
        self.peer_chat.messages.append(FakeMsg(QUEUE))
        t.record_run_outcome(self.dir.name, "q1", [])
        res = t._fetch_queue_impl(self.acct, self.dir.name, "peer@example.com", sync_wait_secs=0)
        self.assertEqual(res["status"], "empty")


class CompletePairingTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.acct = FakeAcct(FakeChat(), FakeChat())

    def tearDown(self):
        self.dir.cleanup()

    def test_stores_peer_contact_id_and_addr(self):
        # The pre-scan clear drains anything enqueued before the attempt,
        # so deliver this join's completion event right after the clear.
        rpc = _FakeRpc(self.acct.events)
        original_clear = rpc.clear_all_events

        def clear_then_deliver(account_id):
            original_clear(account_id)
            self.acct.events.put({"kind": "SecurejoinJoinerProgress",
                                  "progress": 1000, "contactId": 42})

        rpc.clear_all_events = clear_then_deliver
        self.acct._rpc = rpc
        res = t._complete_pairing_impl(self.acct, self.dir.name, "dcaccount:fake-qr")
        self.assertEqual(res["status"], "ok")
        self.assertEqual(res["peer_addr"], "peer@example.com")
        st = t.load_state(self.dir.name)
        self.assertEqual(st["peer_contact_id"], 42)
        self.assertEqual(st["peer_addr"], "peer@example.com")
        self.assertEqual(self.acct.scanned_qrs, ["dcaccount:fake-qr"])
        self.assertTrue(rpc.cleared)

    def test_unfinished_handshake_times_out(self):
        import queue as _q
        self.acct.events = _q.Queue()  # no completion event ever arrives
        self.acct._rpc = _FakeRpc(self.acct.events)
        with self.assertRaises(RuntimeError):
            t._complete_pairing_impl(self.acct, self.dir.name, "qr", timeout_secs=0)

    def test_unrelated_completion_event_never_satisfies_wait(self):
        # A progress-1000 event for a DIFFERENT contact must not complete
        # this join. It is re-enqueued after the pre-scan event clear so
        # the contactId filter (not just the clear) is what rejects it.
        rpc = _FakeRpc(self.acct.events)
        original_clear = rpc.clear_all_events

        def clear_then_poison(account_id):
            original_clear(account_id)
            self.acct.events.put({"kind": "SecurejoinJoinerProgress",
                                  "progress": 1000, "contactId": 999})

        rpc.clear_all_events = clear_then_poison
        self.acct._rpc = rpc
        with self.assertRaises(RuntimeError):
            t._complete_pairing_impl(self.acct, self.dir.name, "qr", timeout_secs=1)
        self.assertTrue(rpc.cleared)

    def test_progress_below_1000_does_not_complete(self):
        import queue as _q
        self.acct.events = _q.Queue()
        self.acct.events.put({"kind": "SecurejoinJoinerProgress",
                              "progress": 400, "contactId": 42})
        self.acct._rpc = _FakeRpc(self.acct.events)
        with self.assertRaises(RuntimeError):
            t._complete_pairing_impl(self.acct, self.dir.name, "qr", timeout_secs=1)


class SendResultTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.peer_chat = FakeChat()
        self.acct = FakeAcct(self.peer_chat, FakeChat())

    def tearDown(self):
        self.dir.cleanup()

    def test_order_and_caption_matching(self):
        result = json.dumps({"type": "capture-result", "page_id": "a",
                             "captures": ["a-viewport.png"], "status": "ok"})
        t._send_result_impl(self.acct, self.dir.name, "peer@example.com", result,
                            ["/tmp/a-viewport.png"])
        sent = self.peer_chat.sent
        self.assertEqual(sent[0][0], "text")  # result JSON first
        self.assertEqual(sent[1][0], "file")
        self.assertEqual(sent[1][1], "/tmp/a-viewport.png")
        self.assertEqual(sent[1][2], "capture-result-file:a:a-viewport.png")

    def test_partial_send_failure_raises_and_marks_nothing(self):
        def fail_second(text=None, file=None, **kw):
            if "second" in (file or ""):
                raise IOError("relay rejected")
            self.peer_chat.sent.append(("file", file, text))
            return FakeMsg(text or "", file)
        self.peer_chat.send_message = fail_second
        result = json.dumps({"type": "capture-result", "page_id": "a", "status": "ok"})
        with self.assertRaises(IOError):
            t._send_result_impl(self.acct, self.dir.name, "peer@example.com", result,
                                ["/tmp/first.png", "/tmp/second.png"])
        # outcome was never recorded; queue is NOT served by a partial send
        self.assertFalse(t.is_served(self.dir.name, "q1"))


if __name__ == "__main__":
    unittest.main()
