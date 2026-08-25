package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalNotificationGateTest {
    @Test
    void countdownReservationQueuesTheNewestAlertUntilRelease() {
        PersonalNotificationGate gate = new PersonalNotificationGate();
        UUID player = UUID.randomUUID();
        int reservation = gate.reserve(player);

        assertTrue(gate.offer(player, Component.text("paid")).isEmpty());
        assertTrue(gate.offer(player, Component.text("leaderboard")).isEmpty());
        assertEquals(
                Component.text("leaderboard"),
                gate.release(player, reservation).orElseThrow()
        );
        assertEquals(
                Component.text("next"),
                gate.offer(player, Component.text("next")).orElseThrow()
        );
    }

    @Test
    void staleReleaseCannotBreakANewerCountdown() {
        PersonalNotificationGate gate = new PersonalNotificationGate();
        UUID player = UUID.randomUUID();
        int oldReservation = gate.reserve(player);
        int currentReservation = gate.reserve(player);
        gate.offer(player, Component.text("waiting"));

        assertTrue(gate.release(player, oldReservation).isEmpty());
        assertEquals(
                Component.text("waiting"),
                gate.release(player, currentReservation).orElseThrow()
        );
    }
}
