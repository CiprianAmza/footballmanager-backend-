package com.footballmanagergamesimulator.service;

/**
 * Finishes a transfer whose fee was already agreed, once personal terms are settled (or not).
 * Implemented by the transfer market, which owns the money movements and the offer lifecycle.
 */
public interface AgreedTransferHandler {

    /** Moves the player to the buyer and pays the fee. Throws if the transfer is no longer possible. */
    void complete(long transferOfferId);

    /** Personal terms failed: the fee agreement lapses and the manager is told. */
    void collapse(long transferOfferId, String reason);
}
