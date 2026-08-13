package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class ClanStore {
    static final int MAX_MEMBERS = 25;
    static final long INVITE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int FORMAT_VERSION = 1;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{2,15}");

    enum ClanRole {
        LEADER,
        STAFF,
        MEMBER
    }

    record ClanView(
            UUID id,
            String name,
            UUID leader,
            Map<UUID, String> members,
            Set<UUID> staff,
            boolean friendlyFire
    ) {
        ClanRole roleOf(UUID playerId) {
            if (leader.equals(playerId)) {
                return ClanRole.LEADER;
            }
            return staff.contains(playerId) ? ClanRole.STAFF : ClanRole.MEMBER;
        }
    }

    private static final class SavedState {
        int version = FORMAT_VERSION;
        List<SavedClan> clans = new ArrayList<>();
        Map<String, SavedInvite> invites = new LinkedHashMap<>();
    }

    private static final class SavedClan {
        String id;
        String name;
        String leader;
        Map<String, String> members = new LinkedHashMap<>();
        Set<String> staff = new LinkedHashSet<>();
        boolean friendlyFire;
    }

    private static final class SavedInvite {
        String clanId;
        String invitedBy;
        long expiresAt;
    }

    static final class ClanException extends IllegalArgumentException {
        ClanException(String message) {
            super(message);
        }
    }

    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private SavedState state;
    private final Map<UUID, SavedClan> memberIndex = new LinkedHashMap<>();

    ClanStore(Path path) throws IOException {
        this.path = path;
        this.state = load(path);
        rebuildIndex();
    }

    synchronized ClanView create(UUID owner, String ownerName, String requestedName) throws IOException {
        requireNotInClan(owner);
        String name = normalizeName(requestedName);
        requireUniqueName(name, null);
        SavedClan clan = new SavedClan();
        clan.id = UUID.randomUUID().toString();
        clan.name = name;
        clan.leader = owner.toString();
        clan.members.put(owner.toString(), cleanPlayerName(ownerName));
        state.clans.add(clan);
        memberIndex.put(owner, clan);
        persist();
        return view(clan);
    }

    synchronized Optional<ClanView> clanOf(UUID playerId) {
        SavedClan clan = memberIndex.get(playerId);
        return clan == null ? Optional.empty() : Optional.of(view(clan));
    }

    synchronized Optional<ClanView> findClan(String name) {
        String lookup = normalizeLookup(name);
        return state.clans.stream()
                .filter(clan -> clan.name.toLowerCase(Locale.ROOT).equals(lookup))
                .findFirst()
                .map(this::view);
    }

    synchronized List<ClanView> list() {
        return state.clans.stream()
                .map(this::view)
                .sorted(Comparator.comparing(ClanView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized String invite(
            UUID actor,
            UUID target,
            String targetName,
            long now
    ) throws IOException {
        SavedClan clan = requireStaff(actor);
        if (actor.equals(target)) {
            throw new ClanException("You cannot invite yourself.");
        }
        requireNotInClan(target);
        if (clan.members.size() >= MAX_MEMBERS) {
            throw new ClanException("Your clan has reached its 25-player limit.");
        }
        pruneInvites(now);
        SavedInvite invite = new SavedInvite();
        invite.clanId = clan.id;
        invite.invitedBy = actor.toString();
        invite.expiresAt = now + INVITE_TTL_MILLIS;
        state.invites.put(target.toString(), invite);
        persist();
        return cleanPlayerName(targetName);
    }

    synchronized ClanView accept(UUID player, String playerName, long now) throws IOException {
        requireNotInClan(player);
        pruneInvites(now);
        SavedInvite invite = state.invites.get(player.toString());
        if (invite == null) {
            throw new ClanException("You do not have an active clan invite.");
        }
        SavedClan clan = requireClan(UUID.fromString(invite.clanId));
        if (clan.members.size() >= MAX_MEMBERS) {
            state.invites.remove(player.toString());
            persist();
            throw new ClanException("That clan is full.");
        }
        clan.members.put(player.toString(), cleanPlayerName(playerName));
        memberIndex.put(player, clan);
        state.invites.remove(player.toString());
        persist();
        return view(clan);
    }

    synchronized void decline(UUID player) throws IOException {
        if (state.invites.remove(player.toString()) == null) {
            throw new ClanException("You do not have an active clan invite.");
        }
        persist();
    }

    synchronized ClanView rename(UUID actor, String requestedName) throws IOException {
        SavedClan clan = requireLeader(actor);
        String name = normalizeName(requestedName);
        requireUniqueName(name, UUID.fromString(clan.id));
        clan.name = name;
        persist();
        return view(clan);
    }

    synchronized ClanView setStaff(UUID actor, UUID target, boolean promoted) throws IOException {
        SavedClan clan = requireLeader(actor);
        requireMember(clan, target);
        if (actor.equals(target)) {
            throw new ClanException("The clan leader already has every management permission.");
        }
        boolean changed = promoted
                ? clan.staff.add(target.toString())
                : clan.staff.remove(target.toString());
        if (!changed) {
            throw new ClanException(promoted
                    ? "That player is already clan staff."
                    : "That player is not clan staff.");
        }
        persist();
        return view(clan);
    }

    synchronized ClanView transfer(UUID actor, UUID target) throws IOException {
        SavedClan clan = requireLeader(actor);
        requireMember(clan, target);
        if (actor.equals(target)) {
            throw new ClanException("You are already the clan leader.");
        }
        clan.staff.remove(target.toString());
        clan.staff.add(actor.toString());
        clan.leader = target.toString();
        persist();
        return view(clan);
    }

    synchronized String kick(UUID actor, UUID target) throws IOException {
        SavedClan clan = requireStaff(actor);
        requireMember(clan, target);
        if (actor.equals(target)) {
            throw new ClanException("Use /clans leave to leave your clan.");
        }
        if (clan.leader.equals(target.toString())) {
            throw new ClanException("The clan leader cannot be kicked.");
        }
        if (!clan.leader.equals(actor.toString()) && clan.staff.contains(target.toString())) {
            throw new ClanException("Only the clan leader can remove clan staff.");
        }
        String name = clan.members.remove(target.toString());
        clan.staff.remove(target.toString());
        memberIndex.remove(target);
        persist();
        return name;
    }

    synchronized String leave(UUID player) throws IOException {
        SavedClan clan = requireClanForMember(player);
        if (clan.leader.equals(player.toString())) {
            throw new ClanException("Transfer leadership or disband the clan before leaving.");
        }
        String name = clan.name;
        clan.members.remove(player.toString());
        clan.staff.remove(player.toString());
        memberIndex.remove(player);
        persist();
        return name;
    }

    synchronized String disband(UUID actor) throws IOException {
        SavedClan clan = requireLeader(actor);
        String name = clan.name;
        UUID clanId = UUID.fromString(clan.id);
        state.clans.remove(clan);
        for (String memberId : clan.members.keySet()) {
            memberIndex.remove(UUID.fromString(memberId));
        }
        state.invites.entrySet().removeIf(entry -> entry.getValue().clanId.equals(clanId.toString()));
        persist();
        return name;
    }

    synchronized ClanView toggleFriendlyFire(UUID actor) throws IOException {
        SavedClan clan = requireLeader(actor);
        clan.friendlyFire = !clan.friendlyFire;
        persist();
        return view(clan);
    }

    synchronized Optional<UUID> findMember(UUID clanId, String playerName) {
        SavedClan clan;
        try {
            clan = requireClan(clanId);
        } catch (ClanException exception) {
            return Optional.empty();
        }
        String lookup = normalizeLookup(playerName);
        return clan.members.entrySet().stream()
                .filter(entry -> entry.getValue().toLowerCase(Locale.ROOT).equals(lookup))
                .map(entry -> UUID.fromString(entry.getKey()))
                .findFirst();
    }

    synchronized void touchPlayerName(UUID playerId, String playerName) throws IOException {
        SavedClan clan = memberIndex.get(playerId);
        if (clan == null) {
            return;
        }
        String cleanName = cleanPlayerName(playerName);
        if (!cleanName.equals(clan.members.get(playerId.toString()))) {
            clan.members.put(playerId.toString(), cleanName);
            persist();
        }
    }

    private SavedState load(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) == 0) {
            return new SavedState();
        }
        try {
            SavedState loaded = gson.fromJson(Files.readString(source, StandardCharsets.UTF_8), SavedState.class);
            if (loaded == null || loaded.version != FORMAT_VERSION) {
                throw new IOException("Unsupported clans.json format version");
            }
            if (loaded.clans == null) {
                loaded.clans = new ArrayList<>();
            }
            if (loaded.invites == null) {
                loaded.invites = new LinkedHashMap<>();
            }
            return loaded;
        } catch (JsonParseException exception) {
            throw new IOException("clans.json is not valid JSON", exception);
        }
    }

    private void rebuildIndex() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Set<UUID> clanIds = new LinkedHashSet<>();
        try {
            for (SavedClan clan : state.clans) {
                UUID clanId = UUID.fromString(clan.id);
                UUID leader = UUID.fromString(clan.leader);
                if (!clanIds.add(clanId) || !names.add(normalizeLookup(clan.name))) {
                    throw new IOException("Clan IDs and names must be unique");
                }
                normalizeName(clan.name);
                if (clan.members == null || !clan.members.containsKey(leader.toString())) {
                    throw new IOException("A clan leader must be present in its member list");
                }
                if (clan.members.isEmpty() || clan.members.size() > MAX_MEMBERS) {
                    throw new IOException("Clan member counts must be between 1 and " + MAX_MEMBERS);
                }
                if (clan.staff == null) {
                    clan.staff = new LinkedHashSet<>();
                }
                clan.staff.remove(leader.toString());
                clan.staff.removeIf(member -> !clan.members.containsKey(member));
                for (Map.Entry<String, String> member : clan.members.entrySet()) {
                    UUID memberId = UUID.fromString(member.getKey());
                    if (member.getValue() == null || member.getValue().isBlank()) {
                        throw new IOException("Clan members must have a saved player name");
                    }
                    if (memberIndex.putIfAbsent(memberId, clan) != null) {
                        throw new IOException("A player cannot belong to multiple clans");
                    }
                }
            }
            Set<String> knownClanIds = new LinkedHashSet<>();
            state.clans.forEach(clan -> knownClanIds.add(clan.id));
            for (Map.Entry<String, SavedInvite> entry : state.invites.entrySet()) {
                UUID.fromString(entry.getKey());
                SavedInvite invite = entry.getValue();
                if (invite == null
                        || !knownClanIds.contains(invite.clanId)
                        || invite.invitedBy == null
                        || invite.expiresAt <= 0) {
                    throw new IOException("clans.json contains an invalid clan invite");
                }
                UUID.fromString(invite.invitedBy);
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("clans.json contains invalid clan data", exception);
        }
    }

    private void persist() throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(state), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private SavedClan requireClanForMember(UUID member) {
        SavedClan clan = memberIndex.get(member);
        if (clan == null) {
            throw new ClanException("You are not in a clan.");
        }
        return clan;
    }

    private SavedClan requireClan(UUID clanId) {
        return state.clans.stream()
                .filter(candidate -> candidate.id.equals(clanId.toString()))
                .findFirst()
                .orElseThrow(() -> new ClanException("That clan no longer exists."));
    }

    private SavedClan requireStaff(UUID actor) {
        SavedClan clan = requireClanForMember(actor);
        if (!clan.leader.equals(actor.toString()) && !clan.staff.contains(actor.toString())) {
            throw new ClanException("Only clan staff can do that.");
        }
        return clan;
    }

    private SavedClan requireLeader(UUID actor) {
        SavedClan clan = requireClanForMember(actor);
        if (!clan.leader.equals(actor.toString())) {
            throw new ClanException("Only the clan leader can do that.");
        }
        return clan;
    }

    private void requireNotInClan(UUID member) {
        if (memberIndex.containsKey(member)) {
            throw new ClanException("That player is already in a clan.");
        }
    }

    private void requireMember(SavedClan clan, UUID member) {
        if (!clan.members.containsKey(member.toString())) {
            throw new ClanException("That player is not in your clan.");
        }
    }

    private void requireUniqueName(String name, UUID exceptId) {
        String lookup = normalizeLookup(name);
        boolean taken = state.clans.stream().anyMatch(clan ->
                (exceptId == null || !clan.id.equals(exceptId.toString()))
                        && clan.name.toLowerCase(Locale.ROOT).equals(lookup)
        );
        if (taken) {
            throw new ClanException("A clan with that name already exists.");
        }
    }

    private void pruneInvites(long now) {
        state.invites.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private ClanView view(SavedClan clan) {
        LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        clan.members.forEach((id, name) -> members.put(UUID.fromString(id), name));
        LinkedHashSet<UUID> staff = new LinkedHashSet<>();
        clan.staff.forEach(id -> staff.add(UUID.fromString(id)));
        return new ClanView(
                UUID.fromString(clan.id),
                clan.name,
                UUID.fromString(clan.leader),
                Map.copyOf(members),
                Set.copyOf(staff),
                clan.friendlyFire
        );
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (!VALID_NAME.matcher(normalized).matches()) {
            throw new ClanException("Clan names must be 3-16 letters, numbers, spaces, hyphens, or underscores.");
        }
        return normalized;
    }

    private static String normalizeLookup(String value) {
        return (value == null ? "" : value.trim().replaceAll("\\s+", " ")).toLowerCase(Locale.ROOT);
    }

    private static String cleanPlayerName(String value) {
        String normalized = value == null ? "Player" : value.trim();
        return normalized.isEmpty() ? "Player" : normalized.substring(0, Math.min(normalized.length(), 32));
    }
}
