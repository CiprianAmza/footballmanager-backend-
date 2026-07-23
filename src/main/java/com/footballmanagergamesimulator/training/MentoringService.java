package com.footballmanagergamesimulator.training;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.training.TrainingExtraRepositories.MentoringGroupMemberRepository;
import com.footballmanagergamesimulator.training.TrainingExtraRepositories.MentoringGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

/**
 * Mentoring groups: senior mentors influence the mental development of younger
 * mentees. Fully deterministic (no RNG) and explainable — every estimated
 * delta is reported per mentee with the driving mentor.
 *
 * Mentored attributes are the classic personality/mental set:
 * determination, composure, work rate, teamwork.
 */
@Service
public class MentoringService {

    /** A member is a mentor if senior enough and mentally strong. */
    private static final int MENTOR_MIN_AGE = 28;
    private static final int MENTOR_MIN_DETERMINATION = 13;
    /** A mentee only benefits while young. */
    private static final int MENTEE_MAX_AGE = 23;
    private static final int GROUP_MAX = 6;

    /** name -> attribute accessor/mutator, for the mentored personality attributes. */
    private static final List<String> MENTORED_ATTRS =
            List.of("determination", "composure", "workRate", "teamwork");

    private final MentoringGroupRepository groupRepository;
    private final MentoringGroupMemberRepository memberRepository;
    private final HumanRepository humanRepository;
    private final PlayerSkillsRepository playerSkillsRepository;

