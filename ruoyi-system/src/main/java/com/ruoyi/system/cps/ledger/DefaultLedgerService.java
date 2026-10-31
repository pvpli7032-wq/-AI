package com.ruoyi.system.cps.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** All account mutations stay at the existing, locked account boundary. */
@Service
public class DefaultLedgerService implements LedgerService {
    private final CommissionCalculateServiceImpl accounts;
    public DefaultLedgerService(CommissionCalculateServiceImpl accounts) { this.accounts = accounts; }

    @Override
    @Transactional
    public LedgerPosting post(PostingCommand command) {
        return accounts.updateUserAccount(command);
    }
}
