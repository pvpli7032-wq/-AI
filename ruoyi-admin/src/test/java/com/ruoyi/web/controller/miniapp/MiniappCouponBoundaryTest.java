package com.ruoyi.web.controller.miniapp;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.cps.attribution.*;
import com.ruoyi.system.service.IDtkApiService;
import com.ruoyi.system.service.IJdSdkService;
import com.ruoyi.system.service.IPddApiService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.web.controller.cps.CpsAttributionController;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

/** HTTP identity boundary and real server response -> miniapp adapter -> copy-button compatibility. */
public class MiniappCouponBoundaryTest {
    private CouponConversionService conversion;
    private MiniappTbkController tbk;
    private MockMvc mvc;

    @Before public void setup() {
        SecurityContextHolder.clearContext();
        conversion = mock(CouponConversionService.class);
        tbk = new MiniappTbkController();
        ReflectionTestUtils.setField(tbk, "couponConversionService", conversion);
        mvc = MockMvcBuilders.standaloneSetup(new MiniappConvertController(conversion), tbk).build();
    }
    @After public void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test public void convertUsesAuthenticatedViewerDespiteForgedQueryParameters() throws Exception {
        authenticate(41L);
        when(conversion.convert(eq(41L), anyString(), eq(Platform.JD), eq("123"))).thenReturn(success(Platform.JD, "https://example.invalid/jd"));
        mvc.perform(post("/miniapp/convert/link").param("userId", "999").param("pid", "other-pid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"JD\",\"itemId\":\"123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(conversion).convert(eq(41L), anyString(), eq(Platform.JD), eq("123"));
        assertEquals(Set.of("platform", "itemId"), Arrays.stream(MiniappConvertController.LinkRequest.class.getRecordComponents())
                .map(component -> component.getName()).collect(Collectors.toSet()));
    }

    @Test public void parseAllowsAnonymousBrowsingWithoutAdoptingUserId() throws Exception {
        when(conversion.parse(isNull(), anyString(), eq(Platform.TBK), eq("product-input"))).thenReturn(success(Platform.TBK, null));
        mvc.perform(post("/miniapp/convert/parse").param("userId", "999").param("invitationCode", "sender")
                .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"tbk\",\"input\":\"product-input\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(conversion).parse(isNull(), anyString(), eq(Platform.TBK), eq("product-input"));
        assertNotNull(MiniappConvertController.class.getMethod("parse", MiniappConvertController.ParseRequest.class).getAnnotation(Anonymous.class));
        assertNotNull(MiniappConvertController.class.getMethod("link", MiniappConvertController.LinkRequest.class).getAnnotation(Anonymous.class));
    }

    @Test public void suppliedPromotionUrlsCannotReachConversion() throws Exception {
        for (String url : List.of("https://example.invalid/?pid=other", "https://example.invalid/?%70%69%64=other", "//example.invalid/?pid=other")) {
            mvc.perform(get("/miniapp/tbk/getTpwd").param("goodsId", "123").param("url", url))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
        }
        verifyNoInteractions(conversion);
    }

    @Test public void legacyTpwdRebuildsFromGoodsIdAndKeepsCopyableModel() throws Exception {
        authenticate(41L);
        when(conversion.convert(eq(41L), anyString(), eq(Platform.TBK), eq("123"))).thenReturn(success(Platform.TBK, "TEST_TOKEN_ONLY"));
        mvc.perform(get("/miniapp/tbk/getTpwd").param("goodsId", "123").param("userId", "999").param("invitationCode", "sender"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.model").value("TEST_TOKEN_ONLY"))
                .andExpect(jsonPath("$.data.valueType").value("TPWD"));
        verify(conversion).convert(eq(41L), anyString(), eq(Platform.TBK), eq("123"));
    }

    @Test public void everyBackofficeAttributionRouteHasTheUnifiedPermission() {
        int mapped = 0;
        for (Method method : CpsAttributionController.class.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) continue;
            mapped++;
            PreAuthorize permission = method.getAnnotation(PreAuthorize.class);
            assertNotNull(method.getName() + " must require a permission", permission);
            assertTrue(method.getName() + ": " + permission.value(),
                    permission.value().matches("@ss\\.hasPermi\\('system:commission:[a-z]+:[a-z]+'\\)"));
        }
        assertTrue("Backoffice route inspection must not be vacuous", mapped >= 7);
    }

    @Test public void detailRemovesNestedCommissionAmountsWithoutInventingRebate() {
        JSONObject source = JSON.parseObject("{\"price\":\"10.00\",\"publishInfo\":{\"incomeInfo\":{\"commissionAmount\":\"5.00\"}},\"commission_amount\":\"5.00\"}");
        JSONObject result = CouponPresentation.detail(source);
        assertEquals("10.00", result.getString("price"));
        assertNull(result.get("estimatedRebate"));
        assertFalse(result.containsKey("commission_amount"));
        assertFalse(result.getJSONObject("publishInfo").containsKey("incomeInfo"));
        assertNotNull("Input response must not be modified", source.getJSONObject("publishInfo").get("incomeInfo"));
    }

    @Test public void detailRemovesNestedForeignPromotionAliasesButKeepsProductImages() {
        JSONObject source = JSON.parseObject("{\"itemId\":\"123\",\"title\":\"test\",\"publishInfo\":{\"couponShareUrl\":\"https://foreign.invalid/?pid=other\",\"clickURL\":\"https://foreign.invalid/a\",\"shortURL\":\"https://foreign.invalid/b\"},\"images\":[{\"url\":\"https://images.invalid/product.png\"}]}");
        JSONObject result = CouponPresentation.detail(source);
        assertEquals("123", result.getString("itemId"));
        assertEquals("test", result.getString("title"));
        assertTrue(result.getJSONObject("publishInfo").isEmpty());
        assertEquals("https://images.invalid/product.png", result.getJSONArray("images").getJSONObject(0).getString("url"));
        assertTrue(source.getJSONObject("publishInfo").containsKey("couponShareUrl"));
    }

    @Test public void serverResponsesReachRealMiniappCopyButtonsAcrossPlatforms() throws Exception {
        List<Map<String,Object>> fixtures = new ArrayList<>();
        fixtures.add(fixture("tbk", "TEST_TBK_TOKEN", legacyTbk("TEST_TBK_TOKEN")));
        fixtures.add(fixture("tbk", "https://example.invalid/tbk", legacyTbk("https://example.invalid/tbk")));
        CouponDtkApiService dtk = new CouponDtkApiService(mock(IDtkApiService.class), conversion);
        for (String value : List.of("TEST_DTK_TOKEN", "https://example.invalid/dtk")) {
            when(conversion.convert(isNull(), anyString(), eq(Platform.TBK), eq("123"))).thenReturn(success(Platform.TBK, value));
            JSONObject response = dtk.getPrivilegeLink("123", null, "ignored-pid", "title", null, null);
            if (value.startsWith("https:")) assertFalse(response.getJSONObject("data").containsKey("tpwd"));
            fixtures.add(fixture("dtk", value, AjaxResult.success(response)));
        }
        when(conversion.convert(isNull(), anyString(), eq(Platform.PDD), eq("123"))).thenReturn(success(Platform.PDD, "https://example.invalid/pdd"));
        CouponPddApiService pdd = new CouponPddApiService(mock(IPddApiService.class), conversion);
        fixtures.add(fixture("pdd", "https://example.invalid/pdd", AjaxResult.success(pdd.generatePromotionUrl("123", 999L, null, null))));
        when(conversion.convert(isNull(), anyString(), eq(Platform.JD), eq("123"))).thenReturn(success(Platform.JD, "https://example.invalid/jd"));
        CouponJdApiService jd = new CouponJdApiService(mock(IJdSdkService.class), conversion, mock(CouponCatalogService.class));
        fixtures.add(fixture("jd", "https://example.invalid/jd", AjaxResult.success(jd.getPromotionLink(Map.of("materialId", "123")))));
        runMiniapp(fixtures);
    }

    private AjaxResult legacyTbk(String value) {
        when(conversion.convert(isNull(), anyString(), eq(Platform.TBK), eq("123"))).thenReturn(success(Platform.TBK, value));
        return tbk.getTpwd("title", null, "123", null, null, "ignored", 999L);
    }
    private static Map<String,Object> fixture(String platform, String value, AjaxResult response) {
        return Map.of("platform", platform, "value", value, "response", response);
    }
    private static CouponResult success(Platform platform, String value) {
        return new CouponResult("SUCCESS", platform, "123", BigDecimal.ONE, BigDecimal.TEN, null,
                "PENDING_RULE_CONTRACT", value, "UNATTRIBUTED", "测试成功", List.of());
    }
    private static void authenticate(Long id) {
        SysUser user = new SysUser(); user.setUserId(id);
        LoginUser login = new LoginUser(id, null, user, Collections.emptySet());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }
    private static void runMiniapp(List<Map<String,Object>> fixtures) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("tbk-miniapp"))) root = root.getParent();
        assertNotNull(root);
        Path input = Files.createTempFile("line9-link-fixtures-", ".json");
        Path output = Files.createTempFile("line9-link-consumers-", ".log");
        try {
            Files.writeString(input, JSON.toJSONString(fixtures));
            Process process = new ProcessBuilder("node", "-e", SCRIPT, root.toString(), input.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue("Miniapp copy-button test timed out", finished);
            String log = Files.readString(output);
            assertEquals(log, 0, process.exitValue());
            assertTrue(log, log.contains("all legacy coupon copy buttons passed"));
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }
    private static final String SCRIPT = """
        const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
        const root=process.argv[1],fixtures=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
        let fixture, copied, toast;
        const app={globalData:{}};
        const wx={showLoading:()=>{},hideLoading:()=>{},showToast:x=>{toast=x.title;},setClipboardData:x=>{copied=x.data;x.success?.();}};
        const request={optionalGet:async()=>fixture.response};
        const quiet={log:()=>{},warn:()=>{},error:()=>{}};
        function load(file,mocks={},capture){
          const filename=path.join(root,file),module={exports:{}};
          vm.runInNewContext(fs.readFileSync(filename,'utf8'),{module,exports:module.exports,wx,getApp:()=>app,Page:capture,console:quiet,
            require:name=>Object.hasOwn(mocks,name)?mocks[name]:require(path.resolve(path.dirname(filename),name))},{filename});
          return module.exports;
        }
        (async()=>{
          const api=load('tbk-miniapp/api/platform-api.js',{'../utils/request.js':request});
          let home,detail;
          load('tbk-miniapp/pages/index/index.js',{'../../utils/request.js':request,'../../api/platform-api.js':api},v=>home=v);
          load('tbk-miniapp/pages/goods/detail/detail.js',{'../../../utils/request.js':request,'../../../api/platform-api.js':api},v=>detail=v);
          home.setData=v=>Object.assign(home.data,v);detail.setData=v=>Object.assign(detail.data,v);
          for(fixture of fixtures){
            const adapted=await api.getTpwd(fixture.platform,{goodsId:'123'});
            assert.equal(adapted.code,200);
            assert.equal(adapted.data.data.model,fixture.value);
            // Still exercise the real legacy adapter envelopes when no uniform-value path is present.
            if(fixture.platform==='dtk'&&!fixture.value.startsWith('http')){
              const raw=require(path.join(root,'tbk-miniapp/adapters/dtk-adapter.js')).adaptTpwdResponse(fixture.response);
              assert.equal(raw.data.data.model,fixture.value);
            }
            if(fixture.platform==='pdd'){
              const raw=require(path.join(root,'tbk-miniapp/adapters/pdd-adapter.js')).adaptTpwdResponse(fixture.response);
              assert.equal(raw.data.mobileShortUrl,fixture.value);
            }
            const goods={id:'123',platform:fixture.platform,title:'test',mainPic:'https://image.invalid/x.png'};
            home.data.currentPlatform=fixture.platform;copied=null;toast=null;
            home.onGetTpwd({currentTarget:{dataset:{goods}}});
            await new Promise(resolve=>setImmediate(resolve));
            assert.equal(copied,fixture.value,'home '+fixture.platform+': '+toast);
            detail.data.currentPlatform=fixture.platform;detail.data.goods=goods;copied=null;toast=null;
            detail.onGetTpwd();
            await new Promise(resolve=>setImmediate(resolve));
            assert.equal(copied,fixture.value,'detail '+fixture.platform+': '+toast);
          }
          console.log('all legacy coupon copy buttons passed');
        })().catch(error=>{console.error(error);process.exitCode=1;});
        """;
}
