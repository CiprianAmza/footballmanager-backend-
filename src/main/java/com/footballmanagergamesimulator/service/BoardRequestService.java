package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BoardRequestService {

    @Autowired
    BoardRequestRepository boardRequestRepository;
    @Autowired
    SeasonObjectiveRepository seasonObjectiveRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    ManagerInboxRepository managerInboxRepository;

    private static final List<String> BOARD_REQUEST_DESCRIPTIONS = List.of(
            "Sign a new striker",
            "Reduce wage bill",
            "Develop youth players",
            "Improve league position",
            "Increase stadium revenue"
    );

    private static final List<String> BOARD_REQUEST_TYPES = List.of(
            "IMPROVE_POSITION",
            "REDUCE_WAGES",
            "DEVELOP_YOUTH",
            "WIN_TROPHY",
            "INCREASE_REVENUE"
    );

    public Map<String, Object> processBoardMeeting(long teamId, int season) {

        Map<String, Object> result = new HashMap<>();

        // Check current season objectives progress
        List<SeasonObjective> objectives = seasonObjectiveRepository.findAllByTeamIdAndSeasonNumber(teamId, season);

        long metCount = objectives.stream()
                .filter(obj -> "achieved".equals(obj.getStatus()))
                .count();
        long activeCount = objectives.stream()
                .filter(obj -> "active".equals(obj.getStatus()))
                .count();
        long failedCount = objectives.stream()
                .filter(obj -> "failed".equals(obj.getStatus()))
                .count();

        // Find the manager for this team
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        Human manager = managers.isEmpty() ? null : managers.get(0);

        boolean objectivesBeingMet = failedCount == 0 && (metCount > 0 || activeCount > 0);
        String boardMood;

        if (objectivesBeingMet) {
            // Board is happy
            boardMood = "HAPPY";
            if (manager != null) {
                double newMorale = Math.min(100, manager.getMorale() + 5);
                manager.setMorale(newMorale);
                humanRepository.save(manager);
            }

            sendInboxMessage(teamId, season, 0,
                    "Board Meeting: Positive Outlook",
                    "The board is pleased with the team's progress this season. Keep up the good work!",
                    "BOARD");

        } else {
            // Board is unhappy - check for previous warnings
            List<BoardRequest> previousFailures = boardRequestRepository.findAllByTeamIdAndStatus(teamId, "FAILED");
            int warningCount = previousFailures.size();

            if (warningCount >= 2) {
                // Manager fired
                boardMood = "FIRED";
                sendInboxMessage(teamId, season, 0,
                        "Board Meeting: Manager Dismissed",
                        "The board has lost confidence in the manager due to repeated failures to meet objectives. The manager has been dismissed.",
                        "BOARD");

                if (manager != null) {
                    manager.setMorale(0);
                    humanRepository.save(manager);
                }

            } else {
                // Issue warning
                boardMood = "WARNING";
                sendInboxMessage(teamId, season, 0,
                        "Board Meeting: Warning Issued",
                        "The board is not satisfied with the current progress. This is a formal warning. Failure to improve will result in dismissal.",
                        "BOARD");

                if (manager != null) {
                    double newMorale = Math.max(0, manager.getMorale() - 10);
                    manager.setMorale(newMorale);
                    humanRepository.save(manager);
                }
            }
        }

        result.put("boardMood", boardMood);
        result.put("objectivesMet", metCount);
        result.put("objectivesActive", activeCount);
        result.put("objectivesFailed", failedCount);

        // Generate random board request with 20% chance
        Random random = new Random();
        if (random.nextDouble() < 0.2) {
            int requestIndex = random.nextInt(BOARD_REQUEST_DESCRIPTIONS.size());

            BoardRequest boardRequest = new BoardRequest();
            boardRequest.setTeamId(teamId);
            boardRequest.setSeasonNumber(season);
            boardRequest.setDay(0);
            boardRequest.setRequestType(BOARD_REQUEST_TYPES.get(requestIndex));
            boardRequest.setDescription(BOARD_REQUEST_DESCRIPTIONS.get(requestIndex));
            boardRequest.setDeadline(60); // current day + 60
            boardRequest.setStatus("ACTIVE");
            boardRequest.setReputationPenalty(10);

            boardRequestRepository.save(boardRequest);

            sendInboxMessage(teamId, season, 0,
                    "New Board Request",
                    "The board has a new request: " + boardRequest.getDescription() +
                            ". You have 60 days to fulfill this request.",
                    "BOARD");

            result.put("newBoardRequest", boardRequest.getDescription());
        }

        return result;
    }

    public Map<String, Object> checkBoardRequestDeadline(long teamId, int currentDay, int season) {

        Map<String, Object> result = new HashMap<>();

        List<BoardRequest> activeRequests = boardRequestRepository.findAllByTeamIdAndStatus(teamId, "ACTIVE");

        List<BoardRequest> expiredRequests = activeRequests.stream()
                .filter(req -> req.getSeasonNumber() == season && currentDay > req.getDeadline())
                .collect(Collectors.toList());

        if (!expiredRequests.isEmpty()) {
            // Mark expired requests
            for (BoardRequest request : expiredRequests) {
                request.setStatus("EXPIRED");
                boardRequestRepository.save(request);
            }

            // Apply morale penalty to manager
            List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
            if (!managers.isEmpty()) {
                Human manager = managers.get(0);
                double moralePenalty = -10.0 * expiredRequests.size();
                double newMorale = Math.max(0, manager.getMorale() + moralePenalty);
                manager.setMorale(newMorale);

                // Check for multiple failures - possible firing
                List<BoardRequest> allFailures = boardRequestRepository.findAllByTeamIdAndStatus(teamId, "EXPIRED");
                List<BoardRequest> allFailed = boardRequestRepository.findAllByTeamIdAndStatus(teamId, "FAILED");
                int totalFailures = allFailures.size() + allFailed.size();

                if (totalFailures >= 3) {
                    result.put("managerFired", true);
                    sendInboxMessage(teamId, season, currentDay,
                            "Board Decision: Manager Dismissed",
                            "Due to multiple unfulfilled board requests, the board has decided to part ways with the manager.",
                            "BOARD");
                }

                humanRepository.save(manager);
            }

            sendInboxMessage(teamId, season, currentDay,
                    "Board Request Expired",
                    expiredRequests.size() + " board request(s) have expired without being fulfilled. " +
                            "A morale penalty has been applied.",
                    "BOARD");

            result.put("expiredCount", expiredRequests.size());
        } else {
            result.put("expiredCount", 0);
        }

        return result;
    }

    public BoardRequest fulfillBoardRequest(long requestId) {

        BoardRequest boardRequest = boardRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Board request not found: " + requestId));

        boardRequest.setStatus("MET");
        boardRequestRepository.save(boardRequest);

        // Apply morale bonus to manager
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(boardRequest.getTeamId(), TypeNames.MANAGER_TYPE);
        if (!managers.isEmpty()) {
            Human manager = managers.get(0);
            double newMorale = Math.min(100, manager.getMorale() + 5);
            manager.setMorale(newMorale);
            humanRepository.save(manager);
        }

        sendInboxMessage(boardRequest.getTeamId(), boardRequest.getSeasonNumber(), 0,
                "Board Request Fulfilled",
                "The board acknowledges that the request '" + boardRequest.getDescription() +
                        "' has been fulfilled. Well done!",
                "BOARD");

        return boardRequest;
    }

    public List<BoardRequest> getBoardRequests(long teamId, int season) {

        return boardRequestRepository.findAllByTeamIdAndSeasonNumber(teamId, season);
    }

    private void sendInboxMessage(long teamId, int season, int day, String title, String content, String category) {

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());

        managerInboxRepository.save(inbox);
    }
}
