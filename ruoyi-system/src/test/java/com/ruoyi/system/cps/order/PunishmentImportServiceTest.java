package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.PunishmentEntryMapper;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class PunishmentImportServiceTest {
    private PunishmentLine row(String order, String amount) {
        PunishmentLine line = new PunishmentLine();
        line.setOrderNo(order); line.setPenaltyAmount(new BigDecimal(amount)); return line;
    }
    @Test public void writesAuthenticatedOperatorAndEveryActualRow() {
        PunishmentEntryMapper mapper = mock(PunishmentEntryMapper.class);
        when(mapper.insertImport(any())).thenAnswer(call -> {
            PunishmentImport record = call.getArgument(0);
            assertEquals(Long.valueOf(7), record.getOperator());
            assertEquals(2, record.getLines()); record.setId(11L); return 1;
        });
        when(mapper.insertLine(anyLong(), anyInt(), any())).thenReturn(1);
        assertEquals(11L, new PunishmentImportService(mapper).importLines("tbk", "test.csv", 7L,
            List.of(row("order-a", "1.20"), row("order-b", "0.30"))));
        verify(mapper, times(2)).insertLine(eq(11L), anyInt(), any());
    }
    @Test public void rejectsInvalidAndOversizedImportBeforeWriting() {
        PunishmentEntryMapper mapper = mock(PunishmentEntryMapper.class);
        PunishmentImportService service = new PunishmentImportService(mapper);
        assertThrows(ServiceException.class, () -> service.importLines(null, "test.csv", 7L, List.of(row("a", "1"))));
        assertThrows(ServiceException.class, () -> service.importLines("tbk", "../secret", 7L, List.of(row("a", "1"))));
        assertThrows(ServiceException.class, () -> service.importLines("tbk", "test.csv", 7L, List.of(row("a", "-1"))));
        assertThrows(ServiceException.class, () -> service.importLines("tbk", "test.csv", 7L, new ArrayList<>(java.util.Collections.nCopies(1001, row("a", "1")))));
        verifyNoInteractions(mapper);
    }
}
