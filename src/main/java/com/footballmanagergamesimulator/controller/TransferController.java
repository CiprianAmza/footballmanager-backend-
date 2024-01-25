package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transfers")
@CrossOrigin(origins = "*")
public class TransferController {

    @Autowired
    TransferRepository transferRepository;

    @GetMapping("/boughtPlayers/{teamId}/{seasonNumber}")
    public List<Transfer> getBoughtPlayersByTeamIdAndSeasonId(@PathVariable(name = "teamId") Long teamId, @PathVariable(name = "seasonNumber") Long seasonNumber) { // tactic format: GK=1231&DC=1331&DL=123...

        List<Transfer> boughtPlayers = transferRepository.findAllByBuyTeamIdAndSeasonNumber(teamId, seasonNumber);

        return boughtPlayers;
    }

    @GetMapping("/soldPlayers/{teamId}/{seasonNumber}")
    public List<Transfer> getSoldPlayersByTeamIdAndSeasonId(@PathVariable(name = "teamId") Long teamId, @PathVariable(name = "seasonNumber") Long seasonNumber) { // tactic format: GK=1231&DC=1331&DL=123...

        List<Transfer> soldPlayers = transferRepository.findAllBySellTeamIdAndSeasonNumber(teamId, seasonNumber);

        return soldPlayers;
    }
}
