package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PersonalAccountingService {

    private final PersonalAccountRepository accountRepository;
    private final PersonalLedgerEntryRepository ledgerRepository;
    private final OwnedAssetRepository ownedAssetRepository;
    private final HumanRepository humanRepository;
    private final RegentEconomyProperties properties;

    public PersonalAccountingService(PersonalAccountRepository accountRepository,
                                     PersonalLedgerEntryRepository ledgerRepository,
                                     OwnedAssetRepository ownedAssetRepository,
                                     HumanRepository humanRepository,
                                     RegentEconomyProperties properties) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.ownedAssetRepository = ownedAssetRepository;
        this.humanRepository = humanRepository;
        this.properties = properties;
    }

    /**
     * Merges the economy identity created at registration with the economy identity of an
     * existing AI-controlled Human. The user profile is the surviving canonical profile.
     *
     * <p>This is deliberately an accounting merge rather than a cascading delete: a manager
     * may already own assets or have career earnings before a user takes control. All ledger
     * rows and assets remain auditable, while their account/profile ownership is moved to the
     * surviving identity.</p>
     */
    @Transactional
    public void mergeProfiles(PersonProfile survivingProfile, PersonProfile absorbedProfile,
                              Integer ownerUserId, Long ownerHumanId) {
        if (survivingProfile.getId() == absorbedProfile.getId()) {
            synchronizeOwners(survivingProfile.getId(), ownerUserId, ownerHumanId);
            return;
        }

        PersonalAccount surviving = accountRepository.findByProfileIdForUpdate(survivingProfile.getId())
                .orElse(null);
        PersonalAccount absorbed = accountRepository.findByProfileIdForUpdate(absorbedProfile.getId())
                .orElse(null);

        if (absorbed == null) {
            if (surviving != null) {
                surviving.setOwnerUserId(ownerUserId);
                surviving.setOwnerHumanId(ownerHumanId);
                accountRepository.saveAndFlush(surviving);
                mirror(surviving);
            }
            return;
        }

        // Release unique owner columns before assigning them to the surviving account.
        absorbed.setOwnerUserId(null);
        absorbed.setOwnerHumanId(null);
        accountRepository.saveAndFlush(absorbed);

        if (surviving == null) {
            reparentAccount(absorbed, survivingProfile.getId(), ownerUserId, ownerHumanId);
            mirror(absorbed);
            return;
        }

        surviving.setCashBalance(safeAdd(surviving.getCashBalance(), absorbed.getCashBalance()));
        surviving.setLifetimeCareerEarnings(safeAdd(
                surviving.getLifetimeCareerEarnings(), absorbed.getLifetimeCareerEarnings()));
        surviving.setRealizedInvestmentGain(safeAdd(
                surviving.getRealizedInvestmentGain(), absorbed.getRealizedInvestmentGain()));
        surviving.setOwnerUserId(ownerUserId);
        surviving.setOwnerHumanId(ownerHumanId);

        mergeLedger(absorbed, surviving, survivingProfile.getId(), absorbedProfile.getId());
        mergeAssets(absorbed, surviving, survivingProfile.getId(), absorbedProfile.getId());
        accountRepository.saveAndFlush(surviving);
        accountRepository.delete(absorbed);
        accountRepository.flush();
        mirror(surviving);
    }

    private void synchronizeOwners(long profileId, Integer ownerUserId, Long ownerHumanId) {
        accountRepository.findByProfileIdForUpdate(profileId).ifPresent(account -> {
            account.setOwnerUserId(ownerUserId);
            account.setOwnerHumanId(ownerHumanId);
            accountRepository.saveAndFlush(account);
            mirror(account);
        });
    }

    private void reparentAccount(PersonalAccount account, long profileId,
                                 Integer ownerUserId, Long ownerHumanId) {
        for (PersonalLedgerEntry entry : ledgerRepository
                .findAllByAccountIdOrderByCreatedAtAscIdAsc(account.getId())) {
            entry.setProfileId(profileId);
        }
        for (OwnedAsset asset : ownedAssetRepository.findAllByAccountIdOrderByIdAsc(account.getId())) {
            asset.setProfileId(profileId);
        }
        ledgerRepository.flush();
        ownedAssetRepository.flush();
        account.setProfileId(profileId);
        account.setOwnerUserId(ownerUserId);
        account.setOwnerHumanId(ownerHumanId);
        accountRepository.saveAndFlush(account);
    }

    private void mergeLedger(PersonalAccount absorbed, PersonalAccount surviving,
                             long survivingProfileId, long absorbedProfileId) {
        List<PersonalLedgerEntry> survivingEntries = new ArrayList<>(ledgerRepository
                .findAllByAccountIdOrderByCreatedAtAscIdAsc(surviving.getId()));
        Set<String> usedKeys = new HashSet<>();
        survivingEntries.forEach(entry -> usedKeys.add(entry.getIdempotencyKey()));

        List<PersonalLedgerEntry> absorbedEntries = new ArrayList<>(ledgerRepository
                .findAllByAccountIdOrderByCreatedAtAscIdAsc(absorbed.getId()));
        for (PersonalLedgerEntry entry : absorbedEntries) {
            entry.setAccountId(surviving.getId());
            entry.setProfileId(survivingProfileId);
            entry.setIdempotencyKey(uniqueMergeKey(entry.getIdempotencyKey(), usedKeys,
                    absorbedProfileId, entry.getId(), 160));
            survivingEntries.add(entry);
        }
        survivingEntries.sort(Comparator.comparingLong(PersonalLedgerEntry::getCreatedAt)
                .thenComparingLong(PersonalLedgerEntry::getId));
        long runningBalance = 0;
        for (PersonalLedgerEntry entry : survivingEntries) {
            runningBalance = safeAdd(runningBalance, entry.getSignedAmount());
            entry.setBalanceAfter(runningBalance);
        }
        if (runningBalance != surviving.getCashBalance()) {
            throw new IllegalStateException("Merged personal account does not reconcile");
        }
        ledgerRepository.saveAllAndFlush(survivingEntries);
    }

    private void mergeAssets(PersonalAccount absorbed, PersonalAccount surviving,
                             long survivingProfileId, long absorbedProfileId) {
        List<OwnedAsset> survivingAssets = ownedAssetRepository.findAllByAccountIdOrderByIdAsc(surviving.getId());
        Set<String> purchaseKeys = new HashSet<>();
        Set<String> saleKeys = new HashSet<>();
        for (OwnedAsset asset : survivingAssets) {
            purchaseKeys.add(asset.getPurchaseIdempotencyKey());
            if (asset.getSaleIdempotencyKey() != null) saleKeys.add(asset.getSaleIdempotencyKey());
        }

        List<OwnedAsset> absorbedAssets = ownedAssetRepository.findAllByAccountIdOrderByIdAsc(absorbed.getId());
        for (OwnedAsset asset : absorbedAssets) {
            asset.setAccountId(surviving.getId());
            asset.setProfileId(survivingProfileId);
            asset.setPurchaseIdempotencyKey(uniqueMergeKey(asset.getPurchaseIdempotencyKey(), purchaseKeys,
                    absorbedProfileId, asset.getId(), 160));
            if (asset.getSaleIdempotencyKey() != null) {
                asset.setSaleIdempotencyKey(uniqueMergeKey(asset.getSaleIdempotencyKey(), saleKeys,
                        absorbedProfileId, asset.getId(), 160));
            }
        }
        ownedAssetRepository.saveAllAndFlush(absorbedAssets);
    }

    private static String uniqueMergeKey(String original, Set<String> used,
                                         long sourceProfileId, long rowId, int maxLength) {
        if (used.add(original)) return original;
        String suffix = ":MERGED:" + sourceProfileId + ":" + rowId;
        int prefixLength = Math.max(0, maxLength - suffix.length());
        String candidate = original.substring(0, Math.min(original.length(), prefixLength)) + suffix;
        if (!used.add(candidate)) {
            throw new IllegalStateException("Unable to preserve an idempotency key during profile merge");
        }
        return candidate;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Merged personal wealth exceeds supported range");
        }
    }

    public long registrationStartingWealth(CareerRole role, Long requested) {
        RegentEconomyProperties.Economy config = properties.getEconomy();
        if (role == CareerRole.MANAGER) {
            if (requested != null) {
                throw new IllegalArgumentException("startingWealth is available only for chairman careers");
            }
            return config.getManagerStartingWealth();
        }
        long value = requested == null ? config.getChairmanStartingWealthDefault() : requested;
        if (value < config.getChairmanStartingWealthMin() || value > config.getChairmanStartingWealthMax()) {
            throw new IllegalArgumentException("Chairman starting wealth must be between "
                    + config.getChairmanStartingWealthMin() + " and " + config.getChairmanStartingWealthMax());
        }
        return value;
    }

    @Transactional
    public PersonalAccount openRegistrationAccount(PersonProfile profile, long startingWealth, String key) {
        return openAccount(profile, startingWealth, 0, LedgerEntryType.STARTING_CAPITAL,
                "REGISTRATION:" + key, "REGISTRATION:" + key);
    }

    @Transactional
    public PersonalAccount ensureAccount(PersonProfile profile) {
        return accountRepository.findByProfileId(profile.getId()).orElseGet(() -> {
            long balance = 0;
            long earnings = 0;
            if (profile.getHumanId() != null) {
                Human human = humanRepository.findById(profile.getHumanId()).orElse(null);
                if (human != null) {
                    balance = Math.max(0, human.getWealth());
                    earnings = Math.max(0, human.getCareerEarnings());
                }
            } else if (profile.getCareerType() == com.footballmanagergamesimulator.person.CareerType.CHAIRMAN) {
                balance = properties.getEconomy().getChairmanStartingWealthDefault();
            }
            return openAccount(profile, balance, earnings, LedgerEntryType.MIGRATION_OPENING,
                    "PROFILE-MIGRATION:" + profile.getId(), "PROFILE-MIGRATION:" + profile.getId());
        });
    }

    /** Startup/import batch path; avoids one account and one Human query for every AI person. */
    @Transactional
    public void ensureAccounts(List<PersonProfile> profiles) {
        Set<Long> existingProfiles = new HashSet<>();
        accountRepository.findAll().forEach(account -> existingProfiles.add(account.getProfileId()));
        List<PersonProfile> missingProfiles = profiles.stream()
                .filter(profile -> !existingProfiles.contains(profile.getId())).toList();
        if (missingProfiles.isEmpty()) return;
        Map<Long, Human> humans = new HashMap<>();
        humanRepository.findAll().forEach(human -> humans.put(human.getId(), human));
        List<PersonalAccount> created = new ArrayList<>();
        Map<PersonalAccount, long[]> openings = new HashMap<>();
        for (PersonProfile profile : missingProfiles) {
            long balance = 0;
            long earnings = 0;
            Human human = profile.getHumanId() == null ? null : humans.get(profile.getHumanId());
            if (human != null) {
                balance = Math.max(0, human.getWealth());
                earnings = Math.max(0, human.getCareerEarnings());
            } else if (profile.getCareerType() == com.footballmanagergamesimulator.person.CareerType.CHAIRMAN) {
                balance = properties.getEconomy().getChairmanStartingWealthDefault();
            }
            PersonalAccount account = new PersonalAccount();
            account.setProfileId(profile.getId());
            account.setOwnerUserId(profile.getUserId());
            account.setOwnerHumanId(profile.getHumanId());
            account.setCashBalance(balance);
            account.setLifetimeCareerEarnings(earnings);
            account.setRealizedInvestmentGain(0);
            created.add(account);
            openings.put(account, new long[]{balance, earnings});
        }
        accountRepository.saveAllAndFlush(created);
        List<PersonalLedgerEntry> ledgerEntries = new ArrayList<>();
        for (PersonalAccount account : created) {
            long[] opening = openings.get(account);
            if (opening[0] == 0 && opening[1] == 0) continue;
            String key = "PROFILE-MIGRATION:" + account.getProfileId();
            ledgerEntries.add(baseEntry(account, LedgerEntryType.MIGRATION_OPENING,
                    opening[0], opening[1], opening[0], 0, 0, key, key, "Migrated opening balance"));
        }
        ledgerRepository.saveAll(ledgerEntries);
    }

    private PersonalAccount openAccount(PersonProfile profile, long balance, long earnings,
                                        LedgerEntryType type, String correlationId, String idempotencyKey) {
        if (balance < 0 || earnings < 0) throw new IllegalArgumentException("Opening values cannot be negative");
        PersonalAccount existing = accountRepository.findByProfileId(profile.getId()).orElse(null);
        if (existing != null) return existing;

        PersonalAccount account = new PersonalAccount();
        account.setProfileId(profile.getId());
        account.setOwnerUserId(profile.getUserId());
        account.setOwnerHumanId(profile.getHumanId());
        account.setCashBalance(balance);
        account.setLifetimeCareerEarnings(earnings);
        account.setRealizedInvestmentGain(0);
        account = accountRepository.saveAndFlush(account);

        if (balance != 0 || earnings != 0) {
            PersonalLedgerEntry entry = baseEntry(account, type, balance, earnings,
                    balance, 0, 0, correlationId, idempotencyKey,
                    type == LedgerEntryType.STARTING_CAPITAL ? "Starting capital" : "Migrated opening balance");
            ledgerRepository.save(entry);
        }
        mirror(account);
        return account;
    }

    @Transactional
    public PostingResult post(long profileId, LedgerEntryType type, long signedAmount,
                              long careerEarningsDelta, int season, int day,
                              String correlationId, String idempotencyKey,
                              Long counterpartTeamId, Long counterpartAssetId, String description) {
        PersonalAccount account = accountRepository.findByProfileIdForUpdate(profileId)
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        return postLocked(account, type, signedAmount, careerEarningsDelta, season, day,
                correlationId, idempotencyKey, counterpartTeamId, counterpartAssetId, description);
    }

    PostingResult postLocked(PersonalAccount account, LedgerEntryType type, long signedAmount,
                             long careerEarningsDelta, int season, int day,
                             String correlationId, String idempotencyKey,
                             Long counterpartTeamId, Long counterpartAssetId, String description) {
        validateText("correlationId", correlationId, 120);
        validateText("idempotencyKey", idempotencyKey, 160);
        validateText("description", description, 300);
        PersonalLedgerEntry existing = ledgerRepository
                .findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey).orElse(null);
        if (existing != null) {
            if (existing.getEntryType() != type
                    || existing.getSignedAmount() != signedAmount
                    || existing.getCareerEarningsDelta() != careerEarningsDelta
                    || existing.getSeasonNumber() != season
                    || existing.getGameDay() != day
                    || !Objects.equals(existing.getCorrelationId(), correlationId)
                    || !Objects.equals(existing.getCounterpartTeamId(), counterpartTeamId)
                    || !Objects.equals(existing.getCounterpartAssetId(), counterpartAssetId)
                    || !Objects.equals(existing.getDescription(), description)) {
                throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for a different operation");
            }
            return new PostingResult(account, existing, true);
        }

        long newBalance;
        long newEarnings;
        try {
            newBalance = Math.addExact(account.getCashBalance(), signedAmount);
            newEarnings = Math.addExact(account.getLifetimeCareerEarnings(), careerEarningsDelta);
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Money operation exceeds supported range");
        }
        if (newBalance < 0) {
            throw new EconomyConflictException("INSUFFICIENT_FUNDS", "Personal balance cannot become negative");
        }
        if (newEarnings < 0) {
            throw new EconomyConflictException("NEGATIVE_CAREER_EARNINGS", "Career earnings cannot become negative");
        }

        account.setCashBalance(newBalance);
        account.setLifetimeCareerEarnings(newEarnings);
        accountRepository.save(account);
        PersonalLedgerEntry entry = baseEntry(account, type, signedAmount, careerEarningsDelta,
                newBalance, season, day, correlationId, idempotencyKey, description);
        entry.setCounterpartTeamId(counterpartTeamId);
        entry.setCounterpartAssetId(counterpartAssetId);
        ledgerRepository.save(entry);
        mirror(account);
        return new PostingResult(account, entry, false);
    }

    @Transactional(readOnly = true)
    public void assertReconciled(long accountId) {
        PersonalAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        long signedSum = ledgerRepository.sumSignedAmount(accountId);
        long earningsSum = ledgerRepository.sumCareerEarnings(accountId);
        if (signedSum != account.getCashBalance() || earningsSum != account.getLifetimeCareerEarnings()) {
            throw new IllegalStateException("Personal account does not reconcile with its ledger");
        }
    }

    private PersonalLedgerEntry baseEntry(PersonalAccount account, LedgerEntryType type,
                                          long amount, long earnings, long balanceAfter,
                                          int season, int day, String correlationId,
                                          String idempotencyKey, String description) {
        PersonalLedgerEntry entry = new PersonalLedgerEntry();
        entry.setAccountId(account.getId());
        entry.setProfileId(account.getProfileId());
        entry.setSeasonNumber(season);
        entry.setGameDay(day);
        entry.setEntryType(type);
        entry.setSignedAmount(amount);
        entry.setCareerEarningsDelta(earnings);
        entry.setBalanceAfter(balanceAfter);
        entry.setCorrelationId(correlationId);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setDescription(description);
        entry.setCreatedAt(System.currentTimeMillis());
        return entry;
    }

    private void mirror(PersonalAccount account) {
        if (account.getOwnerHumanId() == null) return;
        humanRepository.findById(account.getOwnerHumanId()).ifPresent(human -> {
            human.setWealth(account.getCashBalance());
            human.setCareerEarnings(account.getLifetimeCareerEarnings());
            humanRepository.save(human);
        });
    }

    private static void validateText(String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1 to " + max + " characters");
        }
    }

    public record PostingResult(PersonalAccount account, PersonalLedgerEntry entry, boolean replayed) { }
}
