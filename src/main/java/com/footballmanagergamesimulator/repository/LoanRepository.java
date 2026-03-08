package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Loan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findAllByLoanTeamIdAndStatus(long teamId, String status);

    List<Loan> findAllByParentTeamIdAndStatus(long teamId, String status);

    List<Loan> findAllByPlayerIdAndStatus(long playerId, String status);

    List<Loan> findAllBySeasonNumber(int seasonNumber);

    List<Loan> findAllByLoanTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    List<Loan> findAllByParentTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    List<Loan> findAllByStatus(String status);

}
