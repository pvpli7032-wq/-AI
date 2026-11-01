package com.ruoyi.web.controller.cps;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.contract.AdminDictionaryService;

/** Authenticated, read-only shared CPS dictionaries for administration pages. */
@RestController
@RequestMapping("/admin/dict")
public class CpsAdminDictionaryController extends BaseController {
    private final AdminDictionaryService dictionaries;
    public CpsAdminDictionaryController(AdminDictionaryService dictionaries) { this.dictionaries = dictionaries; }

    @GetMapping
    public AjaxResult dictionary() {
        Map<String, Object> data = dictionaries.dictionary();
        return success(data);
    }
}
