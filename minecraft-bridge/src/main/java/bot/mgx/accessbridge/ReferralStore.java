package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Referral state in {@code referrals.json}: when each account was first and last seen,
 * the hashed connections it used, open welcome rewards, every accepted referral, and
 * Shards owed to players who were offline when they were earned.
 *
 * <p>Addresses are never written in the clear. Each is a salted SHA-256 whose salt is
 * generated per server and kept in the same file, so the hashes can be compared with
 * each other and with nothing else.
 */
final class ReferralStore {
    static final int MAXIMUM_ADDRESSES = 12;

    static final class AccountRow {
        long firstSeen;
        long lastSeen;
        long playMinutes;
        String discordOwner;
        long lastReturnRewardAt;
        List<String> addresses = new ArrayList<>();
    }

    static final class Welcome {
        ReferralRules.Kind kind;
        long startedAt;
        int onlineMinutes;
        String referrer;
        boolean qualified;
        boolean playerPaid;
        boolean referrerPaid;
    }

    static final class ReferralRow {
        String referrer;
        String referee;
        String referrerOwner;
        String refereeOwner;
        ReferralRules.Kind kind;
        long at;
    }

    private static final class Data {
        String salt;
        Map<String, AccountRow> accounts = new LinkedHashMap<>();
        Map<String, Welcome> welcomes = new LinkedHashMap<>();
        List<ReferralRow> referrals = new ArrayList<>();
        Map<String, Integer> owedShards = new LinkedHashMap<>();
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Data data = new Data();

    ReferralStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            try {
                Data loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) {
                    data = loaded;
                }
            } catch (RuntimeException exception) {
                throw new IOException("Referral store is unreadable", exception);
            }
        }
        if (data.accounts == null) data.accounts = new LinkedHashMap<>();
        if (data.welcomes == null) data.welcomes = new LinkedHashMap<>();
        if (data.referrals == null) data.referrals = new ArrayList<>();
        if (data.owedShards == null) data.owedShards = new LinkedHashMap<>();
        data.welcomes.values().removeIf(welcome -> welcome == null || welcome.kind == null);
        data.referrals.removeIf(row -> row == null || row.kind == null
                || row.referrerOwner == null || row.refereeOwner == null);
        if (data.salt == null || data.salt.isBlank()) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            data.salt = HexFormat.of().formatHex(bytes);
            persist();
        }
    }

    synchronized Optional<AccountRow> account(UUID playerId) {
        return Optional.ofNullable(data.accounts.get(playerId.toString()));
    }

    synchronized AccountRow accountOrCreate(UUID playerId) {
        return data.accounts.computeIfAbsent(playerId.toString(), ignored -> new AccountRow());
    }

    synchronized Map<UUID, AccountRow> accounts() {
        Map<UUID, AccountRow> copy = new LinkedHashMap<>();
        data.accounts.forEach((id, row) -> copy.put(UUID.fromString(id), row));
        return copy;
    }

    synchronized String hashAddress(String address) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((data.salt + "|" + address).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Remembers a hashed connection, newest last, dropping the oldest past the cap. */
    synchronized void rememberAddress(UUID playerId, String hash) {
        AccountRow row = accountOrCreate(playerId);
        if (row.addresses == null) row.addresses = new ArrayList<>();
        LinkedHashSet<String> ordered = new LinkedHashSet<>(row.addresses);
        ordered.remove(hash);
        ordered.add(hash);
        List<String> kept = new ArrayList<>(ordered);
        while (kept.size() > MAXIMUM_ADDRESSES) kept.remove(0);
        row.addresses = kept;
    }

    synchronized Optional<Welcome> welcome(UUID playerId) {
        return Optional.ofNullable(data.welcomes.get(playerId.toString()));
    }

    synchronized Map<UUID, Welcome> welcomes() {
        Map<UUID, Welcome> copy = new LinkedHashMap<>();
        data.welcomes.forEach((id, welcome) -> copy.put(UUID.fromString(id), welcome));
        return copy;
    }

    synchronized void openWelcome(UUID playerId, ReferralRules.Kind kind, long now) {
        Welcome welcome = new Welcome();
        welcome.kind = kind;
        welcome.startedAt = now;
        data.welcomes.put(playerId.toString(), welcome);
    }

    synchronized void closeWelcome(UUID playerId) {
        data.welcomes.remove(playerId.toString());
    }

    synchronized List<ReferralRules.Referral> history() {
        return data.referrals.stream()
                .map(row -> new ReferralRules.Referral(
                        row.referrerOwner, row.refereeOwner, row.kind, row.at))
                .toList();
    }

    synchronized void addReferral(
            UUID referrer, String referrerOwner, UUID referee, String refereeOwner,
            ReferralRules.Kind kind, long now
    ) {
        ReferralRow row = new ReferralRow();
        row.referrer = referrer.toString();
        row.referee = referee.toString();
        row.referrerOwner = referrerOwner;
        row.refereeOwner = refereeOwner;
        row.kind = kind;
        row.at = now;
        data.referrals.add(row);
    }

    synchronized List<ReferralRow> referralsBy(String referrerOwner) {
        return data.referrals.stream()
                .filter(row -> row.referrerOwner.equals(referrerOwner))
                .toList();
    }

    synchronized void owe(UUID playerId, int shards) {
        if (shards > 0) data.owedShards.merge(playerId.toString(), shards, Integer::sum);
    }

    synchronized int takeOwed(UUID playerId) {
        Integer owed = data.owedShards.remove(playerId.toString());
        return owed == null ? 0 : owed;
    }

    synchronized void persist() {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
