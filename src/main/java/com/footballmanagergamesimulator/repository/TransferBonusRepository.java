package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TransferBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferBonusRepository extends JpaRepository<TransferBonus, Long> {

    List<TransferBonus> findAllByPaidFalseAndFromSeasonLessThanEqualAndUntilSeasonGreaterThanEqual(int from, int until);
}
