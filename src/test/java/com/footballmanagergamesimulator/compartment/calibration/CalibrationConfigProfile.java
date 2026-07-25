package com.footballmanagergamesimulator.compartment.calibration;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;

record CalibrationConfigProfile(CompartmentEngineConfig compartment, MatchEngineConfig match) {}
