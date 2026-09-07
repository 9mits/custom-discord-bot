package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Pure ordering for the rank board that lives inside the PvP menu. */
final class PvpRankLeaderboard {
    record Row(int placement, UUID playerId, String username, PvpRecordStore.Record record) {
    }

    private PvpRankLeaderboard() {
    }

    static List<Row> top(
            Map<UUID, PvpRecordStore.Record> records,
            Function<UUID, String> names,
            int limit
    ) {
        List<Row> ranked = new ArrayList<>();
        records.forEach((playerId, record) -> {
            if (record == null || record.isEmpty()) {
                return;
            }
            String resolved = names.apply(playerId);
            String username = resolved == null || resolved.isBlank()
                    ? playerId.toString().substring(0, 8)
                    : resolved;
            ranked.add(new Row(0, playerId, username, record));
        });
        ranked.sort(Comparator
                .comparingLong((Row row) -> row.record().rating()).reversed()
                .thenComparing(Comparator.comparingLong(
                        (Row row) -> row.record().wins()).reversed())
                .thenComparing(Comparator.comparingLong(
                        (Row row) -> row.record().kills()).reversed())
                .thenComparing(Row::username, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.playerId().toString()));
        int shown = Math.min(Math.max(0, limit), ranked.size());
        List<Row> placed = new ArrayList<>(shown);
        for (int index = 0; index < shown; index++) {
            Row row = ranked.get(index);
            placed.add(new Row(index + 1, row.playerId(), row.username(), row.record()));
        }
        return List.copyOf(placed);
    }
}
