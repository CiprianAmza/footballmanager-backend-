package match2d.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Toate pantele prin care un rating intra in joc, intr-un singur loc.
 *
 * <p>Numele unui camp e {@code mecanism_cheie}; partea de dupa mecanism care e numele unui atribut
 * (sau incepe cu el) e panta acelui atribut, restul sunt constantele formulei. Ratingul intra
 * normalizat (1..20 -> 0.15..0.85), deci o panta de 1.3 la {@code pace} inseamna 1.3 m/s intre
 * un jucator de 1 si unul de 20, inmultit cu 0.7.
 *
 * <p>Campurile nu sunt finale: {@link #set(String, double)} schimba o pondere fara editat cod
 * ({@code "speed.pace"}), pentru masuratori.
 */
public final class Wt {
    // ---- fizic
    public static double speed_base = 6.4, speed_pace = 1.3;
    public static double accel_base = 4.3, accel_acceleration = 1.8;
    public static double reaction_base = 0.42, reaction_anticipation = 0.20;
    public static double jog_base = 0.55, jog_workRate = 0.2;
    public static double carry_speed_base = 0.74, carry_speed_dribbling = 0.22;

    // ---- oboseala
    public static double fatigue_drain = 0.00035, fatigue_flat = 0.195, fatigue_ref_speed = 8.0,
            fatigue_stamina_base = 1.45, fatigue_stamina = 1.0;
    public static double recovery_rest = 0.0004, recovery_naturalFitness_base = 0.5,
            recovery_naturalFitness = 1.0, recovery_half_time_base = 0.15,
            recovery_naturalFitness_half_time = 0.35;
    public static double tired_threshold = 0.7, tired_speed = 0.06, tired_accel = 0.09,
            tired_jog = 0.20, tired_press = 0.8;
    public static double track_back_base = 19.55, track_back_workRate = 10.0;
    public static double trailing_determination = 0.2, trailing_determination_from = 0.5;
    public static double chase_lost_base = 0.5, chase_lost_determination = 4.0,
            chase_lost_radius = 8.0, chase_lost_max_pressers = 3;

    // ---- dueluri
    public static double tackle_tackling = 0.40, tackle_ballRecovery = 0.15,
            tackle_anticipation = 0.20, tackle_strength = 0.15, tackle_aggression = 0.10;
    public static double dribble_dribbling = 0.45, dribble_agility = 0.20, dribble_balance = 0.15,
            dribble_technique = 0.10, dribble_strength = 0.10;
    public static double aerial_heading = 0.40, aerial_jumpingReach = 0.35, aerial_strength = 0.15,
            aerial_bravery = 0.10;
    public static double duel_slope = 2.8, duel_front = 0.5, duel_bias = 0.1, duel_belief_bias = 0.2;
    public static double tackle_eagerness_base = 0.04, tackle_eagerness_aggression = 0.10,
            tackle_eagerness_close = 1.6;

    // ---- pase
    public static double pass_error_passing = 0.085, pass_error_offset = 0.001,
            pass_error_floor = 0.004, pass_error_pressure = 0.9, pass_error_composure = 0.5;
    public static double loft_error_passing = 0.5, loft_error_technique = 0.25,
            loft_error_crossing = 0.25, loft_error_slope = 0.105, loft_error_factor = 1.35;
    public static double pass_weight_base = 0.03, pass_weight_technique = 0.07,
            pass_weight_loft_base = 0.03, pass_weight_technique_loft = 0.08;
    public static double pass_speed_base = 0.80, pass_speed_passing = 0.36;
    public static double vision_base = 24.0, vision_vision = 40.0;
    public static double receive_belief_loft_base = 0.80, receive_belief_through_loft_base = 0.78,
            receive_belief_firstTouch = 0.14;

    // ---- preluare
    public static double control_time_base = 0.2, control_time_firstTouch = 0.4;
    public static double control_base = 0.99, control_speed_from = 9.0, control_firstTouch = 0.05,
            control_firstTouch_loft = 0.10, control_technique = 0.06;
    public static double intercept_base = 0.93, intercept_speed_from = 7.0,
            intercept_per_speed = 0.035, intercept_anticipation_from = 1.4, intercept_ready = 0.05,
            intercept_unready = 0.15;
    public static double loose_control_base = 0.95, loose_control_speed_from = 8.0,
            loose_control_per_speed = 0.03, loose_control_firstTouch_from = 1.3;
    public static double gk_claim_base = 0.80, gk_claim_handling = 0.19;

    // ---- decizie
    public static double decision_noise_base = 0.04, decision_noise_decisions = 0.30,
            decision_noise_composure = 1.0;
    public static double decision_time_base = 0.3, decision_time_decisions = 0.45;
    public static double offside_judgement_base = 0.3, offside_judgement_decisions = 1.5;
    public static double carry_bias_base = 0.75, carry_bias_dribbling = 0.35,
            carry_bias_flair_base = 0.5, carry_bias_flair = 0.5;
    public static double shot_belief_finishing_base = 0.55, shot_belief_finishing = 0.9,
            shot_belief_longShots_base = 0.4, shot_belief_longShots = 1.0,
            shot_belief_pressure = 0.45;

    // ---- fara minge
    public static double support_radius = 3.5, support_offTheBall = 5.5, support_threat = 6.0,
            support_offTheBall_base = 0.5;
    public static double marking_gap = 2.7, marking_marking_gap = 0.0, marking_gap_deep = 1.9,
            marking_marking_gap_deep = 0.0, marking_pull = 0.575, marking_marking = 0.0,
            marking_pull_deep = 0.2, marking_non_defender = 0.6;
    public static double gk_position_range_base = 5.0, gk_position_commandOfArea = 11.0,
            gk_position_defending = 0.7, gk_position_oneOnOnes_base = 0.30,
            gk_position_oneOnOnes = 0.25;
    public static double corner_taker_corners = 1, corner_taker_crossing = 1;

    // ---- aparare si atentie (etapa 4); pantele "_from" sunt centrate: jucatorul mediu (0.5) e neutru
    /**
     * positioning: cand mingea e aproape de poarta lui si nu are om de tinut, se trage spre linia
     * minge - poarta (cat din distanta pana la ea). Cine tine un om ramane cu el: cand acopereau
     * toti, atacantii ramaneau liberi si apararea nu castiga nimic.
     */
    public static double cover_base = -0.35, cover_positioning = 1.0, cover_from = 40.0,
            cover_full = 25.0, cover_reach = 14.0;
    /**
     * marking: reactioneaza mai repede la pasa spre omul pe care il tine (cel de langa primitor),
     * o intercepteaza mai des si, daca pasa ajunge, ii strica preluarea.
     */
    public static double mark_react_marking = 0.8, mark_react_marking_from = 0.5,
            mark_react_radius = 5.0, mark_intercept_marking = 0.6, mark_touch_marking = 0.5;
    /** ballRecovery: porneste mai repede spre mingea libera si o stapaneste mai des. */
    public static double loose_react_ballRecovery = 0.25, loose_react_ballRecovery_from = 0.5,
            loose_control_ballRecovery = 0.12;
    /**
     * teamwork: vine spre purtatorul presat (cat din distanta), daca nu e cu mult in fata mingii,
     * si isi cauta loc cu culoar de pasa liber (cat cantareste culoarul la alegerea pozitiei).
     * Incercate si scoase, fiindca stricau: iesirea din zona la dublaj (adversarul tragea mai
     * mult) si zgomot de decizie mai mic doar la pase (muta alegerea spre conducerea mingii).
     */
    public static double help_base = 0.0, help_teamwork = 0.4, help_keep = 8.0, help_range = 22.0,
            help_ahead = 8.0;
    public static double support_lane_base = 0.7, support_lane_teamwork = 0.8,
            support_lane_teamwork_from = 0.5;
    /** concentration: scapari de atentie (sansa la fiecare gandire de pozitie), mai dese spre finalul reprizei. */
    public static double lapse_base = 0.0005, lapse_concentration = 0.005, lapse_late = 1.5,
            lapse_time = 2.0, lapse_react = 0.5;
    /** leadership: capitanul (cel mai mare leadership) scade efectul presiunii la cei din jurul lui. */
    public static double captain_leadership = 1.0, captain_leadership_from = 0.5, captain_radius = 25.0;

    // ---- echilibrul formatiilor (recalibrare; vezi Runs)
    /**
     * Alergarea in careu. Linia de atac a formatiei: un atacant valoreaza 1, un mijlocas ofensiv
     * {@code run_second}, o extrema {@code run_wide}. Cat lipseste pana la {@code run_target} se
     * completeaza din mers: cu mingea dincolo de {@code run_from - run_slope x cat lipseste} metri
     * urca cei mai avansati care nu sunt fundasi si nu sunt cu mai mult de {@code run_reach} m in
     * urma mingii. Tinta e la {@code run_depth} m de linia portii. Alergarea tine pana se pierde
     * mingea sau jocul se intoarce cu {@code run_keep} m.
     */
    public static double run_target = 2.5, run_second = 0.45, run_wide = 0.23, run_from = 105.0,
            run_slope = 35.0, run_keep = 8.0, run_depth = 9.0, run_reach = 30.0;
    /**
     * Pe linia de atac (atacanti si extreme) stau cel mult {@code hold_line} oameni; cel in plus e
     * tinut cu {@code hold_depth} metri mai jos cand echipa ataca.
     */
    public static double hold_line = 3, hold_depth = 13.0;
    /**
     * Coborarea atacantilor: cand echipa se apara, sus raman doar cei mai avansati
     * {@code drop_stay_up} jucatori. Ceilalti coboara spre linia mijlocasilor ({@code drop_deep},
     * cu zona pana la {@code drop_box}) pe masura ce mingea vine de la {@code drop_from} metri de
     * poarta lor spre {@code drop_from - drop_blend}.
     */
    public static double drop_stay_up = 2, drop_from = 55.0, drop_blend = 20.0, drop_deep = 21.0,
            drop_box = 14.0;

    // ---- sut
    public static double shot_aim_base = 0.45, shot_aim_composure = 1.1;
    public static double shot_error_finishing = 1.0, shot_error_longShots = 1.0,
            shot_error_floor = 0.035, shot_error_slope = 0.58, shot_error_offset = 0.01,
            shot_error_pressure = 0.8, shot_error_composure = 1.6;
    public static double shot_power_base = 21.0, shot_power_technique = 6.0,
            shot_power_strength = 4.0;
    public static double shot_height_base = 0.01, shot_height_technique = 0.17;
    /**
     * Blocajul: cine e la cel mult {@code block_reach} metri de traiectoria sutului (plus pana la
     * {@code block_reach_near} langa sutor, unde se poate arunca in fata mingii) il poate bloca.
     */
    public static double block_base = 0.90, block_slope = 0.30, block_bravery = 1.0,
            block_positioning = 1.0, block_reach = 2.8, block_reach_near = 3.6;
    public static double special_shot_longShots_from = 20;

    // ---- portar
    public static double save_oneOnOnes_blend = 0.5, save_react_base = 0.278, save_reflexes = 0.126,
            save_dive_base = 4.7, save_agility = 4.3, save_reach = 1.5, save_oneOnOnes = 0.7,
            save_oneOnOnes_from = 0.15, save_slope = 2.2, save_sure_base = 0.93,
            save_handling = 0.135, save_catch_base = 0.30, save_handling_catch = 0.5,
            save_speed_from = 22.0, save_per_speed = 0.012;

    // ---- faze fixe, etapa A (vezi SetPiece); pantele sunt centrate pe jucatorul mediu (0.5)
    /**
     * Faultul din duel: sansa la fiecare deposedare incercata. Mai des cu aggression mare, mai rar
     * cu tackling mare; din spate de {@code foul_behind} ori mai des, in careul propriu de
     * {@code foul_box} ori (fundasul e mai atent), iar cine are galben de {@code foul_booked} ori.
     */
    public static double foul_base = 0.22, foul_aggression = 1.2, foul_tackling = 0.9,
            foul_behind = 1.8, foul_box = 0.155, foul_booked = 0.6, foul_max = 0.6;
    /**
     * Cartonasul galben dupa un fault: din spate si langa poarta (sub {@code card_danger_dist} m
     * de centrul portii, cel mult {@code card_danger_width} m de ax) e mai probabil.
     */
    public static double card_base = 0.125, card_aggression = 0.8, card_behind = 1.8,
            card_danger = 1.6, card_danger_dist = 25.0, card_danger_width = 14.0;
    /** cine are galben iese mai rar la deposedare (inmultit in sansa de a ataca mingea). */
    public static double booked_eager = 0.6;
    /**
     * Felul loviturii libere: directa pana la {@code fk_shot_range} m de centrul portii, daca
     * unghiul fata de axul portii e sub {@code fk_shot_angle} grade; altfel centrata in careu de la
     * {@code fk_cross_from} m (canonic); restul se joaca scurt.
     */
    public static double fk_shot_range = 26.5, fk_shot_angle = 55.0, fk_cross_from = 72.0;
    /**
     * Executia loviturii libere directe: eroarea de unghi (rad) a jucatorului mediu si panta lui
     * freeKick; inaltimea la poarta (medie, abatere si panta freeKick); puterea e cea a sutului,
     * inmultita cu {@code fk_power}; tinteste la {@code fk_aim_margin} m de bara.
     */
    public static double fk_error_base = 0.08, fk_error_freeKick = 0.15, fk_error_floor = 0.015,
            fk_height_base = 1.55, fk_height_sd = 0.75, fk_height_freeKick = 0.6, fk_height_sd_floor = 0.2,
            fk_power = 0.92, fk_aim_margin = 0.7;
    /** cat pleaca portarul mai tarziu la lovitura libera directa (s): zidul ii ascunde mingea. */
    public static double fk_gk_late = 0.43;
    /**
     * Zidul: {@code fk_wall_n} oameni ({@code fk_wall_n_close} sub {@code fk_wall_close} m), la
     * {@code fk_wall_dist} m de minge, la {@code fk_wall_gap} m unul de altul. Sansa ca sutul sa
     * ramana in zid: {@code fk_wall_base} pentru executantul mediu, mai mica cu freeKick mare.
     */
    public static double fk_wall_n = 3, fk_wall_n_close = 4, fk_wall_close = 22.0, fk_wall_dist = 9.15,
            fk_wall_gap = 0.7, fk_wall_base = 0.22, fk_wall_freeKick = 0.30;
    /**
     * Penalty-ul: sansa de gol a executantului mediu contra portarului mediu, cu pantele
     * penaltyTaking, composure si ale portarului ((reflexes + oneOnOnes) / 2); o parte fixa e
     * ratare pe langa poarta, restul e parada.
     */
    public static double pen_goal_base = 0.79, pen_penaltyTaking = 0.40, pen_composure = 0.12,
            pen_gk = 0.12, pen_miss_base = 0.07;
    /**
     * Asezarea la fazele fixe: executia asteapta pana ajung toti cei cu loc la cel mult
     * {@code setpiece_near} m, dar cel mult {@code setpiece_wait_max} s; cine e la peste
     * {@code setpiece_sprint} m de loc sprinteaza. Linia de la penalty e la {@code pen_line} m de
     * linia portii, intre -{@code pen_line_half} si +{@code pen_line_half} m de ax.
     */
    public static double setpiece_near = 2.5, setpiece_wait_max = 10.0, setpiece_sprint = 4.0,
            pen_line = 20.5, pen_line_half = 16.0, pen_spot = 11.0;
    /** cat de aproape de poarta (s) dupa executie se noteaza golul ca venit din faza fixa. */
    public static double setpiece_after = 10.0;
    /**
     * Faultul la duelul aerian (impingere la saritura): sansa pe duel a fiecaruia dintre cei doi,
     * cu panta aggression si team.foulFreq (in careul celui care faulteaza, inmultita cu foul_box);
     * cartonasul e de {@code aerial_card} ori mai rar decat la un fault obisnuit.
     */
    public static double aerial_foul_base = 0.04, aerial_foul_aggression = 1.2, aerial_card = 0.4;

    // ---- faze fixe, etapa B: centrari, cornere, lovitura de cap
    /**
     * Lovitura de cap la poarta: mingea inalta a unui coechipier ajunsa la cel mult
     * {@code header_range} m de centrul portii, in careu. Viteza din jumpingReach si strength,
     * eroarea de directie din heading (inmultita cu 1 + header_error_pressure x presiunea),
     * inaltimea la poarta din heading. Presiunea e {@code header_press_duel} dupa un duel aerian
     * castigat si {@code header_press_free} cand a fost singur.
     */
    public static double header_range = 12.0, header_power_base = 13.0, header_power_jumpingReach = 4.0,
            header_power_strength = 2.0, header_error_base = 0.11, header_error_heading = 0.22,
            header_error_floor = 0.03, header_error_pressure = 0.3, header_height_base = 1.0,
            header_height_sd = 0.9, header_height_heading = 0.6, header_height_sd_floor = 0.25,
            header_aim_margin = 0.8, header_press_duel = 0.5, header_press_free = 0.35;
    /**
     * Centrarea din joc: pasa inalta de pe banda (la cel putin {@code cross_wide} m de ax, de la x
     * canonic {@code cross_from_x}) cu tinta in careu. E permisa de la {@code cross_min_dist} m;
     * eroarea are structura mingii inalte, cu mixul crossing / passing / technique; valoarea
     * variantei se inmulteste cu {@code cross_bias} si cu 1 + cross_header_air x (air - 0.5) al
     * primitorului (poate da direct cu capul). Sansa estimata de reusita are un minim
     * {@code cross_contest}: in careu mingea se joaca si disputata. {@code cross_error_k} scaleaza
     * eroarea.
     */
    public static double cross_wide = 14.0, cross_from_x = 70.0, cross_min_dist = 10.0, cross_error_k = 1.0,
            cross_error_crossing = 0.6, cross_error_passing = 0.2, cross_error_technique = 0.2,
            cross_bias = 1.6, cross_header_air = 1.0, cross_contest = 0.4;
    /**
     * Cornerul: eroarea din corners (principala) si crossing; tinta e un atacant din careu, ales
     * cu sansa proportionala cu exp(corner_pick_air x (air - 0.5)).
     */
    public static double corner_error_corners = 0.7, corner_error_crossing = 0.3, corner_error_k = 0.3,
            corner_pick_air = 10.0, corner_skill_slope = 3.0;
    /**
     * Surse de corner (mingea scoasa peste linia portii de aparare): parada respinsa
     * ({@code corner_save}), sutul blocat la cel mult {@code corner_block_dist} m de linia portii
     * ({@code corner_block}), centrarea respinsa cu capul in careu ({@code corner_clear}; la
     * respingerea unui corner {@code corner_clear_corner}, ca sirurile corner - corner sa se stinga;
     * la o minge lunga obisnuita in careu {@code corner_clear_long}).
     */
    public static double corner_save = 0.7, corner_block = 0.55, corner_block_dist = 25.0,
            corner_clear = 0.5, corner_clear_corner = 0.15, corner_clear_long = 0.3;
    /** si deposedarea in care mingea e scoasa de langa poarta (la cel mult corner_tackle_dist m de linie). */
    public static double corner_tackle = 0.4, corner_tackle_dist = 18.0;
    /** si pasa adversa deviata de un aparator in careul lui. */
    public static double corner_deflect = 0.6;
    /** Lovitura libera centrata: eroarea din freeKick (principala) si crossing; tinta la fk_cross_depth m de linia portii. */
    public static double fk_cross_error_freeKick = 0.7, fk_cross_error_crossing = 0.3, fk_cross_error_k = 1.2,
            fk_cross_depth = 11.5;

    static {
        if (Boolean.getBoolean("match2d.legacy")) {
            // valorile dinaintea etapei 4, cu care motorul joaca exact ca cel Python
            marking_pull = 0.35;
            marking_marking = 0.45;
            marking_gap = 1.2;
            marking_marking_gap = 3.0;
            marking_gap_deep = 0.8;
            marking_marking_gap_deep = 2.2;
            // si cele dinaintea recalibrarii nivelului de goluri
            shot_error_slope = 0.34;
            block_base = 0.70;
            block_reach = 1.6;
            block_reach_near = 2.6;
            save_sure_base = 0.80;
            // echilibrul formatiilor (Runs e folosit doar pe drumul nou; valorile vechi, pentru claritate)
            run_from = 95.0;
            run_slope = 25.0;
            hold_depth = 10.0;
            // si cele dinaintea calibrarii finale (alergari, nivel, faze fixe); fazele fixe nu se
            // joaca pe drumul vechi, valorile vechi sunt aici pentru claritate
            run_second = 0.5;
            run_wide = 0.5;
            foul_box = 0.12;
            pen_goal_base = 0.82;
            fk_error_base = 0.085;
            fk_gk_late = 0.34;
            fk_cross_error_k = 1.8;
            header_error_base = 0.14;
            header_error_pressure = 0.8;
            header_press_duel = 0.7;
            header_press_free = 0.2;
            corner_save = 0.6;
            corner_block = 0.45;
            corner_clear_corner = 0.25;
            fk_shot_range = 25.5;
            // portarul: pantele reflexes si handling (cu bazele lor) dinaintea comprimarii
            save_reflexes = 0.21;
            save_react_base = 0.32;
            save_handling = 0.225;
        }
    }

    private Wt() {
    }

    private static Field field(String path) {
        String name = path.replace('.', '_');
        try {
            Field f = Wt.class.getField(name);
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == double.class) {
                return f;
            }
        } catch (NoSuchFieldException e) {
            // tratat mai jos
        }
        throw new IllegalArgumentException("ponderea " + path + " nu exista");
    }

    /** Schimba o pondere dupa nume, de exemplu {@code set("speed.pace", 1.0)}. Intoarce valoarea veche. */
    public static synchronized double set(String path, double value) {
        Field f = field(path);
        try {
            double old = f.getDouble(null);
            f.setDouble(null, value);
            return old;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Toate ponderile, cu numele lor ({@code mecanism_cheie}) si valoarea curenta. */
    public static Map<String, Double> table() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Field f : Wt.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == double.class) {
                try {
                    out.put(f.getName(), f.getDouble(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return out;
    }
}
