#!/usr/bin/env bash
# check-architecture.sh 的测试：在临时 git 仓库里造场景，逐项验证每个失败条件。
# 用法：bash scripts/check-architecture.test.sh
set -u

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
checker="$script_dir/check-architecture.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
repo="$work/repo"
mkdir -p "$repo"
cd "$repo" || exit 1
git init -q -b main .
git config user.email test@example.com
git config user.name test
git config commit.gpgsign false
git config core.autocrlf false

cps_dir='ruoyi-system/src/main/java/com/ruoyi/system/cps'
mapper_dir='ruoyi-system/src/main/resources/mapper'

# base 上的顶层目录与已有文件
mkdir -p "$cps_dir/ledger" "$cps_dir/mapper" "$mapper_dir/cps" "$mapper_dir/system" \
  'ruoyi-system/src/main/java/com/ruoyi/system/service/impl' 'ruoyi-system/src/test/java/com/ruoyi/system/cps/ledger' \
  'ruoyi-admin/src/main/java/com/ruoyi/web/controller/cps' 'ruoyi-admin/src/main/java/com/ruoyi/web/controller/miniapp' \
  'ruoyi-admin/src/main/java/com/ruoyi/web/controller/system' 'ruoyi-admin/src/main/resources/db/migration' \
  'ruoyi-quartz/src/main/java/com/ruoyi/quartz/task' 'ruoyi-union-sdk/src/main/java/com/ruoyi/union/adapter' \
  docs scripts design tbk-miniapp
echo base > "$cps_dir/ledger/DefaultLedgerService.java"
echo base > "$cps_dir/mapper/SettleItemMapper.java"
echo base > "$mapper_dir/cps/SettleItemMapper.xml"
echo base > "$mapper_dir/system/SysUserMapper.xml"
echo base > 'ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysUserServiceImpl.java'
echo base > 'ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysUserController.java'
echo base > 'ruoyi-admin/src/main/resources/db/migration/V1__baseline.sql'
echo base > docs/handover.md
echo base > scripts/build-sdk-stub.sh
echo base > README.md
# git 不跟踪空目录：下面每个目录都要有 base 上的文件，否则检查会把它当成新增的顶层目录。
echo base > 'ruoyi-quartz/src/main/java/com/ruoyi/quartz/task/RyTask.java'
echo base > 'ruoyi-union-sdk/src/main/java/com/ruoyi/union/adapter/DefaultAdapter.java'
echo base > 'ruoyi-admin/src/main/java/com/ruoyi/web/controller/cps/BaseController.java'
echo base > 'ruoyi-admin/src/main/java/com/ruoyi/web/controller/miniapp/BaseMiniappController.java'
echo base > design/admin.md
echo base > tbk-miniapp/app.json
echo base > 'ruoyi-system/src/test/java/com/ruoyi/system/cps/ledger/LegacyLedgerTest.java'
git add -A
git commit -q -m base
git tag base0

pass=0
failn=0
case_no=0

# 在 base0 上建分支并执行 $1（函数名）改动，提交，运行检查。
# 用法：run_case <说明> <期望退出码> <改动函数> [期望输出包含的片段...]
run_case() {
  local desc=$1 want=$2 mutate=$3
  shift 3
  case_no=$((case_no + 1))
  git checkout -q -B "work/case-$case_no" base0
  "$mutate"
  git add -A
  git commit -q --allow-empty -m change
  local out code ok=true frag
  out=$(bash "$checker" base0 HEAD 2>&1)
  code=$?
  if [[ $code -ne $want ]]; then ok=false; fi
  for frag in "$@"; do
    [[ $out == *"$frag"* ]] || ok=false
  done
  if [[ $ok == true ]]; then
    pass=$((pass + 1))
    printf 'PASS %2d  %s\n' "$case_no" "$desc"
  else
    failn=$((failn + 1))
    printf 'FAIL %2d  %s（期望退出码 %s，实际 %s）\n' "$case_no" "$desc" "$want" "$code"
    printf '%s\n' "$out" | sed 's/^/        | /'
  fi
}

