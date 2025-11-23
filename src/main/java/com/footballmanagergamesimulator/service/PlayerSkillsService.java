package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Service
public class PlayerSkillsService {

    public static final HashMap<String, Function<PlayerSkills, Long>> GETTER_MAP = new HashMap<>();
    public static final HashMap<String, BiConsumer<PlayerSkills, Long>> SETTER_MAP = new HashMap<>();

    static {
            // Adding getters for long values
            GETTER_MAP.put("skill1", PlayerSkills::getSkill1);
            GETTER_MAP.put("skill2", PlayerSkills::getSkill2);
            GETTER_MAP.put("skill3", PlayerSkills::getSkill3);
            GETTER_MAP.put("skill4", PlayerSkills::getSkill4);
            GETTER_MAP.put("skill5", PlayerSkills::getSkill5);
            GETTER_MAP.put("skill6", PlayerSkills::getSkill6);
            GETTER_MAP.put("skill7", PlayerSkills::getSkill7);
            GETTER_MAP.put("skill8", PlayerSkills::getSkill8);
            GETTER_MAP.put("skill9", PlayerSkills::getSkill9);
            GETTER_MAP.put("skill10", PlayerSkills::getSkill10);

            // Adding setters for long values
            SETTER_MAP.put("skill1", PlayerSkills::setSkill1);
            SETTER_MAP.put("skill2", PlayerSkills::setSkill2);
            SETTER_MAP.put("skill3", PlayerSkills::setSkill3);
            SETTER_MAP.put("skill4", PlayerSkills::setSkill4);
            SETTER_MAP.put("skill5", PlayerSkills::setSkill5);
            SETTER_MAP.put("skill6", PlayerSkills::setSkill6);
            SETTER_MAP.put("skill7", PlayerSkills::setSkill7);
            SETTER_MAP.put("skill8", PlayerSkills::setSkill8);
            SETTER_MAP.put("skill9", PlayerSkills::setSkill9);
            SETTER_MAP.put("skill10", PlayerSkills::setSkill10);
    }

}
