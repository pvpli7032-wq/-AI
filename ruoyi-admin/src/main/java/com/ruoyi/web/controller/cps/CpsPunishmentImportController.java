package com.ruoyi.web.controller.cps;

import java.util.List;
import java.util.Set;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.order.PunishmentImportService;
import com.ruoyi.system.cps.order.PunishmentLine;

@RestController
@RequestMapping("/cps/punishments")
public class CpsPunishmentImportController extends BaseController {
    private final PunishmentImportService service;
    public CpsPunishmentImportController(PunishmentImportService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('cps:order:punishImport')")
    @PostMapping(value = "/import", consumes = "application/json")
    public AjaxResult importLines(HttpServletRequest http) throws IOException {
        final int maxBytes = 256 * 1024;
        if (http.getContentLengthLong() > maxBytes) return error("处罚导入内容过大");
        byte[] payload = http.getInputStream().readNBytes(maxBytes + 1);
        if (payload.length > maxBytes) return error("处罚导入内容过大");
        ImportRequest request;
        try {
            JSONObject object = JSON.parseObject(payload);
            if (object == null || !Set.of("platform", "file", "lines").containsAll(object.keySet()))
                return error("处罚导入包含不支持的字段");
            JSONArray rows = object.getJSONArray("lines");
            if (rows == null || rows.size() > 1000) return error("处罚导入明细无效");
            for (Object value : rows) {
                if (!(value instanceof JSONObject row)
                    || !Set.of("orderNo", "subOrderNo", "penaltyAmount").containsAll(row.keySet()))
                    return error("处罚明细包含不支持的字段");
            }
            request = object.toJavaObject(ImportRequest.class);
        } catch (JSONException | ClassCastException invalid) { return error("处罚导入内容格式无效"); }
        if (request == null) return error("处罚导入内容格式无效");
        return success(service.importLines(request.getPlatform(), request.getFile(), getUserId(), request.getLines()));
    }

    public static class ImportRequest {
        private String platform;
        private String file;
        private List<PunishmentLine> lines;
        public String getPlatform() { return platform; }
        public void setPlatform(String platform) { this.platform = platform; }
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public List<PunishmentLine> getLines() { return lines; }
        public void setLines(List<PunishmentLine> lines) { this.lines = lines; }
    }
}
