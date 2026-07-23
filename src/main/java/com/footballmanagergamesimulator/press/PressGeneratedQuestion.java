package com.footballmanagergamesimulator.press;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** A frozen, ordered question produced by the deterministic generator. */
@Data
@AllArgsConstructor
public class PressGeneratedQuestion {
    private String catalogQuestionId;
    private String contextKey;
    private String promptText;
    private List<PressGeneratedAnswer> answers;
}
