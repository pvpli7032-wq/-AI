package com.ruoyi.system.cps.risk;

import com.ruoyi.common.exception.ServiceException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only member projection. Never returns WeChat identifiers, credentials or session secrets. */
@Service
public class MemberQueryService {
    private final JdbcTemplate jdbc;
    public record Query(Long userId, String nickname, String phone, String riskLevel, int pageNum, int pageSize) {}
    public record Page(List<MemberView> rows, long total, int pageNum, int pageSize) {}
    public record MemberView(long userId, String nickname, String avatar, String phoneMasked,
        String riskLevel, String invitationCode, Long inviterUserId, LocalDateTime createTime,
        String balance, String frozenAmount, String withdrawingAmount, String withdrawn,
        String totalEarned, String accountStatus) {}

    public MemberQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Page list(Query query) {
        if (query == null || query.pageNum() < 1 || query.pageSize() < 1 || query.pageSize() > 100)
            throw new ServiceException("Invalid member page");
        Query conditions = filters(query);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (conditions.userId() != null) { where.append(" AND w.user_id=?"); args.add(conditions.userId()); }
        if (conditions.nickname() != null) { where.append(" AND w.nickname LIKE ?"); args.add("%"+conditions.nickname()+"%"); }
        if (conditions.phone() != null) { where.append(" AND w.phone LIKE ?"); args.add("%"+conditions.phone()+"%"); }
        if (conditions.riskLevel() != null) { where.append(" AND w.risk_level=?"); args.add(conditions.riskLevel()); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_wechat_user w"+where, Long.class, args.toArray());
        args.add(query.pageSize()); args.add((long)(query.pageNum()-1)*query.pageSize());
        List<MemberView> rows = jdbc.query(select()+where+" ORDER BY w.user_id LIMIT ? OFFSET ?", this::map, args.toArray());
        return new Page(rows,total == null ? 0 : total,query.pageNum(),query.pageSize());
    }

    @Transactional(readOnly = true)
    public MemberView detail(long userId) {
        if (userId <= 0) throw new ServiceException("Invalid member user");
        return jdbc.query(select()+" WHERE w.user_id=?",this::map,userId).stream().findFirst().orElse(null);
    }

    private String select() {
        return """ SELECT w.user_id,w.nickname,w.avatar,w.phone,w.risk_level,w.invitation_code,w.inviter_user_id,w.create_time,
            a.balance,a.frozen_amount,a.withdrawing_amount,a.withdrawn,a.total_earned,a.status
            FROM sys_wechat_user w LEFT JOIN sys_agent_commission_account a ON a.user_id=w.user_id """;
    }
    private MemberView map(ResultSet r, int n) throws SQLException {
        var created = r.getTimestamp("create_time");
        return new MemberView(r.getLong("user_id"),r.getString("nickname"),r.getString("avatar"),
            maskPhone(r.getString("phone")),r.getString("risk_level"),r.getString("invitation_code"),
            (Long)r.getObject("inviter_user_id"),created == null ? null : created.toLocalDateTime(),
            money(r,"balance"),money(r,"frozen_amount"),money(r,"withdrawing_amount"),
            money(r,"withdrawn"),money(r,"total_earned"),r.getString("status"));
    }
    private static String money(ResultSet r,String column) throws SQLException {
        BigDecimal value = r.getBigDecimal(column);
        return value == null ? null : value.setScale(2,RoundingMode.HALF_UP).toPlainString();
    }
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String trimmed = phone.trim();
        if (trimmed.length() < 7) return "***";
        return trimmed.substring(0,3)+"****"+trimmed.substring(trimmed.length()-4);
    }
    private static Query filters(Query q) {
        Long id = q.userId();
        if (id != null && id <= 0) throw new ServiceException("Invalid member user");
        return new Query(id,text(q.nickname(),80),text(q.phone(),32),text(q.riskLevel(),32),q.pageNum(),q.pageSize());
    }
    private static String text(String value,int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length()>max) throw new ServiceException("Member filter too long");
        return trimmed;
    }
}
