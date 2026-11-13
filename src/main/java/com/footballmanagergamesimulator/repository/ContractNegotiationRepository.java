package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ContractNegotiation;
import com.footballmanagergamesimulator.service.AgentNegotiation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContractNegotiationRepository extends JpaRepository<ContractNegotiation, Long> {

    List<ContractNegotiation> findAllByStatusIn(Collection<AgentNegotiation.Status> statuses);

    Optional<ContractNegotiation> findFirstByTransferOfferIdOrderByIdDesc(long transferOfferId);

    List<ContractNegotiation> findAllByPlayerIdAndKindAndStatus(long playerId, String kind, AgentNegotiation.Status status);

    boolean existsByStatusIn(Collection<AgentNegotiation.Status> statuses);
}
