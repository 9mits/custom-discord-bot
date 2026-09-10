import asyncio
import json
import unittest

from minecraft_bot.schedule import STORAGE_KEY, Schedule


OWNER_UUID = "11111111-1111-1111-1111-111111111111"


class MemoryData:
    def __init__(self, value="[]"):
        self.value = value

    async def get_config(self, key, default=None):
        self.assert_key(key)
        await asyncio.sleep(0)
        return self.value if self.value is not None else default

    async def set_config(self, key, value):
        self.assert_key(key)
        await asyncio.sleep(0)
        self.value = value

    @staticmethod
    def assert_key(key):
        if key != STORAGE_KEY:
            raise AssertionError(key)


class FakeBot:
    def __init__(self, value="[]"):
        self.data = MemoryData(value)


class MinecraftScheduleTests(unittest.IsolatedAsyncioTestCase):
    async def test_owner_identity_round_trips_and_is_returned_when_due(self):
        bot = FakeBot()
        schedule = Schedule(bot)
        created = await schedule.upsert(
            {"id": "event-one", "action": "airdrop", "run_at": 1_700_000_000},
            actor_uuid=OWNER_UUID,
            actor_label="Owner",
        )

        self.assertEqual(created.actor_uuid, OWNER_UUID)
        self.assertEqual(created.actor_label, "Owner")
        due = await schedule.due(now=1_700_000_000)
        self.assertEqual([entry.actor_uuid for entry in due], [OWNER_UUID])

    async def test_new_entry_requires_a_valid_linked_owner_uuid(self):
        schedule = Schedule(FakeBot())

        with self.assertRaisesRegex(ValueError, "linked Minecraft owner"):
            await schedule.upsert(
                {"action": "airdrop", "run_at": 1_700_000_000}, actor_uuid=""
            )

    async def test_concurrent_upserts_do_not_overwrite_each_other(self):
        bot = FakeBot()
        schedule = Schedule(bot)

        await asyncio.gather(
            schedule.upsert(
                {"id": "one", "action": "airdrop", "run_at": 1_700_000_001},
                actor_uuid=OWNER_UUID,
            ),
            schedule.upsert(
                {"id": "two", "action": "huge-block", "run_at": 1_700_000_002},
                actor_uuid=OWNER_UUID,
            ),
        )

        self.assertEqual({row["id"] for row in json.loads(bot.data.value)}, {"one", "two"})

    async def test_malformed_stored_rows_do_not_break_the_schedule(self):
        bot = FakeBot(json.dumps([
            {"id": "broken", "action": "airdrop", "run_at": "not-a-time"},
            {
                "id": "valid",
                "action": "airdrop",
                "run_at": 1_700_000_000,
                "actor_uuid": OWNER_UUID,
            },
        ]))
        schedule = Schedule(bot)

        await schedule.load()

        self.assertEqual([entry.id for entry in schedule.entries], ["valid"])


if __name__ == "__main__":
    unittest.main()
