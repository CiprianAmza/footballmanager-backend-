package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TransferInstallment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferInstallmentRepository extends JpaRepository<TransferInstallment, Long> {

    List<TransferInstallment> findAllByPaidFalseAndDueSeasonLessThanEqual(int season);
}