m_ok() {
  echo x > "$cps_dir/ledger/LedgerAuditService.java"
  echo x > "$cps_dir/mapper/NewMapper.java"
  echo x > "$mapper_dir/cps/NewMapper.xml"
  echo x > 'ruoyi-system/src/test/java/com/ruoyi/system/cps/ledger/LedgerAuditServiceTest.java'
  echo x > 'ruoyi-admin/src/main/java/com/ruoyi/web/controller/cps/NewController.java'
  echo x > 'ruoyi-admin/src/main/java/com/ruoyi/web/controller/miniapp/NewMiniappController.java'
  echo x > 'ruoyi-quartz/src/main/java/com/ruoyi/quartz/task/NewTask.java'
  echo x > 'ruoyi-union-sdk/src/main/java/com/ruoyi/union/adapter/NewAdapter.java'
}
m_top_dir() { mkdir -p tools/gen; echo x > tools/gen/Gen.java; }
m_top_file() { echo x > CHANGELOG.md; }
m_cps_root() { echo x > "$cps_dir/LedgerService.java"; }
m_java_outside() { echo x > 'ruoyi-system/src/main/java/com/ruoyi/system/service/impl/NewServiceImpl.java'; }
m_controller_outside() { echo x > 'ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/NewController.java'; }
m_mapper_xml_outside() { echo x > "$mapper_dir/system/NewMapper.xml"; }
m_modify_outside() { echo more >> 'ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysUserServiceImpl.java'; }
m_modify_top_file() { echo more >> README.md; }
m_modify_cps_root() { echo more >> "$cps_dir/ledger/DefaultLedgerService.java"; }
m_migration() { echo 'select 1;' > 'ruoyi-admin/src/main/resources/db/migration/V2__next.sql'; }
m_many() {
  mkdir -p tools; echo x > tools/Tool.java
  echo x > "$cps_dir/RootService.java"
  echo x > 'ruoyi-system/src/main/java/com/ruoyi/system/service/impl/AnotherServiceImpl.java'
}

run_case "全部合规：业务包、Mapper、测试、接口、定时任务、联盟适配" 0 m_ok "架构位置检查通过"
run_case "新增顶层目录" 1 m_top_dir "新增了顶层目录" "tools"
run_case "新增顶层文件" 1 m_top_file "CHANGELOG.md"
run_case "文件直接放在 cps/ 根目录" 1 m_cps_root "cps/ 根目录" "业务包"
run_case "Java 放在 AGENTS.md 表外（历史 service.impl）" 1 m_java_outside "不在 AGENTS.md"
run_case "后台接口放在 controller/system 下" 1 m_controller_outside "NewController.java"
run_case "Mapper XML 放在 mapper/system 下" 1 m_mapper_xml_outside "Mapper XML" "mapper/cps"
run_case "修改表外已有文件不报错（只检查新增）" 0 m_modify_outside "架构位置检查通过"
run_case "修改已有顶层文件不报错（只检查新增）" 0 m_modify_top_file "架构位置检查通过"
run_case "修改已有 cps 文件不报根目录违规" 0 m_modify_cps_root "架构位置检查通过"
run_case "新增迁移脚本不在 Java 规则范围内" 0 m_migration "架构位置检查通过"
run_case "多个问题一次全部列出" 1 m_many "tools/Tool.java" "cps/ 根目录" "AnotherServiceImpl.java" "共 3 项"

# 场景：base 在分叉后又前进，PR 只应比较到 merge-base
case_no=$((case_no + 1))
git checkout -q main
git checkout -q -B work/late base0
echo x > "$cps_dir/ledger/LateService.java"
git add -A
git commit -q -m mine
git checkout -q main
mkdir -p analytics
echo x > analytics/README.md
git add -A
git commit -q -m "main advances"
main_tip=$(git rev-parse HEAD)
git checkout -q work/late
out=$(bash "$checker" "$main_tip" HEAD 2>&1)
code=$?
if [[ $code -eq 0 && $out == *"架构位置检查通过"* ]]; then
  pass=$((pass + 1))
  printf 'PASS %2d  %s\n' "$case_no" "base 前进后：main 新增的顶层目录不算本分支的问题"
else
  failn=$((failn + 1))
  printf 'FAIL %2d  %s（退出码 %s）\n' "$case_no" "base 前进后的比较" "$code"
  printf '%s\n' "$out" | sed 's/^/        | /'
fi

# 场景：base 不存在时是用法错误（退出 2）
case_no=$((case_no + 1))
bash "$checker" nosuchbase HEAD >/dev/null 2>&1
code=$?
if [[ $code -eq 2 ]]; then
  pass=$((pass + 1))
  printf 'PASS %2d  %s\n' "$case_no" "base 无法解析时退出 2"
else
  failn=$((failn + 1))
  printf 'FAIL %2d  %s（退出码 %s）\n' "$case_no" "base 无法解析" "$code"
fi

echo
echo "通过 $pass，失败 $failn，共 $case_no"
[[ $failn -eq 0 ]]
