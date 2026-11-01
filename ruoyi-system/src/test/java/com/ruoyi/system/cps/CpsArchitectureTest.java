package com.ruoyi.system.cps;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.service.ISysAgentCommissionAccountService;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;
import com.ruoyi.system.service.impl.SysAgentCommissionAccountServiceImpl;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 交接第 1 步的架构规则，依据 AGENTS.md 铁规则 1（只有 ledger 能改余额）和「模块分工」
 * 的调用方向 {@code order → attribution → settlement → ledger}（只能单向）。
 *
 * <p>只扫 {@code com.ruoyi.system} 的主代码，不含测试类。控制器在 ruoyi-admin 模块，
 * 不在本模块的 classpath 上，规则按包名判断，将来代码挪进本模块同样生效。
 *
 * <p>现有违反全部写成精确例外并注明原因，见 {@link #LEGACY_ACCOUNT_SERVICE}、
 * {@link #LEGACY_ACCOUNTING_BOUNDARY}，迁移完成前不要扩大例外范围。
 */
public class CpsArchitectureTest {

    private static final String SYSTEM = "com.ruoyi.system";
    private static final String CPS = SYSTEM + ".cps";

    /** 账务包：唯一可以改余额的地方。 */
    private static final String LEDGER = CPS + ".ledger..";

    /** 控制器包：业务包不允许反向依赖。 */
    private static final String CONTROLLER = "com.ruoyi.web.controller..";

    /** 调用方向，越靠后越底层。 */
    private static final String[] CHAIN = { "order", "attribution", "settlement", "ledger" };

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(SYSTEM);

    /**
     * 能改账户金额的方法：账户行的写入（见 SysAgentCommissionAccountMapper.xml 的 insert、update）
     * 和统一入账入口 CommissionCalculateServiceImpl.updateUserAccount。
     */
    private static final Object[][] BALANCE_WRITES = {
            { SysAgentCommissionAccountMapper.class, "insertSysAgentCommissionAccount" },
            { SysAgentCommissionAccountMapper.class, "updateSysAgentCommissionAccount" },
            { ISysAgentCommissionAccountService.class, "insertSysAgentCommissionAccount" },
            { ISysAgentCommissionAccountService.class, "updateSysAgentCommissionAccount" },
            { SysAgentCommissionAccountServiceImpl.class, "insertSysAgentCommissionAccount" },
            { SysAgentCommissionAccountServiceImpl.class, "updateSysAgentCommissionAccount" },
            { CommissionCalculateServiceImpl.class, "updateUserAccount" },
    };

    private static final Set<String> BALANCE_WRITE_KEYS = new HashSet<String>();
    static {
        for (Object[] write : BALANCE_WRITES) {
            BALANCE_WRITE_KEYS.add(((Class<?>) write[0]).getName() + "#" + write[1]);
        }
    }

    /**
     * 精确列出当前遗留写入边界：账户 Service 只允许调用两个账户 Mapper 写方法；
     * 旧账务类只允许调用账户更新方法及其内部旧入账入口；ledger 只允许调用统一入账入口。
     * 迁移完成后删除对应关系，不要用整类豁免扩大范围。
     */
    private static final String LEGACY_ACCOUNT_SERVICE = SysAgentCommissionAccountServiceImpl.class.getName();
    private static final String LEGACY_ACCOUNTING_BOUNDARY = CommissionCalculateServiceImpl.class.getName();
    private static final String LEDGER_BOUNDARY = CPS + ".ledger.DefaultLedgerService";

    private static final DescribedPredicate<JavaMethodCall> BALANCE_WRITE =
            new DescribedPredicate<JavaMethodCall>(
                    "调用余额写入方法（updateUserAccount、insert/updateSysAgentCommissionAccount）") {
                @Override
                public boolean test(JavaMethodCall call) {
                    if (!BALANCE_WRITE_KEYS.contains(call.getTargetOwner().getName() + "#" + call.getName())) {
                        return false;
                    }
                    return !isLegacyBalanceWriteCall(call);
                }
            };

    private static boolean isLegacyBalanceWriteCall(JavaMethodCall call) {
        String origin = call.getOriginOwner().getName();
        String target = call.getTargetOwner().getName();
        String method = call.getName();
        if (LEGACY_ACCOUNT_SERVICE.equals(origin)
                && SysAgentCommissionAccountMapper.class.getName().equals(target)
                && ("insertSysAgentCommissionAccount".equals(method)
                        || "updateSysAgentCommissionAccount".equals(method))) {
            return true;
        }
        if (LEGACY_ACCOUNTING_BOUNDARY.equals(origin)) {
            // 当前类的旧流程内部调用，以及其直接更新账户行的两个既有位置。
            return (LEGACY_ACCOUNTING_BOUNDARY.equals(target) && "updateUserAccount".equals(method))
                    || (SysAgentCommissionAccountMapper.class.getName().equals(target)
                            && "updateSysAgentCommissionAccount".equals(method));
        }
        // 新 ledger 入口暂由历史 CommissionCalculateServiceImpl 承接。
        return LEDGER_BOUNDARY.equals(origin)
                && LEGACY_ACCOUNTING_BOUNDARY.equals(target)
                && "updateUserAccount".equals(method);
    }

    private static String pkg(String name) {
        return CPS + "." + name + "..";
    }

    private static void check(ArchRule rule) {
        rule.check(CLASSES);
    }

    // ---- 账务写入归属：只有 ledger 能改余额 ----

    @Test
    public void onlyLedgerWritesAccountBalance() {
        check(noClasses().that().resideInAPackage(SYSTEM + "..")
                .and().resideOutsideOfPackage(LEDGER)
                .should().callMethodWhere(BALANCE_WRITE)
                .because("AGENTS.md 铁规则 1：只有 ledger 能改余额，其他代码调 LedgerService.post；"
                        + "SysAgentCommissionAccountServiceImpl、CommissionCalculateServiceImpl 是 ledger "
                        + "委托的历史入账边界，迁移到 ledger 前保留例外"));
    }

    /** 方法改名时让本测试失败，避免规则悄悄失效。 */
    @Test
    public void balanceWriteMethodsStillExist() {
        for (Object[] write : BALANCE_WRITES) {
            Class<?> owner = (Class<?>) write[0];
            String name = (String) write[1];
            boolean found = false;
            for (Method method : owner.getMethods()) {
                if (method.getName().equals(name)) {
                    found = true;
                }
            }
            assertTrue(owner.getName() + " 没有方法 " + name + "，请同步更新余额写入方法清单", found);
        }
    }

    // ---- 业务不依赖控制器 ----

    @Test
    public void businessDoesNotDependOnControllers() {
        check(noClasses().that().resideInAPackage(SYSTEM + "..")
                .should().dependOnClassesThat().resideInAnyPackage(CONTROLLER)
                .because("AGENTS.md「模块分工」：控制器只做入参和调用，业务写在业务包里，业务包不能依赖控制器"));
    }

    // ---- 依赖方向：order -> attribution -> settlement -> ledger，单向 ----

    @Test
    public void ledgerDoesNotDependOnUpperLayers() {
        check(noClasses().that().resideInAPackage(pkg("ledger"))
                .should().dependOnClassesThat().resideInAnyPackage(pkg("settlement"), pkg("attribution"), pkg("order"))
                .because("调用方向只能是 order -> attribution -> settlement -> ledger，ledger 在最底层，不能反向依赖"));
    }

    @Test
    public void settlementDoesNotDependOnUpperLayers() {
        check(noClasses().that().resideInAPackage(pkg("settlement"))
                .should().dependOnClassesThat().resideInAnyPackage(pkg("attribution"), pkg("order"))
                .because("调用方向只能是 order -> attribution -> settlement -> ledger，settlement 不能依赖 attribution 和 order"));
    }

    @Test
    public void attributionDoesNotDependOnOrder() {
        check(noClasses().that().resideInAPackage(pkg("attribution"))
                .should().dependOnClassesThat().resideInAPackage(pkg("order"))
                .because("调用方向只能是 order -> attribution -> settlement -> ledger，attribution 不能依赖 order"));
    }

    /** 上面三条覆盖了四个包之间全部六条反向边，等价于这四个包之间无循环依赖，这里再直接算一遍。 */
    @Test
    public void businessChainHasNoCyclicDependencies() {
        Map<String, Set<String>> edges = new HashMap<String, Set<String>>();
        for (String layer : CHAIN) {
            edges.put(layer, new LinkedHashSet<String>());
        }
        for (JavaClass javaClass : CLASSES) {
            String from = chainLayer(javaClass);
            if (from == null) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                String to = chainLayer(dependency.getTargetClass());
                if (to != null && !to.equals(from)) {
                    edges.get(from).add(to);
                }
            }
        }
        for (String layer : CHAIN) {
            String cycle = findCycle(layer, edges, new ArrayList<String>(), new HashSet<String>());
            if (cycle != null) {
                fail("业务包之间出现循环依赖：" + cycle
                        + "；AGENTS.md「模块分工」要求 order -> attribution -> settlement -> ledger 单向");
            }
        }
    }

    @Test
    public void contractDoesNotDependOnBusinessPackages() {
        check(noClasses().that().resideInAPackage(pkg("contract"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        pkg("attribution"), pkg("order"), pkg("rule"), pkg("settlement"), pkg("ledger"),
                        pkg("withdraw"), pkg("recon"), pkg("risk"), pkg("mapper"))
                .because("contract 是各模块共用的契约，只能被业务包依赖，不能反过来依赖业务包"));
    }

    private static String chainLayer(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        for (String layer : CHAIN) {
            if (packageName.equals(CPS + "." + layer) || packageName.startsWith(CPS + "." + layer + ".")) {
                return layer;
            }
        }
        return null;
    }

    /** 深度优先找一条回路，返回形如 {@code order -> attribution -> order} 的描述，没有回路返回 null。 */
    private static String findCycle(String node, Map<String, Set<String>> edges, List<String> path, Set<String> done) {
        int index = path.indexOf(node);
        if (index >= 0) {
            List<String> circle = new ArrayList<String>(path.subList(index, path.size()));
            circle.add(node);
            StringBuilder text = new StringBuilder();
            for (String layer : circle) {
                if (text.length() > 0) {
                    text.append(" -> ");
                }
                text.append(layer);
            }
            return text.toString();
        }
        if (!done.add(node)) {
            return null;
        }
        path.add(node);
        for (String next : edges.get(node)) {
            String cycle = findCycle(next, edges, path, done);
            if (cycle != null) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        return null;
    }
}
