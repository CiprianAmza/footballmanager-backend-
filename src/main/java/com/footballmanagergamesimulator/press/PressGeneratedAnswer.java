package com.footballmanagergamesimulator.press;

import com.footballmanagergamesimulator.press.catalog.PressEffectSpec;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** A frozen answer option offered for a generated question. */
@Data
@AllArgsConstructor
public class PressGeneratedAnswer {
    private String catalogAnswerId;
    private String code;
    private String tone;
    private String stance;
    private List<PressEffectSpec> effects;
}
