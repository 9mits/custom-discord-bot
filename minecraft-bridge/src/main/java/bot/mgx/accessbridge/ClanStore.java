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
    static final int DEFAULT_THEME_COLOR = 0xFF9900;
    static final long INVITE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final int FORMAT_VERSION = 1;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Z0-9]{2,6}");

    enum ClanRole {
        LEADER,
        STAFF,
        MEMBER
    }

    record ClanView(
            UUID id,
            String name,
            int themeColor,
            UUID leader,
            Map<UUID, String> members,
            Set<UUID> staff,
            int level,
            Map<String, Integer> vault,
            int memberSlots,
            Map<UUID, Long> donations
    ) {
        ClanRole roleOf(UUID playerId) {
            if (leader.equals(playerId)) {
                return ClanRole.LEADER;
            }
            return staff.contains(playerId) ? ClanRole.STAFF : ClanRole.MEMBER;
        }

        ClanLevel.Perks perks() {
            return ClanLevel.perksFor(level);
        }

        /** The level this clan would buy next, or empty when it holds the last one. */
        Optional<Integer> nextLevel() {
            return level >= ClanLevel.SECRET_LEVEL ? Optional.empty() : Optional.of(level + 1);
        }

        /** What the vault is worth, which is the clan's balance. */
        long balance() {
            return WealthValues.totalOf(vault);
        }

        /** The next roster upgrade, or empty once the clan is at full size. */
        Optional<ClanLevel.MemberTier> nextMemberTier() {
            return ClanLevel.nextMemberTier(ClanLevel.tiersBoughtFor(memberSlots));
        }

        /** Lifetime contributors, largest first, which is how the donor board reads. */
        List<Map.Entry<UUID, Long>> rankedDonors() {
            List<Map.Entry<UUID, Long>> ranked = new ArrayList<>(donations.entrySet());
            ranked.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
            return ranked;
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
        String tag; // Read once when migrating data written by plugin 2.1.0.
        Integer themeColor;
        String leader;
        Map<String, String> members = new LinkedHashMap<>();
        Set<String> staff = new LinkedHashSet<>();
        /** Absent on clans saved before 2.28.0, which read back as an unupgraded clan. */
        Integer level;
        /** Material name to amount. Absent before 2.28.0; anything of value may appear. */
        Map<String, Integer> vault;
        /** Roster size bought so far. Absent reads as {@link ClanLevel#STARTING_MEMBER_SLOTS}. */
        Integer memberSlots;
        /** Player uuid to lifetime value donated. Never reduced by spending. */
        Map<String, Long> donations;
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
        if (rebuildIndex()) {
            persist();
        }
    }

    synchronized ClanView create(UUID owner, String ownerName, String requestedName) throws IOException {
        requireNotInClan(owner, "You already have a clan!");
        String name = normalizeName(requestedName);
        requireUniqueName(name, null);
        SavedClan clan = new SavedClan();
        clan.id = UUID.randomUUID().toString();
        clan.name = name;
        clan.themeColor = DEFAULT_THEME_COLOR;
        clan.leader = owner.toString();
        clan.members.put(owner.toString(), cleanPlayerName(ownerName));
        state.clans.add(clan);
        memberIndex.put(owner, clan);
        persist();
        return view(clan);
    }

    /**
     * Deletes every clan, vault, donation ledger and outstanding invite.
     *
     * <p>For starting the season over after testing. Unlike {@link #disband(UUID)} this
     * asks nobody's permission and refunds nothing, so it is reachable only from the
     * administrative reset.
     *
     * @return how many clans were removed
     */
    synchronized int clearAll() throws IOException {
        int removed = state.clans.size();
        state.clans.clear();
        state.invites.clear();
        memberIndex.clear();
        persist();
        return removed;
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
        requireNotInClan(target, "That player already has a clan.");
        if (clan.members.size() >= slotsOf(clan)) {
            throw new ClanException("Your clan is full at " + slotsOf(clan)
                    + " members. Buy another roster slot to invite more.");
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
        requireNotInClan(player, "You already have a clan!");
        pruneInvites(now);
        SavedInvite invite = state.invites.get(player.toString());
        if (invite == null) {
            throw new ClanException("You do not have an active clan invite.");
        }
        SavedClan clan = requireClan(UUID.fromString(invite.clanId));
        if (clan.members.size() >= slotsOf(clan)) {
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
        if (clan.name.equals(name)) {
            throw new ClanException("Your clan already uses that name.");
        }
        requireUniqueName(name, UUID.fromString(clan.id));
        clan.name = name;
        persist();
        return view(clan);
    }

    synchronized ClanView setThemeColor(UUID actor, String requestedColor) throws IOException {
        SavedClan clan = requireLeader(actor);
        int themeColor = parseThemeColor(requestedColor);
        if (clan.themeColor == themeColor) {
            throw new ClanException("Your clan already uses that theme color.");
        }
        clan.themeColor = themeColor;
        persist();
        return view(clan);
    }

    /**
     * Banks a batch of items and credits the donor.
     *
     * <p>Donations are one-way: there is no withdraw, and disbanding destroys the
     * vault. The ledger it credits is lifetime value and is never reduced by spending,
     * so someone who funded an upgrade keeps the credit for it.
     *
     * @return the value added, so the caller can tell the donor what it was worth
     */
    synchronized long donate(UUID actor, Map<String, Integer> items) throws IOException {
        SavedClan clan = requireClanForMember(actor);
        if (items == null || items.isEmpty()) {
            throw new ClanException("Put something in the donation window first.");
        }
        LinkedHashMap<String, Integer> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            String material = ClanLevel.normalizeMaterial(entry.getKey());
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            if (amount > 0 && ClanLevel.isDonatable(material)) {
                accepted.merge(material, amount, Integer::sum);
            }
        }
        if (accepted.isEmpty()) {
            throw new ClanException("None of that is worth anything to the clan.");
        }
        long value = WealthValues.totalOf(accepted);
        if (clan.vault == null) {
            clan.vault = new LinkedHashMap<>();
        }
        accepted.forEach((material, amount) -> clan.vault.merge(material, amount, Integer::sum));
        if (clan.donations == null) {
            clan.donations = new LinkedHashMap<>();
        }
        clan.donations.merge(actor.toString(), value, Long::sum);
        persist();
        return value;
    }

    /** Buys the next rung of the roster ladder out of the vault. */
    synchronized ClanView upgradeMembers(UUID actor) throws IOException {
        SavedClan clan = requireLeader(actor);
        int slots = slotsOf(clan);
        ClanLevel.MemberTier tier = ClanLevel.nextMemberTier(ClanLevel.tiersBoughtFor(slots))
                .orElseThrow(() -> new ClanException("Your clan already holds every roster slot."));
        Map<String, Integer> missing = ClanLevel.shortfall(clan.vault, List.of(tier.cost()));
        if (!missing.isEmpty()) {
            throw new ClanException("The clan vault is short: " + describe(missing) + ".");
        }
        spend(clan, List.of(tier.cost()));
        clan.memberSlots = tier.slots();
        persist();
        return view(clan);
    }

    /**
     * Spends the vault on the next level. Checked and debited in one persisted step so
     * a clan cannot be charged for an upgrade it does not receive.
     */
    synchronized ClanView upgrade(UUID actor) throws IOException {
        SavedClan clan = requireLeader(actor);
        int current = levelOf(clan);
        if (current >= ClanLevel.SECRET_LEVEL) {
            throw new ClanException("Your clan is already at the highest level.");
        }
        int next = current + 1;
        if (ClanLevel.isSecret(next) && holdsSecretLevel(clan)) {
            throw new ClanException("Another clan already holds that level. Only one ever can.");
        }
        Map<String, Integer> missing = ClanLevel.shortfall(clan.vault, next);
        if (!missing.isEmpty()) {
            throw new ClanException("The clan vault is short: " + describe(missing) + ".");
        }
        spend(clan, ClanLevel.costOf(next));
        clan.level = next;
        persist();
        return view(clan);
    }

    /** Debits a price already checked against the vault. */
    private static void spend(SavedClan clan, List<ClanLevel.Cost> costs) {
        for (ClanLevel.Cost cost : costs) {
            int remaining = clan.vault.getOrDefault(cost.material(), 0) - cost.amount();
            if (remaining > 0) {
                clan.vault.put(cost.material(), remaining);
            } else {
                clan.vault.remove(cost.material());
            }
        }
    }

    private static int slotsOf(SavedClan clan) {
        return clan.memberSlots == null ? ClanLevel.STARTING_MEMBER_SLOTS : clan.memberSlots;
    }

    private static Map<UUID, Long> donationsOf(SavedClan clan) {
        if (clan.donations == null || clan.donations.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, Long> donations = new LinkedHashMap<>();
        clan.donations.forEach((id, value) -> donations.put(UUID.fromString(id), value));
        return Map.copyOf(donations);
    }

    /** Whether a clan other than {@code exclude} already holds the secret level. */
    private boolean holdsSecretLevel(SavedClan exclude) {
        return state.clans.stream()
                .anyMatch(clan -> clan != exclude && levelOf(clan) >= ClanLevel.SECRET_LEVEL);
    }

    private static String describe(Map<String, Integer> materials) {
        return materials.entrySet().stream()
                .map(entry -> entry.getValue() + "x " + ClanLevel.readableMaterial(entry.getKey()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static int levelOf(SavedClan clan) {
        return clan.level == null ? 0 : clan.level;
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
            if (clan.leader.equals(actor.toString())) {
                throw new ClanException("Transfer leadership or disband the clan before leaving.");
            }
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

    /**
     * Disbands the clan. The vault goes with it — donations are one-way, and handing
     * it to the leader would be a withdraw route in all but name. Callers must warn
     * before reaching here; the returned view still carries the balance destroyed so
     * the message can name the figure.
     */
    synchronized ClanView disband(UUID actor) throws IOException {
        SavedClan clan = requireLeader(actor);
        ClanView disbanded = view(clan);
        UUID clanId = UUID.fromString(clan.id);
        state.clans.remove(clan);
        for (String memberId : clan.members.keySet()) {
            memberIndex.remove(UUID.fromString(memberId));
        }
        state.invites.entrySet().removeIf(entry -> entry.getValue().clanId.equals(clanId.toString()));
        persist();
        return disbanded;
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

    private boolean rebuildIndex() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Set<UUID> clanIds = new LinkedHashSet<>();
        boolean migrated = false;
        boolean secretLevelTaken = false;
        try {
            for (SavedClan clan : state.clans) {
                UUID clanId = UUID.fromString(clan.id);
                UUID leader = UUID.fromString(clan.leader);
                if (!clanIds.add(clanId)) {
                    throw new IOException("Clan IDs and names must be unique");
                }
                String identity = clan.tag == null || clan.tag.isBlank()
                        ? clan.name
                        : clan.tag;
                String migratedName = identity == null ? "" : identity.trim().toUpperCase(Locale.ROOT);
                if (!VALID_NAME.matcher(migratedName).matches()) {
                    migratedName = availableLegacyName(clan.name, names);
                }
                if (!names.add(normalizeLookup(migratedName))) {
                    migratedName = availableLegacyName(migratedName, names);
                    names.add(normalizeLookup(migratedName));
                }
                migrated |= !migratedName.equals(clan.name) || clan.tag != null;
                clan.name = migratedName;
                clan.tag = null;
                if (clan.themeColor == null) {
                    clan.themeColor = DEFAULT_THEME_COLOR;
                    migrated = true;
                } else if (clan.themeColor < 0 || clan.themeColor > 0xFFFFFF) {
                    throw new IOException("Clan theme colors must be valid RGB values");
                }
                if (clan.members == null || !clan.members.containsKey(leader.toString())) {
                    throw new IOException("A clan leader must be present in its member list");
                }
                if (clan.members.isEmpty() || clan.members.size() > MAX_MEMBERS) {
                    throw new IOException("Clan member counts must be between 1 and " + MAX_MEMBERS);
                }
                if (clan.memberSlots == null) {
                    // Clans that predate the roster ladder were built under a flat
                    // 25-player cap. Grant them the smallest tier that still holds
                    // everyone, rather than dropping them to 3 and stranding members.
                    clan.memberSlots = ClanLevel.smallestSlotCountHolding(clan.members.size());
                    migrated = true;
                }
                if (clan.members.size() > clan.memberSlots) {
                    throw new IOException("A clan cannot hold more members than its roster allows");
                }
                if (clan.level != null && !ClanLevel.isValid(clan.level)) {
                    throw new IOException("Clan levels must be between 0 and " + ClanLevel.SECRET_LEVEL);
                }
                if (levelOf(clan) >= ClanLevel.SECRET_LEVEL) {
                    if (secretLevelTaken) {
                        throw new IOException("Only one clan may hold the highest level");
                    }
                    secretLevelTaken = true;
                }
                if (clan.vault != null) {
                    for (Map.Entry<String, Integer> entry : clan.vault.entrySet()) {
                        if (!ClanLevel.isDonatable(entry.getKey())) {
                            throw new IOException("The clan vault holds a material it cannot accept");
                        }
                        if (entry.getValue() == null || entry.getValue() < 0) {
                            throw new IOException("Clan vault amounts cannot be negative");
                        }
                    }
                }
                if (clan.memberSlots != null && !ClanLevel.isValidSlotCount(clan.memberSlots)) {
                    throw new IOException("Clan roster sizes must come from the member ladder");
                }
                if (clan.donations != null) {
                    for (Map.Entry<String, Long> entry : clan.donations.entrySet()) {
                        UUID.fromString(entry.getKey());
                        if (entry.getValue() == null || entry.getValue() < 0) {
                            throw new IOException("Clan donation totals cannot be negative");
                        }
                    }
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
        return migrated;
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

    private void requireNotInClan(UUID member, String message) {
        if (memberIndex.containsKey(member)) {
            throw new ClanException(message);
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

    private static String availableLegacyName(String name, Set<String> takenNames) {
        String base = (name == null ? "" : name)
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        if (base.length() < 2) {
            base = "CLAN";
        }
        base = base.substring(0, Math.min(6, base.length()));
        for (int suffix = 0; suffix < 100; suffix++) {
            String candidate = suffix == 0
                    ? base
                    : base.substring(0, Math.min(4, base.length())) + suffix;
            if (!takenNames.contains(normalizeLookup(candidate))) {
                return candidate;
            }
        }
        throw new ClanException("Could not migrate a unique clan name.");
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
                clan.themeColor,
                UUID.fromString(clan.leader),
                Map.copyOf(members),
                Set.copyOf(staff),
                levelOf(clan),
                clan.vault == null ? Map.of() : Map.copyOf(clan.vault),
                slotsOf(clan),
                donationsOf(clan)
        );
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!VALID_NAME.matcher(normalized).matches()) {
            throw new ClanException("Clan names must contain 2-6 letters or numbers.");
        }
        return normalized;
    }

    private static String normalizeLookup(String value) {
        return (value == null ? "" : value.trim().replaceAll("\\s+", " ")).toLowerCase(Locale.ROOT);
    }

    private static int parseThemeColor(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("[0-9A-Fa-f]{6}")) {
            throw new ClanException("Use a named color or a six-digit hex color such as #55FFFF.");
        }
        return Integer.parseInt(normalized, 16);
    }

    private static String cleanPlayerName(String value) {
        String normalized = value == null ? "Player" : value.trim();
        return normalized.isEmpty() ? "Player" : normalized.substring(0, Math.min(normalized.length(), 32));
    }
}
