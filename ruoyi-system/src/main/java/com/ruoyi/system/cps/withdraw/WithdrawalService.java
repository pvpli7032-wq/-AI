package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.ledger.AccountType;
import com.ruoyi.system.cps.ledger.LedgerPosting;
import com.ruoyi.system.cps.ledger.LedgerService;
import com.ruoyi.system.cps.ledger.PostingCommand;
import com.ruoyi.system.cps.mapper.WithdrawalMapper;
import com.ruoyi.system.cps.recon.ExposureService;
import com.ruoyi.system.cps.risk.HealthMonitorService;
import com.ruoyi.system.cps.risk.RiskService;

/** Owns withdrawal state, never account SQL. No channel call is made inside an account transaction. */
@Service
public class WithdrawalService {
    private final WithdrawalMapper mapper;
    private final ObjectProvider<LedgerService> ledgers;
    private final ObjectProvider<WithdrawChannel> channels;
    private final RiskService risk;
    private final ExposureService exposure;
    private final HealthMonitorService health;
    private final TransactionTemplate transactions;
    private Clock clock = Clock.systemUTC();

    public WithdrawalService(WithdrawalMapper mapper, ObjectProvider<LedgerService> ledgers,
            ObjectProvider<WithdrawChannel> channels, RiskService risk, ExposureService exposure,
            HealthMonitorService health, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.ledgers = ledgers;
        this.channels = channels;
        this.risk = risk;
        this.exposure = exposure;
        this.health = health;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // A caller's rollback must never undo a persisted submission marker after the channel saw it.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void setClock(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }
    public boolean available() { return ledgers.getIfAvailable() != null; }

    public WithdrawalView apply(long userId, WithdrawalApplyRequest request) {
        validUser(userId);
        if (request == null || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{8,100}"))
            throw new ServiceException("请提供8至100位唯一requestId，重试必须沿用原值");
        BigDecimal amount = WithdrawalPolicy.requestedAmount(request.amount());
        return transactions.execute(tx -> {
            admissionLock();
            WithdrawalRecord replay = mapper.findByRequest(userId, request.requestId());
            if (replay != null) {
                if (replay.getAmount().compareTo(amount) != 0) throw new ServiceException("requestId已用于不同金额");
                return WithdrawalView.of(replay);
            }
            ledger();
            WithdrawalPolicy policy = policy();
            WithdrawalAccount account = activeAccount(userId);
            if (account.balance().signum() < 0) throw new ServiceException("负余额未结清，不能提现");
            if (account.balance().compareTo(amount) < 0) throw new ServiceException("可提现余额不足");
            var riskDecision = risk.evaluateWithdrawal(userId);
            if (riskDecision.blocked()) throw new ServiceException("用户已被限制提现，请联系人工处理");
            var exposureDecision = exposure.evaluateWithdrawal(amount);
            if (exposureDecision.blocked()) throw new ServiceException(exposureDecision.reason());
            WithdrawalRecipient recipient = mapper.recipient(userId, policy.channel());
            if (recipient == null || !Boolean.TRUE.equals(recipient.getVerified()))
                throw new ServiceException("请先完成收款姓名与账号归属核验");
            LocalDate today = LocalDate.now(clock.withZone(policy.businessTimezone()));
            LocalDateTime dayStart = utc(today, policy), dayEnd = utc(today.plusDays(1), policy);
            LocalDateTime monthStart = utc(today.withDayOfMonth(1), policy);
            LocalDateTime monthEnd = utc(today.withDayOfMonth(1).plusMonths(1), policy);
            BigDecimal monthAmount = mapper.userAmount(userId, monthStart, monthEnd).add(amount);
            if (amount.compareTo(policy.minimum()) < 0) throw new ServiceException("未达到最低提现金额");
            maximum(policy.maximum(), amount, "超过本地单笔提现上限");
            maximum(policy.monthlyAmount(), monthAmount, "超过本地每月提现上限");
            if (policy.integerOnly() && request.amount().stripTrailingZeros().scale() > 0)
                throw new ServiceException("当前仅允许整数提现");
            if (!policy.monthDays().isEmpty() && !policy.monthDays().contains(today.getDayOfMonth()))
                throw new ServiceException("今日不是配置的提现日期");
            if (policy.requireReceivedOrder() && mapper.receivedOrders(userId) == 0)
                throw new ServiceException("尚无有效收货订单，暂不能提现");
            countLimit(policy.dailyCount(), mapper.countRequests(userId, dayStart, dayEnd), "超过用户每日申请次数");
            countLimit(policy.recipientDailyCount(), mapper.countRecipientRequests(recipient.getRecipientHash(), dayStart, dayEnd),
                "超过同一收款账号每日申请次数");
            channelLimits(policy, userId, amount, dayStart, dayEnd, false);
            List<String> review = new ArrayList<>(policy.reviewReasons(amount, mapper.countSuccess(userId),
                mapper.countRequests(userId, null, null), mapper.disputeOrders(userId), mapper.settledOrders(userId),
                mapper.firstCommissionRatio(userId), mapper.userGroup(userId)));
            if (riskDecision.manualReview()) review.add("RISK_REVIEW");
            if (exposureDecision.manualReview()) review.add("EXPOSURE_LIMIT_REACHED");
            if ((WithdrawalPolicy.hit(policy.realNameSingle(), amount) || WithdrawalPolicy.hit(policy.realNameMonthly(), monthAmount))
                    && !Boolean.TRUE.equals(recipient.getIdentityVerified())) {
                if (policy.identityAction() == WithdrawalPolicy.IdentityAction.REJECT)
                    throw new ServiceException("已达到配置的实名门槛，请先完成实名核验");
                review.add("IDENTITY_REVIEW");
            }
            if (channel(policy.channel()) == null) review.add("CHANNEL_UNAVAILABLE");
            WithdrawalRecord row = new WithdrawalRecord();
            row.setWithdrawNo("WD" + UUID.randomUUID().toString().replace("-", ""));
            row.setUserId(userId); row.setAmount(amount); row.setFee(new BigDecimal("0.00"));
            row.setRequestKey(request.requestId()); row.setChannel(policy.channel());
            row.setRecipientId(recipient.getId()); row.setRecipientName(recipient.getRecipientName());
            row.setRecipientAccount(recipient.getRecipientAccount()); row.setRecipientHash(recipient.getRecipientHash());
            row.setReviewReasons(JSON.toJSONString(review)); row.setPolicySnapshot(policy.snapshot());
            row.setCreatedAt(now()); row.setUpdatedAt(now());
            row.setStatus(review.isEmpty() ? WithdrawalStatus.APPROVED : WithdrawalStatus.MANUAL_REVIEW);
            if (review.isEmpty()) row.setAuditAt(now());
            mapper.insertWithdrawal(row);
            post(row, "WITHDRAW_APPLY", AccountType.USER_AVAILABLE, AccountType.USER_WITHDRAWING, "withdraw", null);
            audit(row, "APPLY", null, null, "申请提现；审核原因=" + String.join(",", review));
            return WithdrawalView.of(row);
        });
    }

    public WithdrawalView audit(String withdrawNo, boolean approve, long operatorId, String reason) {
        validUser(operatorId); reason = required(reason, 400, "审核原因");
        final String auditReason = reason;
        return transactions.execute(tx -> {
            admissionLock();
            WithdrawalRecord row = lock(withdrawNo);
            if (approve && row.getStatus() == WithdrawalStatus.APPROVED) return WithdrawalView.of(row);
            if (!approve && row.getStatus() == WithdrawalStatus.REJECTED) return WithdrawalView.of(row);
            if (row.getStatus() != WithdrawalStatus.MANUAL_REVIEW && row.getStatus() != WithdrawalStatus.APPROVED)
                throw new ServiceException("当前提现状态不允许审核或退回");
            WithdrawalStatus previous = row.getStatus();
            if (approve) {
                activeAccount(row.getUserId());
                var riskDecision = risk.evaluateWithdrawal(row.getUserId());
                if (riskDecision.blocked() || exposure.evaluateWithdrawal(row.getAmount()).blocked())
                    throw new ServiceException("账户或敞口限制尚未解除");
                if (riskDecision.manualReview())
                    throw new ServiceException("风控人工复核尚未解除，请先在风控台处理");
                verifyPayeeSnapshot(row);
                verifyRequiredIdentity(row);
                row.setStatus(WithdrawalStatus.APPROVED);
            } else {
                post(row, "WITHDRAW_RETURN", AccountType.USER_WITHDRAWING, AccountType.USER_AVAILABLE, "withdraw_return", operatorId);
                row.setStatus(WithdrawalStatus.REJECTED); row.setCompletedAt(now());
            }
            row.setAuditBy(operatorId); row.setAuditAt(now()); row.setUpdatedAt(now());
            row.setFailReason(approve ? null : auditReason);
            mapper.updateWithdrawal(row);
            audit(row, approve ? "APPROVE" : "REJECT", previous, operatorId, auditReason);
            return WithdrawalView.of(row);
        });
    }

    private record Submission(WithdrawalRecord row, boolean submit) {}

    public WithdrawalView pay(String withdrawNo, long operatorId) {
        validUser(operatorId);
        Submission claim = transactions.execute(tx -> {
            admissionLock();
            WithdrawalRecord row = lock(withdrawNo);
            if (row.getStatus().terminal() || row.getStatus().submitted()) return new Submission(row, false);
            if (row.getStatus() != WithdrawalStatus.APPROVED) throw new ServiceException("提现尚未审核通过");
            ledger();
            WithdrawalPolicy currentPolicy = policy(); // Disabled policy stops submissions, never result queries.
            WithdrawalAccount account = activeAccount(row.getUserId());
            if (account.balance().signum() < 0) throw new ServiceException("负余额未结清，不能提现");
            var riskDecision = risk.evaluateWithdrawal(row.getUserId());
            var exposureDecision = exposure.evaluateWithdrawal(row.getAmount());
            if (riskDecision.blocked() || exposureDecision.blocked())
                throw new ServiceException("账户或敞口限制尚未解除");
            // A newly raised risk cannot inherit an earlier automatic approval. A risk reviewer
            // must resolve its evidence before payment; withdrawal approval cannot erase a hold.
            if (riskDecision.manualReview() || (exposureDecision.manualReview() && row.getAuditBy() == null)) {
                addReviewReason(row, riskDecision.manualReview() ? "RISK_REVIEW" : "EXPOSURE_LIMIT_REACHED");
                // Approval predates this hold and must not authorize a later dispatch.
                row.setAuditBy(null); row.setAuditAt(null);
                row.setStatus(WithdrawalStatus.MANUAL_REVIEW); row.setUpdatedAt(now()); mapper.updateWithdrawal(row);
                audit(row, "REVIEW_REQUIRED", WithdrawalStatus.APPROVED, operatorId, "提交前重新检查触发人工审核");
                return new Submission(row, false);
            }
            verifyPayeeSnapshot(row);
            verifyRequiredIdentity(row);
            WithdrawChannel channel = channel(row.getChannel());
            if (channel == null) {
                row.setStatus(WithdrawalStatus.MANUAL_REVIEW); row.setUpdatedAt(now());
                addReviewReason(row, "CHANNEL_UNAVAILABLE"); mapper.updateWithdrawal(row);
                audit(row, "CHANNEL_UNAVAILABLE", WithdrawalStatus.APPROVED, operatorId, "通道不可用，退回人工审核");
                return new Submission(row, false);
            }
            WithdrawalPolicy snapshot = WithdrawalPolicy.fromSnapshot(row.getPolicySnapshot());
            LocalDate today = LocalDate.now(clock.withZone(snapshot.businessTimezone()));
            channelLimits(snapshot, row.getUserId(), row.getAmount(), utc(today, snapshot), utc(today.plusDays(1), snapshot), true);
            if (currentPolicy.channel().equals(row.getChannel())) {
                LocalDate currentDay = LocalDate.now(clock.withZone(currentPolicy.businessTimezone()));
                channelLimits(currentPolicy, row.getUserId(), row.getAmount(), utc(currentDay, currentPolicy),
                    utc(currentDay.plusDays(1), currentPolicy), true);
            }
            row.setStatus(WithdrawalStatus.SUBMITTING); row.setSubmittedAt(now()); row.setUpdatedAt(now());
            mapper.updateWithdrawal(row);
            audit(row, "SUBMIT", WithdrawalStatus.APPROVED, operatorId, "已持久化提交标记；后续仅查询同一提现号");
            return new Submission(row, true);
        });
        if (claim.row().getStatus().terminal() || claim.row().getStatus() == WithdrawalStatus.MANUAL_REVIEW)
            return WithdrawalView.of(claim.row());
        if (!claim.submit()) return query(withdrawNo);
        PaymentResult result;
        try {
            result = channel(claim.row().getChannel()).submit(new PaymentRequest(withdrawNo, claim.row().getAmount(),
                claim.row().getRecipientName(), claim.row().getRecipientAccount()));
        } catch (RuntimeException e) {
            // A timeout/exception is not a channel rejection; never release the reservation here.
            result = new PaymentResult(withdrawNo, null, PaymentStatus.UNKNOWN, "提交结果不明，只能查询");
        }
        return accept(withdrawNo, result);
    }

    public WithdrawalView query(String withdrawNo) {
        WithdrawalRecord row = requireRow(mapper.findByNo(withdrawNo));
        if (row.getStatus().terminal() || !row.getStatus().submitted()) return WithdrawalView.of(row);
        WithdrawChannel channel = channel(row.getChannel());
        if (channel == null) return WithdrawalView.of(row);
        PaymentResult result;
        try { result = channel.query(withdrawNo); }
        catch (RuntimeException e) { result = new PaymentResult(withdrawNo, row.getChannelNo(), PaymentStatus.UNKNOWN, "查询未确认"); }
        return accept(withdrawNo, result);
    }

    public WithdrawalView callback(ChannelNotification notification) {
        if (notification == null) throw new ServiceException("无效的通道通知");
        WithdrawChannel channel = channel(notification.channel());
        if (channel == null) throw new ServiceException("通道不可用");
        PaymentResult result = channel.handleCallback(notification); // The adapter authenticates before state changes.
        if (result == null || result.withdrawNo() == null) throw new ServiceException("通道通知缺少提现号");
        WithdrawalRecord row = requireRow(mapper.findByNo(result.withdrawNo()));
        if (!notification.channel().equals(row.getChannel())) throw new ServiceException("通道与申请快照不匹配");
        return accept(result.withdrawNo(), result);
    }

    private WithdrawalView accept(String expectedNo, PaymentResult result) {
        return transactions.execute(tx -> {
            admissionLock();
            WithdrawalRecord row = lock(expectedNo);
            if (result == null || result.status() == null || !expectedNo.equals(result.withdrawNo()))
                throw new ServiceException("通道返回与提现单不匹配，保留待查询状态");
            if (row.getChannelNo() != null && result.channelNo() != null && !row.getChannelNo().equals(result.channelNo()))
                throw new ServiceException("通道单号不匹配，需人工核查");
            if (row.getStatus().terminal()) {
                boolean contradiction = (row.getStatus() == WithdrawalStatus.SUCCEEDED && result.status() == PaymentStatus.FAILED)
                    || ((row.getStatus() == WithdrawalStatus.FAILED || row.getStatus() == WithdrawalStatus.REJECTED)
                        && result.status() == PaymentStatus.SUCCESS);
                if (contradiction) {
                    audit(row, "RESULT_CONFLICT", row.getStatus(), null,
                        "通道终态矛盾；本地=" + row.getStatus() + "，通道=" + result.status() + "；保留原终态与原账务，必须人工对账");
                    // REQUIRED joins this result transaction: evidence and its alert commit together.
                    health.recordWithdrawalResultConflict(expectedNo, row.getStatus().name(), result.status().name());
                }
                return WithdrawalView.of(row);
            }
            if (!row.getStatus().submitted()) throw new ServiceException("未提交的提现单不能接收打款结果");
            WithdrawalStatus previous = row.getStatus();
            if (result.channelNo() != null) row.setChannelNo(required(result.channelNo(), 128, "通道单号"));
            row.setStatus(switch (result.status()) {
                case SUCCESS -> WithdrawalStatus.SUCCEEDED; case FAILED -> WithdrawalStatus.FAILED;
                case PROCESSING -> WithdrawalStatus.PROCESSING; case UNKNOWN -> WithdrawalStatus.UNKNOWN;
                case AWAITING_CONFIRMATION -> WithdrawalStatus.AWAITING_CONFIRMATION; case TIMED_OUT -> WithdrawalStatus.TIMED_OUT;
            });
            if (result.status() == PaymentStatus.SUCCESS) {
                post(row, "WITHDRAW_SUCCESS", AccountType.USER_WITHDRAWING, AccountType.PAYOUT, "withdraw_success", null);
                row.setCompletedAt(now());
            } else if (result.status() == PaymentStatus.FAILED) {
                post(row, "WITHDRAW_RETURN", AccountType.USER_WITHDRAWING, AccountType.USER_AVAILABLE, "withdraw_return", null);
                row.setCompletedAt(now()); row.setFailReason("通道确认打款失败");
            }
            row.setUpdatedAt(now()); mapper.updateWithdrawal(row);
            if (previous != row.getStatus()) audit(row, "CHANNEL_RESULT", previous, null, "通道结果=" + result.status());
            return WithdrawalView.of(row);
        });
    }

    private void post(WithdrawalRecord row, String businessType, AccountType debit, AccountType credit, String type, Long operatorId) {
        WithdrawalAccount before = mapper.lockAccount(row.getUserId());
        if (before == null) throw new ServiceException("账户不存在");
        String key = businessType + ":" + row.getWithdrawNo();
        LedgerPosting posted = ledger().post(new PostingCommand(key, businessType, row.getWithdrawNo(), debit,
            row.getUserId(), null, type, debit, credit, row.getAmount(), "提现单" + row.getWithdrawNo(), operatorId));
        if (posted == null || !key.equals(posted.businessKey())) throw new ServiceException("入账服务未确认，提现事务已回滚");
        if (!posted.replayed()) {
            BigDecimal available = before.balance(), withdrawing = before.withdrawingAmount();
            if (debit == AccountType.USER_AVAILABLE) available = available.subtract(row.getAmount());
            if (credit == AccountType.USER_AVAILABLE) available = available.add(row.getAmount());
            if (debit == AccountType.USER_WITHDRAWING) withdrawing = withdrawing.subtract(row.getAmount());
            if (credit == AccountType.USER_WITHDRAWING) withdrawing = withdrawing.add(row.getAmount());
            WithdrawalAccount after = mapper.lockAccount(row.getUserId());
            if (withdrawing.signum() < 0 || available.compareTo(after.balance()) != 0 || withdrawing.compareTo(after.withdrawingAmount()) != 0)
                throw new ServiceException("入账结果与提现分录不一致，事务已回滚");
        }
    }

    public Map<String, Object> list(long userId, int page, int size) { validUser(userId); return listRows(userId, null, page, size); }
    public Map<String, Object> listAdmin(String status, int page, int size) {
        if (status != null && !status.isBlank()) {
            try { WithdrawalStatus.valueOf(status); } catch (IllegalArgumentException e) { throw new ServiceException("无效提现状态"); }
        } else status = null;
        return listRows(null, status, page, size);
    }
    private Map<String, Object> listRows(Long userId, String status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new ServiceException("分页参数超出范围");
        List<WithdrawalRecord> rows = mapper.list(userId, status, ((long) page - 1) * size, size);
        List<?> views = userId == null ? rows.stream().map(r -> Map.of("userId", r.getUserId(), "withdrawal", WithdrawalView.of(r),
            "reviewReasons", r.getReviewReasons() == null ? "[]" : r.getReviewReasons(),
            "auditOverdue", r.getStatus() == WithdrawalStatus.MANUAL_REVIEW && r.getCreatedAt().plusHours(24).isBefore(now()))).toList()
            : rows.stream().map(WithdrawalView::of).toList();
        return Map.of("rows", views, "total", mapper.count(userId, status));
    }

    /** Cursor scan does not starve later pending rows when the first rows stay UNKNOWN. */
    public int queryPending() {
        long cursor = 0; int queried = 0;
        while (true) {
            List<String> batch = mapper.pending(cursor, 100);
            if (batch.isEmpty()) return queried;
            for (String no : batch) {
                WithdrawalRecord row = mapper.findByNo(no); cursor = row.getId();
                try { query(no); } catch (RuntimeException e) {
                    final long userId = row.getUserId();
                    transactions.executeWithoutResult(tx -> mapper.audit(no, userId, "QUERY_RETRY", null, null, null,
                        "结果查询未完成，保留金额并等待下次查询", now()));
                }
                queried++;
            }
        }
    }

    public String configuration() { return mapper.selectPolicy(); }
    public void configure(String json, long operatorId) {
        validUser(operatorId);
        JSONObject parsed;
        try { parsed = JSON.parseObject(json); } catch (RuntimeException e) { throw new ServiceException("配置必须为JSON对象"); }
        if (parsed == null || !(parsed.get("enabled") instanceof Boolean)) throw new ServiceException("必须明确设置enabled");
        if (Boolean.TRUE.equals(parsed.getBoolean("enabled"))) WithdrawalPolicy.parse(json, mapper.configuration("commission.min.withdraw"));
        transactions.executeWithoutResult(tx -> {
            admissionLock(); mapper.savePolicy(parsed.toJSONString(), operatorId, now());
            mapper.audit(null, operatorId, "POLICY_CHANGE", null, null, operatorId, "更新提现策略；既有申请保留快照", now());
        });
    }

    public void bindRecipient(long userId, String channel, String name, String account, boolean verified,
            boolean identityVerified, String verificationRef, long operatorId, String reason) {
        validUser(userId); validUser(operatorId);
        if (channel == null || !channel.matches("[a-z][a-z0-9_-]{0,31}")) throw new ServiceException("无效通道");
        String payee = required(name, 128, "收款姓名"), recipientAccount = required(account, 255, "收款账号");
        String explanation = required(reason, 300, "改绑原因");
        if ((verified || identityVerified) && (verificationRef == null || verificationRef.isBlank()))
            throw new ServiceException("核验通过必须提供归属核验凭证编号");
        if (identityVerified && !verified) throw new ServiceException("实名通过前必须确认收款账号归属");
        if (verificationRef != null && verificationRef.length() > 255) throw new ServiceException("核验凭证编号过长");
        transactions.executeWithoutResult(tx -> {
            admissionLock();
            if (mapper.lockAccount(userId) == null) throw new ServiceException("用户账户不存在");
            WithdrawalRecipient old = mapper.recipient(userId, channel);
            WithdrawalRecipient recipient = new WithdrawalRecipient();
            recipient.setUserId(userId); recipient.setChannel(channel); recipient.setRecipientName(payee);
            recipient.setRecipientAccount(recipientAccount); recipient.setRecipientHash(recipientHash(channel, recipientAccount));
            recipient.setVerified(verified); recipient.setIdentityVerified(identityVerified); recipient.setVerificationRef(verificationRef);
            recipient.setUpdatedBy(operatorId); recipient.setUpdatedAt(now()); mapper.saveRecipient(recipient);
            mapper.audit(null, userId, "RECIPIENT_CHANGE", null, null, operatorId,
                "收款账号 " + WithdrawalView.mask(old == null ? null : old.getRecipientAccount()) + " -> " + WithdrawalView.mask(recipientAccount)
                + "；" + explanation, now());
        });
    }

    public void assignGroup(long userId, String group, long operatorId, String reason) {
        validUser(userId); validUser(operatorId);
        if (group == null || !group.matches("[A-Za-z0-9_-]{1,64}")) throw new ServiceException("无效分组");
        String explanation = required(reason, 400, "分组变更原因");
        transactions.executeWithoutResult(tx -> {
            admissionLock(); mapper.saveGroup(userId, group, operatorId, now());
            mapper.audit(null, userId, "GROUP_CHANGE", null, null, operatorId, explanation, now());
        });
    }

    private void verifyRequiredIdentity(WithdrawalRecord row) {
        if (row.getReviewReasons() != null && row.getReviewReasons().contains("IDENTITY_REVIEW")) {
            WithdrawalRecipient recipient = mapper.recipient(row.getUserId(), row.getChannel());
            if (recipient == null || !row.getRecipientHash().equals(recipient.getRecipientHash())
                    || !row.getRecipientName().equals(recipient.getRecipientName()) || !Boolean.TRUE.equals(recipient.getIdentityVerified()))
                throw new ServiceException("实名门槛已触发，请先核验申请时的收款人信息");
        }
    }
    private void verifyPayeeSnapshot(WithdrawalRecord row) {
        WithdrawalRecipient recipient = mapper.recipient(row.getUserId(), row.getChannel());
        if (recipient == null || !Boolean.TRUE.equals(recipient.getVerified())
                || !row.getRecipientHash().equals(recipient.getRecipientHash())
                || !row.getRecipientName().equals(recipient.getRecipientName()))
            throw new ServiceException("申请后的收款资料已变更或核验已撤销，请退回原申请后重新申请");
    }
    private static void addReviewReason(WithdrawalRecord row, String reason) {
        List<String> reasons = row.getReviewReasons() == null ? new ArrayList<>()
            : new ArrayList<>(JSON.parseArray(row.getReviewReasons(), String.class));
        if (!reasons.contains(reason)) reasons.add(reason);
        row.setReviewReasons(JSON.toJSONString(reasons));
    }
    private void channelLimits(WithdrawalPolicy p, long userId, BigDecimal amount, LocalDateTime start, LocalDateTime end, boolean submitted) {
        maximum(p.channelSingle(), amount, "超过通道单笔限额");
        maximum(p.channelDaily(), mapper.channelAmount(p.channel(), null, start, end, submitted).add(amount), "超过通道单日限额");
        maximum(p.channelUserDaily(), mapper.channelAmount(p.channel(), userId, start, end, submitted).add(amount), "超过通道单用户单日限额");
    }
    private static void maximum(BigDecimal maximum, BigDecimal amount, String message) {
        if (maximum.signum() > 0 && amount.compareTo(maximum) > 0) throw new ServiceException(message);
    }
    private static void countLimit(long maximum, long used, String message) { if (maximum > 0 && used >= maximum) throw new ServiceException(message); }
    private WithdrawalPolicy policy() { return WithdrawalPolicy.parse(mapper.selectPolicy(), mapper.configuration("commission.min.withdraw")); }
    private LedgerService ledger() {
        LedgerService ledger = ledgers.getIfAvailable();
        if (ledger == null) throw new ServiceException("提现申请暂未开放");
        return ledger;
    }
    private WithdrawChannel channel(String name) { return "mock".equals(name) ? channels.getIfAvailable() : null; }
    private void admissionLock() { if (mapper.lockAdmission() == null) throw new ServiceException("提现控制未初始化"); }
    private WithdrawalRecord lock(String no) {
        WithdrawalRecord read = requireRow(mapper.findByNo(no));
        if (mapper.lockAccount(read.getUserId()) == null) throw new ServiceException("账户不存在");
        return requireRow(mapper.lockWithdrawal(no));
    }
    private WithdrawalAccount activeAccount(long userId) {
        WithdrawalAccount account = mapper.lockAccount(userId);
        if (account == null || !"0".equals(account.status())) throw new ServiceException("账户已冻结或不存在，不能提现");
        return account;
    }
    private static WithdrawalRecord requireRow(WithdrawalRecord row) { if (row == null) throw new ServiceException("提现单不存在"); return row; }
    private void audit(WithdrawalRecord row, String action, WithdrawalStatus from, Long operatorId, String reason) {
        mapper.audit(row.getWithdrawNo(), row.getUserId(), action, from == null ? null : from.name(), row.getStatus().name(), operatorId, reason, now());
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static LocalDateTime utc(LocalDate date, WithdrawalPolicy p) { return LocalDateTime.ofInstant(date.atStartOfDay(p.businessTimezone()).toInstant(), ZoneOffset.UTC); }
    private static void validUser(long userId) { if (userId <= 0) throw new ServiceException("无效用户"); }
    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw new ServiceException(label + "不能为空且不能超长");
        return value.trim();
    }
    public static String recipientHash(String channel, String account) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((channel + "\n" + account.trim()).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