    public MentoringService(MentoringGroupRepository groupRepository,
                            MentoringGroupMemberRepository memberRepository,
                            HumanRepository humanRepository,
                            PlayerSkillsRepository playerSkillsRepository) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.humanRepository = humanRepository;
        this.playerSkillsRepository = playerSkillsRepository;
    }

    @Transactional
    public Map<String, Object> saveGroup(long teamId, Long groupId, String name, List<Long> playerIds) {
        if (playerIds == null) playerIds = List.of();
        if (playerIds.size() > GROUP_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A group may hold at most " + GROUP_MAX + " players");
        }
        MentoringGroup group;
        if (groupId != null) {
            group = groupRepository.findById(groupId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
            if (group.getTeamId() != teamId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your team");
            memberRepository.deleteAllByGroupId(group.getId());
        } else {
            group = new MentoringGroup();
            group.setTeamId(teamId);
        }
        group.setName(name == null || name.isBlank() ? "Mentoring Group" : name);
        group = groupRepository.save(group);

        Map<Long, PlayerSkills> skills = skillsByPlayer(playerIds);
        Map<Long, Human> humans = humansByIds(playerIds);
        List<MentoringGroupMember> members = new ArrayList<>();
        for (Long pid : playerIds) {
            MentoringGroupMember m = new MentoringGroupMember();
            m.setGroupId(group.getId());
            m.setTeamId(teamId);
            m.setPlayerId(pid);
            m.setMentor(isMentor(humans.get(pid), skills.get(pid)));
            members.add(m);
        }
        memberRepository.saveAll(members);
        return describeGroup(group, members, humans, skills);
    }

    @Transactional
    public void deleteGroup(long teamId, long groupId) {
        MentoringGroup g = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (g.getTeamId() != teamId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your team");
        memberRepository.deleteAllByGroupId(groupId);
        groupRepository.delete(g);
    }

    public List<Map<String, Object>> listGroups(long teamId) {
        List<MentoringGroup> groups = groupRepository.findAllByTeamId(teamId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MentoringGroup g : groups) {
            List<MentoringGroupMember> members = memberRepository.findAllByGroupId(g.getId());
            List<Long> ids = members.stream().map(MentoringGroupMember::getPlayerId).toList();
            out.add(describeGroup(g, members, humansByIds(ids), skillsByPlayer(ids)));
        }
        return out;
    }

    /**
     * Apply one cadence of mentoring for a whole team: nudge mentee personality
     * attributes toward the best mentor value. Deterministic, +1 per cadence,
     * clamped to the mentor target and to 20. Returns per-player applied deltas.
     */
    @Transactional
    public List<Map<String, Object>> applyTeamMentoring(long teamId) {
        List<MentoringGroup> groups = groupRepository.findAllByTeamId(teamId);
        List<Map<String, Object>> applied = new ArrayList<>();
        List<PlayerSkills> dirty = new ArrayList<>();
        for (MentoringGroup g : groups) {
            List<MentoringGroupMember> members = memberRepository.findAllByGroupId(g.getId());
            List<Long> ids = members.stream().map(MentoringGroupMember::getPlayerId).toList();
            Map<Long, PlayerSkills> skills = skillsByPlayer(ids);
            Map<Long, Human> humans = humansByIds(ids);
            Map<String, Integer> mentorTarget = mentorTargets(members, skills, humans);
            if (mentorTarget.isEmpty()) continue;
            for (MentoringGroupMember m : members) {
                if (m.isMentor()) continue;
                PlayerSkills s = skills.get(m.getPlayerId());
                Human h = humans.get(m.getPlayerId());
                if (s == null || h == null || h.getAge() > MENTEE_MAX_AGE) continue;
                Map<String, Integer> deltas = new LinkedHashMap<>();
                for (String attr : MENTORED_ATTRS) {
                    int cur = getAttr(s, attr);
                    int target = mentorTarget.getOrDefault(attr, cur);
                    if (cur < target && cur < 20) {
                        setAttr(s, attr, cur + 1);
                        deltas.put(attr, 1);
                    }
                }
                if (!deltas.isEmpty()) {
                    dirty.add(s);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("playerId", m.getPlayerId());
                    row.put("groupId", g.getId());
                    row.put("deltas", deltas);
                    applied.add(row);
                }
            }
        }
        if (!dirty.isEmpty()) playerSkillsRepository.saveAll(dirty);
        return applied;
    }

    // ---- helpers ----

    private Map<String, Object> describeGroup(MentoringGroup g, List<MentoringGroupMember> members,
                                              Map<Long, Human> humans, Map<Long, PlayerSkills> skills) {
        Map<String, Integer> target = mentorTargets(members, skills, humans);
        List<Map<String, Object>> memberViews = new ArrayList<>();
        List<Map<String, Object>> estimatedEffects = new ArrayList<>();
        for (MentoringGroupMember m : members) {
            Human h = humans.get(m.getPlayerId());
            Map<String, Object> mv = new LinkedHashMap<>();
            mv.put("playerId", m.getPlayerId());
            mv.put("name", h == null ? "?" : h.getName());
            mv.put("age", h == null ? 0 : h.getAge());
            mv.put("mentor", m.isMentor());
            memberViews.add(mv);

            if (!m.isMentor() && h != null && h.getAge() <= MENTEE_MAX_AGE && !target.isEmpty()) {
                PlayerSkills s = skills.get(m.getPlayerId());
                if (s != null) {
                    Map<String, Object> eff = new LinkedHashMap<>();
                    Map<String, Integer> deltas = new LinkedHashMap<>();
                    for (String attr : MENTORED_ATTRS) {
                        int cur = getAttr(s, attr);
                        int t = target.getOrDefault(attr, cur);
                        deltas.put(attr, (cur < t && cur < 20) ? 1 : 0);
                    }
                    eff.put("playerId", m.getPlayerId());
                    eff.put("name", h.getName());
                    eff.put("perCadenceDeltas", deltas);
                    eff.put("explain", explainMentee(h.getName(), deltas));
                    estimatedEffects.add(eff);
                }
            }
        }
        long mentorCount = members.stream().filter(MentoringGroupMember::isMentor).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupId", g.getId());
        out.put("teamId", g.getTeamId());
        out.put("name", g.getName());
        out.put("members", memberViews);
        out.put("mentorCount", mentorCount);
        out.put("menteeCount", members.size() - mentorCount);
        out.put("estimatedEffects", estimatedEffects);
        if (mentorCount == 0) {
            out.put("warning", "No mentor in this group — add a senior (age ≥ " + MENTOR_MIN_AGE
                    + ", determination ≥ " + MENTOR_MIN_DETERMINATION + ") for any effect.");
        }
        return out;
    }

    private String explainMentee(String name, Map<String, Integer> deltas) {
        List<String> gains = new ArrayList<>();
        for (Map.Entry<String, Integer> e : deltas.entrySet()) {
            if (e.getValue() > 0) gains.add("+" + e.getValue() + " " + e.getKey());
        }
        if (gains.isEmpty()) return name + " already matches the group's mentors — no further gain.";
        return name + " gains " + String.join(", ", gains) + " per training cadence from the group's mentors.";
    }

    /** Best (max) mentor value per mentored attribute across the group's mentors. */
    private Map<String, Integer> mentorTargets(List<MentoringGroupMember> members,
                                               Map<Long, PlayerSkills> skills,
                                               Map<Long, Human> humans) {
        Map<String, Integer> target = new LinkedHashMap<>();
        boolean anyMentor = false;
        for (MentoringGroupMember m : members) {
            if (!m.isMentor()) continue;
            PlayerSkills s = skills.get(m.getPlayerId());
            if (s == null) continue;
            anyMentor = true;
            for (String attr : MENTORED_ATTRS) {
                target.merge(attr, getAttr(s, attr), Math::max);
            }
        }
        return anyMentor ? target : Map.of();
    }

    private boolean isMentor(Human h, PlayerSkills s) {
        if (h == null || s == null) return false;
        return h.getAge() >= MENTOR_MIN_AGE && s.getDetermination() >= MENTOR_MIN_DETERMINATION;
    }

    private Map<Long, PlayerSkills> skillsByPlayer(Collection<Long> ids) {
        Map<Long, PlayerSkills> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) return map;
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) {
            map.put(s.getPlayerId(), s);
        }
        return map;
    }

    private Map<Long, Human> humansByIds(Collection<Long> ids) {
        Map<Long, Human> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) return map;
        for (Human h : humanRepository.findAllById(ids)) map.put(h.getId(), h);
        return map;
    }

    private int getAttr(PlayerSkills s, String attr) {
        return switch (attr) {
            case "determination" -> s.getDetermination();
            case "composure" -> s.getComposure();
            case "workRate" -> s.getWorkRate();
            case "teamwork" -> s.getTeamwork();
            default -> 0;
        };
    }

    private void setAttr(PlayerSkills s, String attr, int val) {
        switch (attr) {
            case "determination" -> s.setDetermination(val);
            case "composure" -> s.setComposure(val);
            case "workRate" -> s.setWorkRate(val);
            case "teamwork" -> s.setTeamwork(val);
            default -> { }
        }
    }
}
