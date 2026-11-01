package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.PunishmentEntryMapper;

@Service
public class PunishmentImportService {
    private final PunishmentEntryMapper mapper;
    public PunishmentImportService(PunishmentEntryMapper mapper) { this.mapper = mapper; }

    @Transactional(rollbackFor = Exception.class)
    public long importLines(String platform, String file, Long operator, List<PunishmentLine> rows) {
        if (platform == null || !Set.of("tbk", "jd", "pdd").contains(platform) || operator == null || operator <= 0
                || file == null || file.isBlank() || file.length() > 255
                || !file.matches("[\\p{L}\\p{N}._ -]+")
                || rows == null || rows.isEmpty() || rows.size() > 1000) {
            throw new ServiceException("导入参数无效");
        }
        for (PunishmentLine row : rows) {
            if (row == null || row.getOrderNo() == null || !row.getOrderNo().matches("[A-Za-z0-9_-]{1,100}")
                    || (row.getSubOrderNo() != null && !row.getSubOrderNo().matches("[A-Za-z0-9_-]{0,100}"))
                    || row.getPenaltyAmount() == null || row.getPenaltyAmount().signum() < 0
                    || row.getPenaltyAmount().scale() > 2
                    || row.getPenaltyAmount().compareTo(new BigDecimal("999999999999.99")) > 0) {
                throw new ServiceException("处罚行格式或金额无效");
            }
            if (row.getSubOrderNo() == null) row.setSubOrderNo("");
        }
        PunishmentImport record = new PunishmentImport();
        record.setPlatform(platform); record.setFile(file); record.setOperator(operator); record.setLines(rows.size());
        if (mapper.insertImport(record) != 1 || record.getId() == null) throw new ServiceException("处罚导入记录保存失败");
        for (int i = 0; i < rows.size(); i++) {
            if (mapper.insertLine(record.getId(), i + 1, rows.get(i)) != 1) throw new ServiceException("处罚明细保存失败");
        }
        return record.getId();
    }
}
