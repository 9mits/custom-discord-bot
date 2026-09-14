import unittest
from types import SimpleNamespace

from minecraft_bot import livepings


class LivePingRulesTest(unittest.TestCase):
    def test_every_plugin_ping_maps_to_an_opt_in_topic(self):
        self.assertEqual(livepings.topic_for_event("ping_pvp_queue").key, "pvp")
        self.assertEqual(livepings.topic_for_event("ping_event_soon").key, "events")
        self.assertEqual(livepings.topic_for_event("ping_event_live").key, "events")
        self.assertIsNone(livepings.topic_for_event("crate_open"))

    def test_the_gate_holds_each_subject_for_its_cooldown(self):
        gate = livepings.PingGate()
        self.assertTrue(gate.allow("pvp:Ranked 1v1", 1000, 1200))
        self.assertFalse(gate.allow("pvp:Ranked 1v1", 1500, 1200))
        self.assertTrue(gate.allow("pvp:Ranked 2v2", 1500, 1200), "subjects are independent")
        self.assertTrue(gate.allow("pvp:Ranked 1v1", 2300, 1200))

    def test_active_watch_fires_once_and_rearms_below_the_line(self):
        watch = livepings.ActiveWatch()
        self.assertFalse(watch.observe(4, 5, 0, 100))
        self.assertTrue(watch.observe(5, 5, 10, 100))
        self.assertFalse(watch.observe(6, 5, 20, 100), "hovering above the line stays quiet")
        self.assertFalse(watch.observe(4, 5, 30, 100), "one below does not re-arm")
        self.assertFalse(watch.observe(3, 5, 40, 100))
        self.assertFalse(watch.observe(5, 5, 50, 100), "re-armed, but still inside the cooldown")
        self.assertTrue(watch.observe(5, 5, 200, 100))

    def test_ping_lines_say_what_to_do(self):
        pvp = livepings.ping_content(
            "ping_pvp_queue", {"mode": "Ranked 1v1", "waiting": "1", "required": "2"}
        )
        self.assertIn("**Ranked 1v1**", pvp)
        self.assertIn("1/2", pvp)
        soon = livepings.ping_content(
            "ping_event_soon", {"event": "Amethyst Dragon", "minutes": "10"}, now=1000
        )
        self.assertIn("<t:1600:R>", soon)
        live = livepings.ping_content("ping_event_live", {"event": "2x Keys", "duration": "1 hour"})
        self.assertIn("is live now for **1 hour**", live)
        self.assertIsNone(livepings.ping_content("crate_open", {}))

    def test_booked_events_are_pinged_ten_minutes_ahead_once(self):
        entries = [
            SimpleNamespace(enabled=True, run_at=10_600, label="2x Keys", action="event"),
            SimpleNamespace(enabled=True, run_at=20_000, label="", action="airdrop"),
            SimpleNamespace(enabled=False, run_at=10_590, label="Off", action="event"),
        ]
        due = livepings.schedule_due_for_ping(entries, 10_000)
        self.assertEqual([(run_at, label) for _key, run_at, label in due], [(10_600, "2x Keys")])
        self.assertEqual(
            livepings.upcoming_schedule(entries, 10_000),
            [(10_600, "2x Keys"), (20_000, "airdrop")],
        )

    def test_status_panel_shows_zero_players_while_the_server_is_down(self):
        lines = livepings.status_lines(
            connected=False, online=7, java_address="play.example", bedrock_address="play.example",
            bedrock_port=19132, upcoming=[], now=1,
        )
        self.assertIn("**Server** **Offline**", lines)
        self.assertIn("**Players online** 0", lines)


if __name__ == "__main__":
    unittest.main()
