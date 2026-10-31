package com.ruoyi.system.cps;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.service.ISysAgentCommissionAccountService;
import com.ruoyi.system.service.impl.SysAgentCommissionAccountServiceImpl;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 第二批业务包（com.ruoyi.system.cps）的架构规则，依据 docs/plan.md 第 5.1 节、
 * docs/tasks/batch-02.md 共同约定、AGENTS.md 金额和账务硬规则。
 * 只扫描 cps 包的主代码，不含测试类。尚不存在的包用 allowEmptyShould，等对应线落地后规则自动生效。
 */
public class CpsArchitectureTest {

    private static final String CPS = "com.ruoyi.system.cps";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(CPS);

    private static String pkg(String name) {
        return CPS + "." + name + "..";
    }

    private static void check(ArchRule rule) {
        rule.check(CLASSES);
    }

    // ---- 依赖方向：order -> attribution -> settlement -> ledger ----

    @Test
    public void ledgerDoesNotDependOnUpperLayers() {
        check(noClasses().that().resideInAPackage(pkg("ledger"))
                .should().dependOnClassesThat().resideInAnyPackage(pkg("settlement"), pkg("attribution"), pkg("order"))
                .allowEmptyShould(true)
                .because("依赖方向只能是 order -> attribution -> settlement -> ledger，ledger 在最底层，不能反向依赖"));
    }

    @Test
    public void settlementDoesNotDependOnUpperLayers() {
        check(noClasses().that().resideInAPackage(pkg("settlement"))
                .should().dependOnClassesThat().resideInAnyPackage(pkg("attribution"), pkg("order"))
                .allowEmptyShould(true)
                .because("依赖方向只能是 order -> attribution -> settlement -> ledger，settlement 不能依赖 attribution 和 order"));
    }

    @Test
    public void attributionDoesNotDependOnOrder() {
        check(noClasses().that().resideInAPackage(pkg("attribution"))
                .should().dependOnClassesThat().resideInAPackage(pkg("order"))
                .allowEmptyShould(true)
                .because("依赖方向只能是 order -> attribution -> settlement -> ledger，attribution 不能依赖 order"));
    }

    @Test
    public void contractDoesNotDependOnBusinessPackages() {
        check(noClasses().that().resideInAPackage(pkg("contract"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        pkg("attribution"), pkg("order"), pkg("rule"), pkg("settlement"), pkg("ledger"),
                        pkg("withdraw"), pkg("recon"), pkg("risk"), pkg("mapper"))
                .allowEmptyShould(true)
                .because("contract 是各线共用的契约，只能被业务包依赖，不能反过来依赖业务包"));
    }

    // ---- 金额规则 ----

    @Test
    public void noFloatingPointFields() {
        check(noFields().that().areDeclaredInClassesThat().resideInAPackage(CPS + "..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .allowEmptyShould(true)
                .because("AGENTS.md 金额规则：金额用 BigDecimal，禁止 double 和 float"));
    }

    // ---- 只有 ledger 能写账户余额 ----

    /** 能写 balance 的方法：新增账户和整行更新（见 SysAgentCommissionAccountMapper.xml 的 insert、update）。 */
    private static final String[] BALANCE_WRITE_METHODS = {
            "insertSysAgentCommissionAccount", "updateSysAgentCommissionAccount" };

    private static final Set<String> BALANCE_WRITE_OWNERS = new HashSet<>(Arrays.asList(
            SysAgentCommissionAccountMapper.class.getName(),
            ISysAgentCommissionAccountService.class.getName(),
            SysAgentCommissionAccountServiceImpl.class.getName()));

    /** 方法改名时让本测试失败，避免规则悄悄失效。 */
    @Test
    public void balanceWriteMethodsStillExist() {
        for (Class<?> owner : new Class<?>[] { SysAgentCommissionAccountMapper.class,
                ISysAgentCommissionAccountService.class, SysAgentCommissionAccountServiceImpl.class }) {
            for (String name : BALANCE_WRITE_METHODS) {
                boolean found = false;
                for (Method m : owner.getMethods()) {
                    if (m.getName().equals(name)) {
                        found = true;
                    }
                }
                assertTrue(owner.getName() + " 没有方法 " + name + "，请同步更新本测试的余额写入方法清单", found);
            }
        }
    }

    @Test
    public void onlyLedgerWritesAccountBalance() {
        DescribedPredicate<JavaMethodCall> balanceWrite = new DescribedPredicate<JavaMethodCall>(
                "写入账户余额的方法 " + Arrays.toString(BALANCE_WRITE_METHODS)) {
            @Override
            public boolean test(JavaMethodCall call) {
                return BALANCE_WRITE_OWNERS.contains(call.getTargetOwner().getName())
                        && Arrays.asList(BALANCE_WRITE_METHODS).contains(call.getName());
            }
        };
        check(noClasses().that().resideInAPackage(CPS + "..").and().resideOutsideOfPackage(pkg("ledger"))
                .should().callMethodWhere(balanceWrite)
                .allowEmptyShould(true)
                .because("AGENTS.md：账户余额只能经统一入账方法改变，cps 内只有 ledger 能写余额，其他包调 LedgerService"));
    }
}
