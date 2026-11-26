"""Unit tests for analyze.py.  Run:  python -m unittest discover -s tools"""
import unittest

from analyze import analyze, parse_doc, parse_value, percentile, summarize

ME = "aaaaaaaa-0000-0000-0000-000000000001"
PEER = "bbbbbbbb-0000-0000-0000-000000000002"


def server(id_, created, received, channel="internet", hops=0, type_="SOS"):
    return {"id": id_, "type": type_, "createdAt": created, "receivedAt": received,
            "channel": channel, "hops": hops, "senderId": ME}


def own(id_, created, type_="SOS", sms=False):
    return {"id": id_, "senderId": ME, "type": type_, "createdAt": created, "smsSent": sms,
            "lat": 13.0, "lng": 80.0, "note": "", "hops": 0, "uploaded": False,
            "receivedAt": created, "uploadedAt": None}


class ParsingTest(unittest.TestCase):
    def test_firestore_values(self):
        self.assertEqual(parse_value({"integerValue": "1732600000000"}), 1732600000000)
        self.assertEqual(parse_value({"doubleValue": 13.08}), 13.08)
        self.assertEqual(parse_value({"stringValue": "SOS"}), "SOS")
        self.assertEqual(parse_value({"timestampValue": "1970-01-01T00:00:01.5Z"}), 1500)
        self.assertIsNone(parse_value({"nullValue": None}))

    def test_doc_id_comes_from_name(self):
        doc = {"name": "projects/p/databases/(default)/documents/messages/abc",
               "fields": {"type": {"stringValue": "SOS"}}}
        self.assertEqual(parse_doc(doc), {"type": "SOS", "id": "abc"})


class MathTest(unittest.TestCase):
    def test_nearest_rank_matches_the_app(self):
        v = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
        self.assertEqual(percentile(v, 50), 50)
        self.assertEqual(percentile(v, 90), 90)
        self.assertEqual(percentile(v, 100), 100)

    def test_empty_summary(self):
        self.assertEqual(summarize([])["count"], 0)


class AnalyzeTest(unittest.TestCase):
    def test_firestore_only_has_no_delivery_rate(self):
        r = analyze([server("a", 0, 5_000)], [])
        self.assertIsNone(r["delivery"])
        self.assertEqual(r["latency"]["internet"]["median"], 5_000)

    def test_delivery_rate_counts_messages_that_never_arrived(self):
        export = {"deviceId": ME, "messages": [own("a", 0), own("b", 10), own("c", 20, type_="SAFE")],
                  "exchanges": []}
        r = analyze([server("a", 0, 1_000), server("c", 20, 2_000, type_="SAFE")], [export])
        d = r["delivery"]
        self.assertEqual((d["sent"], d["delivered"]), (3, 2))
        self.assertAlmostEqual(d["rate"], 2 / 3)
        self.assertEqual(d["by_type"]["SOS"], {"sent": 2, "delivered": 1})
        self.assertEqual([u["id"] for u in d["undelivered"]], ["b"])

    def test_messages_relayed_by_others_are_not_counted_as_sent_by_them(self):
        relayed = own("x", 0) | {"senderId": PEER, "hops": 1}
        export = {"deviceId": ME, "messages": [relayed], "exchanges": []}
        self.assertEqual(analyze([], [export])["delivery"]["sent"], 0)

    def test_undelivered_shows_which_phones_still_hold_it(self):
        a = {"deviceId": ME, "messages": [own("m", 0)], "exchanges": []}
        b = {"deviceId": PEER, "messages": [own("m", 0) | {"hops": 1}], "exchanges": []}
        u = analyze([], [a, b])["delivery"]["undelivered"][0]
        self.assertEqual(u["heldBy"], ["aaaaaaaa", "bbbbbbbb"])

    def test_negative_latency_is_counted_not_hidden(self):
        r = analyze([server("a", 10_000, 9_000), server("b", 0, 4_000)], [])
        self.assertEqual(r["skewed"], 1)
        self.assertEqual(r["latency_all"]["count"], 1)

    def test_latency_and_hops_per_channel(self):
        r = analyze([server("a", 0, 1_000), server("b", 0, 60_000, "relay", hops=2),
                     server("c", 0, 30_000, "sms")], [])
        self.assertEqual(r["latency"]["relay"]["median"], 60_000)
        self.assertEqual(r["latency"]["sms"]["count"], 1)
        self.assertEqual(r["hops"], {0: 2, 2: 1})

    def test_time_window_filters_both_sides(self):
        export = {"deviceId": ME, "messages": [own("old", 0), own("new", 100)], "exchanges": []}
        r = analyze([server("old", 0, 1), server("new", 100, 200)], [export], since=50)
        self.assertEqual(r["delivered_count"], 1)
        self.assertEqual(r["delivery"]["sent"], 1)

    def test_exchange_pairs_are_counted_once(self):
        ex = {"peer": "bbbbbbbb", "startedAt": 0, "durationMs": 4_000, "sent": 1, "receivedNew": 1, "success": True}
        a = {"deviceId": ME, "messages": [], "exchanges": [ex]}
        b = {"deviceId": PEER, "messages": [], "exchanges": [ex | {"peer": "aaaaaaaa"}]}
        x = analyze([], [a, b])["exchanges"]
        self.assertEqual(x["records"], 2)
        self.assertEqual(x["pairs"], 1)
        self.assertEqual(x["success_rate"], 1.0)


if __name__ == "__main__":
    unittest.main()
