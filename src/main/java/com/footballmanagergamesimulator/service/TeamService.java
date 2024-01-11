package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.footballmanagergamesimulator.repository.*;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TeamService {

  @Autowired
  private TeamRepository teamRepository;
  @Autowired
  private HumanRepository humanRepository;

  public List<Human> getAllHumanByTeamIdAndHumanRoleId(long teamId, long humanRoleId) {

    List<Human> humans = humanRepository.findAll()
                                        .stream()
                                        .filter(human -> human.getTypeId() == humanRoleId && human.getTeamId() == teamId)
                                        .collect(Collectors.toList());

    return humans;
  }

}
